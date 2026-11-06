package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class AxFolderSqlStore implements AxFolderStateStore {
    public interface Database {
        List<Object[]> query(String sql, Object... arguments) throws Exception;
        void execute(String sql, Object... arguments) throws Exception;
    }
    private interface Operation<T> { T run() throws Exception; }
    private final Database database;
    private final long account;
    private final LongSupplier activeOwner;
    private final AxFolderFilterCodec.PeerTypes peerTypes;

    public AxFolderSqlStore(Database database, long account, LongSupplier activeOwner, AxFolderFilterCodec.PeerTypes peerTypes) {
        if (database == null || account <= 0 || activeOwner == null || peerTypes == null) throw new IllegalArgumentException("Invalid folder storage");
        this.database = database; this.account = account; this.activeOwner = activeOwner; this.peerTypes = peerTypes;
    }

    private <T> T transaction(Operation<T> operation) throws IOException, JSONException {
        if (activeOwner.getAsLong() != account) throw new IOException("Account changed");
        boolean begun = false;
        try {
            database.execute("SAVEPOINT ax_folder_sync_apply"); begun = true;
            database.execute("CREATE TABLE IF NOT EXISTS ax_folder_sync(owner INTEGER PRIMARY KEY, state TEXT NOT NULL)");
            database.execute("CREATE TABLE IF NOT EXISTS ax_folder_identity(owner INTEGER NOT NULL, filter_id INTEGER NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(owner, filter_id), UNIQUE(owner, uuid))");
            database.execute("CREATE TABLE IF NOT EXISTS ax_folder_edits(owner INTEGER NOT NULL, id TEXT NOT NULL, digest TEXT NOT NULL, PRIMARY KEY(owner, id))");
            T result = operation.run();
            if (activeOwner.getAsLong() != account) throw new IOException("Account changed");
            database.execute("RELEASE ax_folder_sync_apply"); begun = false;
            return result;
        } catch (Exception error) {
            if (begun) {
                try { database.execute("ROLLBACK TO ax_folder_sync_apply"); database.execute("RELEASE ax_folder_sync_apply"); }
                catch (Exception rollback) { error.addSuppressed(rollback); }
            }
            if (error instanceof JSONException) throw (JSONException) error;
            throw new IOException("Folder transaction failed", error);
        }
    }

    @Override public AxFolderReplica load() throws IOException, JSONException {
        return transaction(this::loadInternal);
    }

    private AxFolderReplica loadInternal() throws Exception {
        List<Object[]> rows = database.query("SELECT state FROM ax_folder_sync WHERE owner = ?", account);
        AxFolderReplica stored = rows.isEmpty() ? AxFolderReplica.fresh(account)
                : AxFolderReplica.decode(((String) rows.get(0)[0]).getBytes(StandardCharsets.UTF_8), account);
        List<MessagesController.DialogFilter> nativeFilters = readNativeInternal();
        AxFolderReplica captured = stored.captureNative(nativeFilters, types(stored));
        if (rows.isEmpty() || !Arrays.equals(stored.encode(), captured.encode())) saveState(captured);
        return captured;
    }

    @Override public boolean compareAndSave(AxFolderReplica expected, AxFolderReplica updated) throws IOException, JSONException {
        if (expected.account != account || updated.account != account || !expected.device.equals(updated.device)) throw new JSONException("Wrong replica identity");
        return transaction(() -> {
            AxFolderReplica current = loadInternal();
            if (!Arrays.equals(current.encode(), expected.encode())) return false;
            if (!Arrays.equals(current.encode(), updated.encode())) applyInternal(updated);
            return true;
        });
    }

    public AxFolderReplica applyUiChanges(String operation, AxFolderReplica before, AxFolderReplica after) throws IOException, JSONException {
        if (before.account != account || after.account != account) throw new JSONException("Wrong UI account");
        try { if (!UUID.fromString(operation).toString().equals(operation)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException error) { throw new JSONException("Invalid edit identity"); }
        byte[] beforeBytes = before.encode(), afterBytes = after.encode();
        return transaction(() -> {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update("AxGramFolders/UI-edit/v1".getBytes(StandardCharsets.UTF_8));
            hash.update(ByteBuffer.allocate(4).putInt(beforeBytes.length).array()); hash.update(beforeBytes); hash.update(afterBytes);
            StringBuilder digest = new StringBuilder();
            for (byte value : hash.digest()) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            List<Object[]> receipts = database.query("SELECT digest FROM ax_folder_edits WHERE owner = ? AND id = ?", account, operation);
            if (!receipts.isEmpty()) {
                if (!digest.toString().equals(receipts.get(0)[0])) throw new JSONException("Edit identity reused with different data");
                return loadInternal();
            }
            AxFolderReplica current = loadInternal();
            AxFolderReplica updated = current.replayUiChanges(before, after);
            if (!Arrays.equals(current.encode(), updated.encode())) applyInternal(updated);
            database.execute("INSERT INTO ax_folder_edits(owner, id, digest) VALUES(?, ?, ?)", account, operation, digest.toString());
            return updated;
        });
    }

    private void applyInternal(AxFolderReplica updated) throws Exception {
        updated.peerTypes(peerTypes);
        List<MessagesController.DialogFilter> locals = updated.nativeFolders();
        List<MessagesController.DialogFilter> existing = readNativeInternal();
        ArrayList<Integer> serverIds = new ArrayList<>();
        for (MessagesController.DialogFilter filter : existing) if (filter.id < 1000) serverIds.add(filter.id);
        List<Integer> order = updated.nativeOrder(serverIds);
        database.execute("DELETE FROM dialog_filter_ep WHERE id >= 1000");
        database.execute("DELETE FROM dialog_filter_pin_v2 WHERE id >= 1000");
        database.execute("DELETE FROM dialog_filter_neko WHERE id >= 1000");
        database.execute("DELETE FROM ax_folder_identity WHERE owner = ? AND filter_id >= 1000", account);
        for (MessagesController.DialogFilter filter : locals) {
            filter.order = order.indexOf(filter.id);
            writeFilter(filter);
        }
        for (int i = 0; i < order.size(); i++) {
            int id = order.get(i);
            if (id < 1000) database.execute("UPDATE dialog_filter_neko SET ord = ? WHERE id = ?", i, id);
        }
        saveState(updated);
    }

    public List<MessagesController.DialogFilter> nativeSnapshot() throws IOException, JSONException {
        return transaction(this::readNativeInternal);
    }

    private void saveState(AxFolderReplica state) throws Exception {
        database.execute("REPLACE INTO ax_folder_sync(owner, state) VALUES(?, ?)", account, new String(state.encode(), StandardCharsets.UTF_8));
    }

    private List<MessagesController.DialogFilter> readNativeInternal() throws Exception {
        List<Object[]> rows = database.query("SELECT f.id, f.ord, f.unread_count, f.flags, f.title, f.emoticon, f.color, f.entities, f.noanimate, i.uuid FROM dialog_filter_neko f LEFT JOIN ax_folder_identity i ON i.owner = ? AND i.filter_id = f.id ORDER BY f.ord, f.id", account);
        ArrayList<MessagesController.DialogFilter> result = new ArrayList<>();
        for (Object[] row : rows) {
            MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
            filter.id = integer(row[0]); filter.order = integer(row[1]); filter.unreadCount = integer(row[2]);
            filter.pendingUnreadCount = filter.unreadCount; filter.flags = integer(row[3]); filter.name = (String) row[4];
            filter.emoticon = (String) row[5]; filter.color = integer(row[6]); filter.title_noanimate = integer(row[8]) != 0;
            if (row[7] != null) {
                SerializedData input = new SerializedData((byte[]) row[7]);
                try { filter.entities = Vector.deserialize(input, TLRPC.MessageEntity::TLdeserialize, true); }
                finally { input.cleanup(); }
            }
            if (filter.id >= 1000) {
                filter.axSyncUuid = (String) row[9];
                if (filter.axSyncUuid == null) {
                    filter.axSyncUuid = UUID.randomUUID().toString();
                    database.execute("INSERT INTO ax_folder_identity(owner, filter_id, uuid) VALUES(?, ?, ?)", account, filter.id, filter.axSyncUuid);
                }
            }
            for (Object[] peer : database.query("SELECT peer, pin FROM dialog_filter_pin_v2 WHERE id = ?", filter.id)) {
                long id = ((Number) peer[0]).longValue(); int pin = integer(peer[1]);
                filter.alwaysShow.add(id);
                if (pin != Integer.MIN_VALUE) filter.pinnedDialogs.put(id, pin);
            }
            for (Object[] peer : database.query("SELECT peer FROM dialog_filter_ep WHERE id = ?", filter.id)) filter.neverShow.add(((Number) peer[0]).longValue());
            result.add(filter);
        }
        return result;
    }

    private void writeFilter(MessagesController.DialogFilter filter) throws Exception {
        SerializedData output = new SerializedData();
        byte[] entities;
        try { Vector.serialize(output, filter.entities); entities = output.toByteArray(); }
        finally { output.cleanup(); }
        database.execute("INSERT INTO dialog_filter_neko(id, ord, unread_count, flags, title, emoticon, color, entities, noanimate) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                filter.id, filter.order, -1, filter.flags, filter.name, filter.emoticon, filter.color, entities, filter.title_noanimate ? 1 : 0);
        database.execute("INSERT INTO ax_folder_identity(owner, filter_id, uuid) VALUES(?, ?, ?)", account, filter.id, filter.axSyncUuid);
        for (long peer : filter.alwaysShow) database.execute("INSERT INTO dialog_filter_pin_v2(id, peer, pin) VALUES(?, ?, ?)", filter.id, peer, filter.pinnedDialogs.get(peer, Integer.MIN_VALUE));
        for (long peer : filter.neverShow) database.execute("INSERT INTO dialog_filter_ep(id, peer) VALUES(?, ?)", filter.id, peer);
    }

    private AxFolderFilterCodec.PeerTypes types(AxFolderReplica state) throws JSONException {
        return state.peerTypes(peerTypes);
    }

    private static int integer(Object value) { return ((Number) value).intValue(); }
}
