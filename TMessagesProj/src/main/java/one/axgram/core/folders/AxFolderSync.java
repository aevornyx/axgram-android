package one.axgram.core.folders;

import org.json.JSONException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

public final class AxFolderSync implements AutoCloseable {
    public static final class Result {
        public final long revision;
        public final boolean pendingLocalChanges;
        private Result(long revision, boolean pendingLocalChanges) {
            this.revision = revision; this.pendingLocalChanges = pendingLocalChanges;
        }
    }
    public static final class RetryRequired extends IOException {
        private RetryRequired() { super("Sync changed concurrently; retry later"); }
    }
    private final AxFolderStateStore store;
    private final AxFolderTransport transport;
    private final String workspace;
    private final long account;
    private final byte[] key;
    private boolean closed;

    public AxFolderSync(AxFolderStateStore store, AxFolderTransport transport, String workspace, long account, byte[] key) {
        if (store == null || transport == null || account <= 0 || key == null || key.length != 32
                || workspace == null || !workspace.equals(UUID.fromString(workspace).toString())) throw new IllegalArgumentException("Invalid sync context");
        this.store = store; this.transport = transport; this.workspace = workspace; this.account = account; this.key = key.clone();
    }

    public synchronized Result run() throws IOException, JSONException, GeneralSecurityException {
        if (closed) throw new IllegalStateException("Sync is closed");
        for (int attempt = 0; attempt < 4; attempt++) {
            AxFolderReplica local = store.load();
            if (local.account != account) throw new JSONException("Wrong account");
            AxFolderTransport.Snapshot snapshot = transport.read();
            AxFolderDocument remote = snapshot.revision == 0 ? AxFolderDocument.empty(account)
                    : AxFolderEnvelope.open(snapshot.envelope(), key, workspace, account);
            AxFolderReplica merged = local.merge(remote);
            if (!store.compareAndSave(local, merged)) continue;
            if (snapshot.revision > 0 && Arrays.equals(merged.document.encode(), remote.encode())) return result(snapshot.revision, merged);
            if (!Arrays.equals(store.load().encode(), merged.encode())) continue;
            byte[] envelope = AxFolderEnvelope.seal(merged.document, key, workspace);
            try { return result(transport.write(snapshot.revision, envelope), merged); }
            catch (AxFolderTransport.Conflict conflict) { }
        }
        throw new RetryRequired();
    }

    private Result result(long revision, AxFolderReplica synchronizedState) throws IOException, JSONException {
        return new Result(revision, !Arrays.equals(store.load().encode(), synchronizedState.encode()));
    }

    @Override public synchronized void close() {
        Arrays.fill(key, (byte) 0); closed = true;
    }
}
