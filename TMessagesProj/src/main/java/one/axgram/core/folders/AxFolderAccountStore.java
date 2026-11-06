package one.axgram.core.folders;

import android.os.Looper;
import org.json.JSONException;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import java.io.IOException;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class AxFolderAccountStore implements AxFolderStateStore, AutoCloseable {
    private final AccountInstance instance;
    private final long account;
    private final AxFolderIdReservations reservations;
    private final long sessionGeneration;
    private volatile boolean active = true;
    private volatile boolean networkAllowed = true;

    public AxFolderAccountStore(AccountInstance instance, long account) {
        if (instance == null || account <= 0 || instance.getUserConfig().getClientUserId() != account) throw new IllegalArgumentException("Wrong account");
        this.instance = instance; this.account = account;
        sessionGeneration = instance.getMessagesController().getAxFolderSessionGeneration();
        reservations = instance.getMessagesController().getAxFolderReservations();
        if (reservations == null || reservations.account != account) throw new IllegalArgumentException("Account changed");
    }

    private <T> T queued(Callable<T> operation) throws IOException, JSONException {
        DispatchQueue queue = instance.getMessagesStorage().getStorageQueue();
        if (Looper.myLooper() == Looper.getMainLooper() || Thread.currentThread() == queue) throw new IOException("Background sync queue required");
        FutureTask<T> task = new FutureTask<>(operation);
        queue.postRunnable(task);
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException error) { task.cancel(false); Thread.currentThread().interrupt(); throw new IOException("Folder operation interrupted", error); }
        catch (TimeoutException error) { task.cancel(false); throw new IOException("Folder storage timed out", error); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof JSONException) throw (JSONException) error.getCause();
            throw new IOException("Folder storage unavailable", error.getCause());
        }
    }

    private AxFolderSqlStore database() throws IOException {
        if (!isSessionActive() || !instance.getMessagesController().dialogFiltersLoaded) throw new IOException("Account folders are not ready");
        MessagesStorage storage = instance.getMessagesStorage();
        HashMap<Long, String> known = new HashMap<>();
        AxFolderFilterCodec.PeerTypes types = dialog -> {
            if (known.containsKey(dialog)) return known.get(dialog);
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM chats WHERE uid = ?", -dialog);
                String type = null;
                if (cursor.next() && !cursor.isNull(0)) {
                    SerializedData data = new SerializedData(cursor.byteArrayValue(0));
                    try {
                        TLRPC.Chat chat = TLRPC.Chat.TLdeserialize(data, data.readInt32(true), true);
                        type = ChatObject.isChannel(chat) ? "channel" : "chat";
                    } finally { data.cleanup(); }
                }
                known.put(dialog, type); return type;
            } catch (Exception error) { throw new IllegalStateException("Peer metadata unavailable", error); }
            finally { if (cursor != null) cursor.dispose(); }
        };
        return new AxFolderSqlStore(new AxFolderNativeDatabase(storage.getDatabase(), storage.getStorageQueue()), account,
                () -> isSessionActive() ? account : 0, types);
    }

    @Override public AxFolderReplica load() throws IOException, JSONException {
        return queued(() -> {
            AxFolderReplica state = database().load();
            reservations.observeStored(state);
            return state;
        });
    }

    @Override public boolean compareAndSave(AxFolderReplica expected, AxFolderReplica updated) throws IOException, JSONException {
        return queued(() -> {
            if (!networkAllowed) return false;
            AxFolderIdReservations.Lease lease = reservations.beginImport(updated);
            if (lease == null) return false;
            boolean applied = false;
            try {
                AxFolderSqlStore store = database();
                applied = store.compareAndSave(expected, updated);
                if (applied && !Arrays.equals(expected.encode(), updated.encode())) instance.getMessagesStorage().applyAxFolderStorageSnapshot(account, store.nativeSnapshot());
                return applied;
            } finally { reservations.finishImport(lease, applied ? updated : null); }
        });
    }

    public List<MessagesController.DialogFilter> nativeSnapshot() throws IOException, JSONException {
        return queued(() -> database().nativeSnapshot());
    }

    public String enqueueUiChanges(AxFolderReplica before, AxFolderReplica after, Consumer<AxFolderReplica> onSaved, Consumer<Exception> onError) {
        String operation = UUID.randomUUID().toString();
        enqueueUiChanges(operation, before, after, onSaved, onError);
        return operation;
    }

    public void enqueueUiChanges(String operation, AxFolderReplica before, AxFolderReplica after, Consumer<AxFolderReplica> onSaved, Consumer<Exception> onError) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI change queue required");
        if (before == null || after == null || before.account != account || after.account != account || onSaved == null || onError == null)
            throw new IllegalArgumentException("Invalid UI change");
        instance.getMessagesStorage().getStorageQueue().postRunnable(() -> {
            try {
                AxFolderSqlStore store = database();
                AxFolderReplica saved = store.applyUiChanges(operation, before, after);
                reservations.confirmUiCommit(saved);
                instance.getMessagesStorage().applyAxFolderStorageSnapshot(account, store.nativeSnapshot());
                AndroidUtilities.runOnUIThread(() -> {
                    if (isSessionActive()) onSaved.accept(saved);
                    else onError.accept(new IOException("Account changed"));
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> onError.accept(error));
            }
        });
    }

    public void readUiState(Consumer<AxFolderReplica> onSaved, Consumer<Exception> onError) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Folder UI queue required");
        instance.getMessagesStorage().getStorageQueue().postRunnable(() -> {
            try {
                AxFolderReplica state = database().load(); reservations.observeStored(state);
                AndroidUtilities.runOnUIThread(() -> {
                    if (isSessionActive()) onSaved.accept(state);
                    else onError.accept(new IOException("Account changed"));
                });
            } catch (Exception error) { AndroidUtilities.runOnUIThread(() -> onError.accept(error)); }
        });
    }

    public void adoptUiFilters(List<MessagesController.DialogFilter> filters) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Folder UI queue required");
        List<MessagesController.DialogFilter> snapshot = new java.util.ArrayList<>(filters);
        instance.getMessagesStorage().getStorageQueue().postRunnable(() -> {
            if (!isSessionActive()) return;
            instance.getMessagesStorage().applyAxFolderStorageSnapshot(account, snapshot);
            AndroidUtilities.runOnUIThread(() -> {
                if (isSessionActive())
                    instance.getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
            });
        });
    }

    public void setNetworkAllowed(boolean allowed) { networkAllowed = allowed && active; }
    public boolean isSessionActive() {
        return active && instance.getUserConfig().getClientUserId() == account && instance.getMessagesController().getAxFolderSessionGeneration() == sessionGeneration;
    }
    public boolean isNetworkAllowed() { return networkAllowed && isSessionActive(); }
    @Override public void close() { active = false; networkAllowed = false; }
}
