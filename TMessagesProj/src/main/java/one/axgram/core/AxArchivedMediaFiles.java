package one.axgram.core;

import java.io.File;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AxArchivedMediaFiles {
    private final File root;

    AxArchivedMediaFiles(File root) {
        this.root = root;
    }

    Prepared prepare(long ownerId, long dialogId, int messageId, File source, long expectedSize) throws IOException {
        return prepare(ownerId, dialogId, messageId, source, expectedSize, null);
    }

    Prepared prepare(long ownerId, long dialogId, int messageId, File source, long expectedSize, File keyFile) throws IOException {
        try (Source input = open(source, expectedSize, keyFile)) {
            return input == null ? null : prepare(ownerId, dialogId, messageId, input);
        }
    }

    Prepared prepare(long ownerId, long dialogId, int messageId, Source source) throws IOException {
        if (ownerId <= 0 || source == null) {
            return null;
        }
        long sourceSize = source.size;
        File directory = new File(root, ownerId + "/" + dialogId + "/" + messageId + "/" + UUID.randomUUID());
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create archive media directory");
        }
        String name = source.file.getName();
        if (source.encrypted && name.endsWith(".enc")) {
            name = name.substring(0, name.length() - 4);
        }
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "bin";
        if (!extension.matches("[a-zA-Z0-9]{1,12}")) {
            extension = "bin";
        }
        File target = new File(directory, "media." + extension);
        File temporary = File.createTempFile("media-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = source.input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (temporary.length() != sourceSize || source.raw.getChannel().size() != sourceSize
                    || (source.file.exists() && source.file.lastModified() != source.modified)) {
                throw new IOException("Media changed during archive copy");
            }
            return new Prepared(this, temporary, target);
        } catch (IOException | RuntimeException e) {
            try {
                delete(temporary.getAbsolutePath());
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    File export(Source source, File folder, String filename) throws IOException {
        if (source == null) throw new IOException("Archive source unavailable");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create export directory");
        String safeName = new File(filename.replace('\\', '/')).getName();
        if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) throw new IOException("Invalid export filename");
        File target = new File(folder, safeName);
        File temporary = File.createTempFile("archive-export-", ".partial", folder);
        try {
            long copied = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.getFD().sync();
            }
            if (copied != source.size || copied == 0 || source.raw.getChannel().size() != source.size
                    || source.file.lastModified() != source.modified) throw new IOException("Incomplete archive export");
            if (!temporary.renameTo(target)) throw new IOException("Cannot commit archive export");
            return target;
        } finally {
            temporary.delete();
        }
    }

    Source open(File source, long expectedSize, File keyFile) throws IOException {
        if (source == null || !source.isFile()) {
            return null;
        }
        FileInputStream raw = new FileInputStream(source);
        try {
            long size = raw.getChannel().size();
            if ((expectedSize >= 0 && size != expectedSize) || (size == 0 && expectedSize != 0)) {
                raw.close();
                return null;
            }
            return new Source(source, raw, decryptSource(raw, keyFile), size, source.lastModified(), keyFile != null);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private InputStream decryptSource(FileInputStream source, File keyFile) throws IOException {
        if (keyFile == null) {
            return source;
        }
        if (keyFile.length() != 48) {
            throw new IOException("Invalid encrypted cache key");
        }
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        // Telegram cache CTR uses a 12-byte nonce and a 32-bit block counter.
        // jni.c aesCtrDecryptionByteArray replaces the final four IV bytes
        // with fileOffset / 16, so the initial counter is zero even when
        // the key file contains non-zero bytes in that part of the IV.
        try (DataInputStream input = new DataInputStream(new FileInputStream(keyFile))) {
            input.readFully(key);
            input.readFully(iv, 0, 12);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new CipherInputStream(source, cipher);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot decrypt archived cache media", e);
        }
    }

    static final class Source implements AutoCloseable {
        final File file;
        final FileInputStream raw;
        final InputStream input;
        final long size;
        final long modified;
        final boolean encrypted;

        Source(File file, FileInputStream raw, InputStream input, long size, long modified, boolean encrypted) {
            this.file = file;
            this.raw = raw;
            this.input = input;
            this.size = size;
            this.modified = modified;
            this.encrypted = encrypted;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    File save(long ownerId, long dialogId, int messageId, File source, long expectedSize) throws IOException {
        try (Prepared prepared = prepare(ownerId, dialogId, messageId, source, expectedSize)) {
            return prepared == null ? null : prepared.commit();
        }
    }

    static final class Prepared implements AutoCloseable {
        private final AxArchivedMediaFiles archive;
        private final File temporary;
        private final File target;

        Prepared(AxArchivedMediaFiles archive, File temporary, File target) {
            this.archive = archive;
            this.temporary = temporary;
            this.target = target;
        }

        File commit() throws IOException {
            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot commit archive media file");
            }
            return target;
        }

        @Override
        public void close() throws IOException {
            if (temporary.exists()) {
                archive.delete(temporary.getAbsolutePath());
            }
        }
    }

    boolean contains(File file) throws IOException {
        return file != null && file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    void delete(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        if (!contains(file)) {
            return;
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot remove archived media file");
        }
        File parent = file.getParentFile();
        while (parent != null && contains(parent) && parent.delete()) {
            parent = parent.getParentFile();
        }
    }

    void sweepOrphans(Set<String> referenced) throws IOException {
        File base = root.getCanonicalFile();
        File direct = new File(root.getAbsoluteFile().getParentFile().getCanonicalFile(), root.getName());
        if (!direct.equals(base)) {
            return;
        }
        sweep(base, 0, referenced);
    }

    private void sweep(File directory, int depth, Set<String> referenced) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.getCanonicalFile().equals(child.getAbsoluteFile()) || !contains(child)) {
                continue;
            }
            String name = child.getName();
            if (child.isDirectory() && depth < 4) {
                boolean ownedDirectory = depth == 0 ? name.matches("[1-9][0-9]*")
                        : depth < 3 ? name.matches("-?[0-9]+")
                        : name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                if (ownedDirectory) {
                    sweep(child, depth + 1, referenced);
                    child.delete();
                }
            } else if (depth == 4 && child.isFile()
                    && (name.matches("media\\.[a-zA-Z0-9]{1,12}") || name.matches("media-.*\\.tmp"))
                    && !referenced.contains(child.getCanonicalPath())) {
                delete(child.getAbsolutePath());
            }
        }
    }
}
