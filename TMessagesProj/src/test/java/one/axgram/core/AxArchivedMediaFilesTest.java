package one.axgram.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AxArchivedMediaFilesTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void archiveSurvivesRemovalOfOriginalCacheFile() throws Exception {
        File source = source("voice.ogg", new byte[]{1, 2, 3, 4});
        AxArchivedMediaFiles archive = archive();
        File saved = archive.save(1001, -55, 7, source, 4);
        Files.delete(source.toPath());

        assertTrue(saved.isFile());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(saved.toPath()));
        assertTrue(archive.contains(saved));
        assertEquals("media.ogg", saved.getName());
    }

    @Test
    public void incompleteDownloadIsNotSavedAsPlayableMedia() throws Exception {
        File source = source("video.mp4", new byte[]{1, 2});
        assertNull(archive().save(1001, -55, 7, source, 100));
    }

    @Test
    public void openedSourceCanBeCopiedOnBackgroundThread() throws Exception {
        File source = source("voice.ogg", new byte[]{1, 2, 3, 4});
        AxArchivedMediaFiles archive = archive();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (AxArchivedMediaFiles.Source input = archive.open(source, 4, null)) {
            File saved = worker.submit(() -> {
                try (AxArchivedMediaFiles.Prepared prepared = archive.prepare(1001, -55, 7, input)) {
                    return prepared.commit();
                }
            }).get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(saved.toPath()));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void accountAndMessageReuseCannotOverwriteAnotherSnapshot() throws Exception {
        File source = source("voice.ogg", new byte[]{1, 2, 3});
        AxArchivedMediaFiles archive = archive();
        File oldSnapshot = archive.save(1001, -55, 7, source, 3);
        Files.write(source.toPath(), new byte[]{9, 8, 7});
        File newSnapshot = archive.save(1001, -55, 7, source, 3);
        File otherOwner = archive.save(2002, -55, 7, source, 3);

        assertNotEquals(oldSnapshot, newSnapshot);
        assertNotEquals(newSnapshot, otherOwner);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(oldSnapshot.toPath()));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(newSnapshot.toPath()));
        archive.delete(oldSnapshot.getAbsolutePath());
        assertFalse(oldSnapshot.exists());
        assertTrue(newSnapshot.isFile());
        assertTrue(otherOwner.isFile());
    }

    @Test
    public void archiveCleanupCannotDeleteExternalFiles() throws Exception {
        File external = source("keep.ogg", new byte[]{1});
        AxArchivedMediaFiles archive = archive();
        assertFalse(archive.contains(external));
        archive.delete(external.getAbsolutePath());
        assertTrue(external.isFile());
    }

    @Test
    public void abandonedCopyDoesNotPublishPartialMedia() throws Exception {
        File source = source("video.mp4", new byte[]{1, 2, 3});
        AxArchivedMediaFiles archive = archive();
        File root = new File(temporary.getRoot(), "archive");
        try (AxArchivedMediaFiles.Prepared ignored = archive.prepare(1001, -55, 7, source, 3)) {
            assertEquals(1, fileCount(root));
        }
        assertEquals(0, fileCount(root));
    }

    @Test
    public void unauthenticatedOwnerAndUnknownEmptyMediaAreRejected() throws Exception {
        AxArchivedMediaFiles archive = archive();
        File source = source("empty.bin", new byte[0]);
        assertNull(archive.save(0, -55, 7, source, 0));
        assertNull(archive.save(1001, -55, 7, source, -1));
        File validEmptyDocument = archive.save(1001, -55, 7, source, 0);
        assertEquals(0, validEmptyDocument.length());
    }

    @Test
    public void encryptedCacheIsPreservedWithoutDependingOnDisposableKeyFile() throws Exception {
        byte[] plaintext = new byte[100_003];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (byte) i;
        }
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 11);
        for (int i = 0; i < 12; i++) iv[i] = (byte) (i + 22);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        File encrypted = source("voice.ogg.enc", cipher.doFinal(plaintext));
        byte[] keyData = new byte[48];
        System.arraycopy(key, 0, keyData, 0, 32);
        System.arraycopy(iv, 0, keyData, 32, 16);
        keyData[44] = 99;
        keyData[45] = 98;
        keyData[46] = 97;
        keyData[47] = 96;
        File keyFile = source("voice.ogg.enc.key", keyData);
        AxArchivedMediaFiles archive = archive();
        File saved;
        try (AxArchivedMediaFiles.Prepared prepared = archive.prepare(1001, -55, 7, encrypted, plaintext.length, keyFile)) {
            saved = prepared.commit();
        }
        Files.delete(encrypted.toPath());
        Files.delete(keyFile.toPath());

        assertEquals("media.ogg", saved.getName());
        assertArrayEquals(plaintext, Files.readAllBytes(saved.toPath()));
    }

    private AxArchivedMediaFiles archive() {
        return new AxArchivedMediaFiles(new File(temporary.getRoot(), "archive"));
    }

    @Test
    public void recoveryRemovesOnlyUnreferencedArchiveFilesAndTemporaryCopies() throws Exception {
        AxArchivedMediaFiles archive = archive();
        File source = source("source.ogg", new byte[]{1, 2, 3});
        File referenced = archive.save(1001, -55, 7, source, 3);
        File orphan = archive.save(2002, -55, 8, source, 3);
        File note = new File(referenced.getParentFile(), "notes.txt");
        Files.write(note.toPath(), new byte[]{9});
        try (AxArchivedMediaFiles.Source input = archive.open(source, 3, null);
             AxArchivedMediaFiles.Prepared abandoned = archive.prepare(1001, -55, 9, input)) {
            archive.sweepOrphans(Collections.singleton(referenced.getCanonicalPath()));
            assertTrue(referenced.isFile());
            assertTrue(note.isFile());
            assertFalse(orphan.exists());
            assertEquals(2, fileCount(new File(temporary.getRoot(), "archive")));
        }
    }

    @Test
    public void outgoingExportDecryptsCacheAndDoesNotModifyOriginal() throws Exception {
        byte[] plaintext = new byte[]{11, 12, 13, 14, 15};
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        File encrypted = source("outgoing.ogg.enc", ciphertext);
        File keyFile = source("outgoing.ogg.enc.key", new byte[48]);
        AxArchivedMediaFiles archive = archive();
        File destination;
        try (AxArchivedMediaFiles.Source input = archive.open(encrypted, plaintext.length, keyFile)) {
            destination = archive.export(input, temporary.newFolder("send"), "voice.ogg");
        }
        assertArrayEquals(plaintext, Files.readAllBytes(destination.toPath()));
        assertArrayEquals(ciphertext, Files.readAllBytes(encrypted.toPath()));
    }

    @Test
    public void outgoingExportRejectsChangedSourceAndLeavesNoPartialFile() throws Exception {
        File original = source("changed-export.ogg", new byte[]{1, 2, 3});
        File folder = temporary.newFolder("failed-export");
        AxArchivedMediaFiles archive = archive();
        try (AxArchivedMediaFiles.Source input = archive.open(original, 3, null)) {
            Files.write(original.toPath(), new byte[]{1});
            try {
                archive.export(input, folder, "voice.ogg");
                org.junit.Assert.fail("Changed source must not be sent");
            } catch (java.io.IOException expected) { }
        }
        assertEquals(0, folder.listFiles().length);
    }

    private File source(String name, byte[] bytes) throws Exception {
        File file = temporary.newFile(name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private int fileCount(File directory) {
        int count = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                count += file.isDirectory() ? fileCount(file) : 1;
            }
        }
        return count;
    }
}
