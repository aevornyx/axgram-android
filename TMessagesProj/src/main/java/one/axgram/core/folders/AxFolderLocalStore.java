package one.axgram.core.folders;

import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class AxFolderLocalStore implements AxFolderStateStore {
    private static final Object LOCK = new Object();
    private final long account;
    private final File file;
    private final File backup;

    public AxFolderLocalStore(File root, long account) {
        if (account <= 0) throw new IllegalArgumentException("Account required");
        this.account = account;
        file = new File(new File(root, Long.toString(account)), "replica.json");
        backup = new File(file.getPath() + ".bak");
    }

    public AxFolderReplica load() throws IOException, JSONException {
        synchronized (LOCK) { return loadLocked(); }
    }

    private AxFolderReplica loadLocked() throws IOException, JSONException {
        recover();
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > AxFolderReplica.MAX_STATE_BYTES) throw new IOException("Replica file too large");
                bytes.write(buffer, 0, read);
            }
            return AxFolderReplica.decode(bytes.toByteArray(), account);
        } catch (FileNotFoundException error) {
            if (file.exists() || backup.exists()) throw error;
            AxFolderReplica created = AxFolderReplica.fresh(account);
            save(created);
            return created;
        }
    }

    public void save(AxFolderReplica replica) throws IOException, JSONException {
        synchronized (LOCK) { saveLocked(replica); }
    }

    private void saveLocked(AxFolderReplica replica) throws IOException, JSONException {
        if (replica.account != account) throw new JSONException("Wrong account");
        byte[] bytes = replica.encode();
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create replica directory");
        recover();
        if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot preserve previous replica");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes); output.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot commit replica");
        } catch (IOException error) {
            if (file.exists() && !file.delete()) throw new IOException("Cannot discard incomplete replica", error);
            if (backup.exists() && !backup.renameTo(file)) throw new IOException("Cannot restore previous replica", error);
            throw error;
        }
    }

    private void recover() throws IOException {
        if (!backup.exists()) return;
        if (file.exists() && !file.delete()) throw new IOException("Cannot discard incomplete replica");
        if (!backup.renameTo(file)) throw new IOException("Cannot restore previous replica");
    }

    public boolean compareAndSave(AxFolderReplica expected, AxFolderReplica updated) throws IOException, JSONException {
        synchronized (LOCK) {
            if (expected.account != account || updated.account != account || !expected.device.equals(updated.device)) throw new JSONException("Wrong replica identity");
            if (!Arrays.equals(loadLocked().encode(), expected.encode())) return false;
            saveLocked(updated);
            return true;
        }
    }
}
