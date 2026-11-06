package one.axgram.core;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import one.axgram.core.folders.*;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderSqlStoreTest {
    private static final String EDIT = "20000000-0000-0000-0000-000000000001";
    private static final String NEXT_EDIT = "20000000-0000-0000-0000-000000000002";
    public static final class LabDatabase implements AxFolderSqlStore.Database, AutoCloseable {
        public final SQLiteDatabase database = SQLiteDatabase.create(null);
        public LabDatabase() {
            database.execSQL("CREATE TABLE dialog_filter_neko(id INTEGER PRIMARY KEY, ord INTEGER, unread_count INTEGER, flags INTEGER, title TEXT, emoticon TEXT, color INTEGER, entities BLOB, noanimate INTEGER)");
            database.execSQL("CREATE TABLE dialog_filter_ep(id INTEGER, peer INTEGER, PRIMARY KEY(id, peer))");
            database.execSQL("CREATE TABLE dialog_filter_pin_v2(id INTEGER, peer INTEGER, pin INTEGER, PRIMARY KEY(id, peer))");
            database.execSQL("CREATE TABLE ax_folder_identity(owner INTEGER NOT NULL, filter_id INTEGER NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(owner, filter_id), UNIQUE(owner, uuid))");
            database.execSQL("INSERT INTO dialog_filter_neko VALUES(0,0,0,31,'ALL_CHATS',NULL,-1,NULL,0)");
        }
        @Override public List<Object[]> query(String sql, Object... arguments) {
            String[] args = new String[arguments.length];
            for (int i = 0; i < args.length; i++) args[i] = arguments[i].toString();
            ArrayList<Object[]> rows = new ArrayList<>();
            try (Cursor cursor = database.rawQuery(sql, args)) {
                while (cursor.moveToNext()) {
                    Object[] row = new Object[cursor.getColumnCount()];
                    for (int i = 0; i < row.length; i++) {
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: row[i] = null; break;
                            case Cursor.FIELD_TYPE_INTEGER: row[i] = cursor.getLong(i); break;
                            case Cursor.FIELD_TYPE_STRING: row[i] = cursor.getString(i); break;
                            case Cursor.FIELD_TYPE_BLOB: row[i] = cursor.getBlob(i); break;
                            default: throw new AssertionError();
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
        @Override public void execute(String sql, Object... arguments) { database.execSQL(sql, arguments); }
        @Override public void close() { database.close(); }
    }
    private SQLiteDatabase db;
    private AxFolderSqlStore store;
    private long owner = 12345;
    private String failSql;
    private final AxFolderSqlStore.Database bridge = new AxFolderSqlStore.Database() {
        @Override public List<Object[]> query(String sql, Object... arguments) {
            String[] args = new String[arguments.length];
            for (int i = 0; i < args.length; i++) args[i] = arguments[i].toString();
            ArrayList<Object[]> rows = new ArrayList<>();
            try (Cursor cursor = db.rawQuery(sql, args)) {
                while (cursor.moveToNext()) {
                    Object[] row = new Object[cursor.getColumnCount()];
                    for (int i = 0; i < row.length; i++) {
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: row[i] = null; break;
                            case Cursor.FIELD_TYPE_INTEGER: row[i] = cursor.getLong(i); break;
                            case Cursor.FIELD_TYPE_STRING: row[i] = cursor.getString(i); break;
                            case Cursor.FIELD_TYPE_BLOB: row[i] = cursor.getBlob(i); break;
                            default: throw new AssertionError();
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
        @Override public void execute(String sql, Object... arguments) {
            if (sql.equals(failSql)) { failSql = null; throw new IllegalStateException("Injected write failure"); }
            db.execSQL(sql, arguments);
        }
    };

    @Before public void start() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE dialog_filter_neko(id INTEGER PRIMARY KEY, ord INTEGER, unread_count INTEGER, flags INTEGER, title TEXT, emoticon TEXT, color INTEGER, entities BLOB, noanimate INTEGER)");
        db.execSQL("CREATE TABLE dialog_filter_ep(id INTEGER, peer INTEGER, PRIMARY KEY(id, peer))");
        db.execSQL("CREATE TABLE dialog_filter_pin_v2(id INTEGER, peer INTEGER, pin INTEGER, PRIMARY KEY(id, peer))");
        db.execSQL("INSERT INTO dialog_filter_neko VALUES(0, 0, 7, 31, 'ALL_CHATS', NULL, -1, NULL, 0)");
        db.execSQL("INSERT INTO dialog_filter_neko VALUES(2, 1, 8, 1, 'Telegram', NULL, -1, NULL, 0)");
        db.execSQL("INSERT INTO dialog_filter_pin_v2 VALUES(2, 777, 0)");
        store = new AxFolderSqlStore(bridge, 12345, () -> owner, id -> null);
    }

    @After public void stop() { db.close(); }

    private AxFolderReplica incoming(AxFolderReplica base) throws Exception {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 1000; filter.name = "Работа"; filter.flags = 31; filter.color = 4; filter.title_noanimate = true;
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold(); bold.offset = 0; bold.length = 6; filter.entities.add(bold);
        filter.alwaysShow.add(77L); filter.pinnedDialogs.put(77, 0); filter.neverShow.add(88L);
        return base.merge(AxFolderReplica.fresh(12345).capture(Collections.singletonList(
                new AxFolderReplica.LocalFolder("10000000-0000-0000-0000-000000000001", filter)), id -> null).document);
    }

    @Test public void importedFolderAndReplicaSurviveTogetherWithoutChangingServerFolder() throws Exception {
        AxFolderReplica before = store.load(), next = incoming(before);
        assertTrue(store.compareAndSave(before, next));
        assertArrayEquals(next.encode(), store.load().encode());
        List<MessagesController.DialogFilter> nativeFolders = store.nativeSnapshot();
        assertEquals(3, nativeFolders.size());
        MessagesController.DialogFilter local = nativeFolders.stream().filter(f -> f.id == 1000).findFirst().get();
        assertEquals("Работа", local.name); assertEquals(4, local.color); assertTrue(local.title_noanimate);
        assertEquals(Collections.singletonList(77L), local.alwaysShow); assertEquals(0, local.pinnedDialogs.get(77));
        assertEquals(Collections.singletonList(88L), local.neverShow); assertNotNull(local.axSyncUuid);
        assertEquals(1, local.entities.size()); assertTrue(local.entities.get(0) instanceof TLRPC.TL_messageEntityBold);
        assertEquals("Telegram", bridge.query("SELECT title FROM dialog_filter_neko WHERE id=2").get(0)[0]);
        assertEquals(777L, bridge.query("SELECT peer FROM dialog_filter_pin_v2 WHERE id=2").get(0)[0]);
    }

    @Test public void failedFinalStateWriteRollsBackAllFolderChanges() throws Exception {
        AxFolderReplica before = store.load(), next = incoming(before);
        failSql = "REPLACE INTO ax_folder_sync(owner, state) VALUES(?, ?)";
        try { store.compareAndSave(before, next); fail(); } catch (java.io.IOException expected) { }
        assertArrayEquals(before.encode(), store.load().encode());
        assertEquals(2, store.nativeSnapshot().size());
        assertTrue(bridge.query("SELECT uuid FROM ax_folder_identity").isEmpty());
        assertTrue(store.compareAndSave(before, next));
    }

    @Test public void localEditDuringNetworkRequestRejectsOldExpectedState() throws Exception {
        db.execSQL("INSERT INTO dialog_filter_neko VALUES(1000,2,0,31,'Before',NULL,-1,NULL,0)");
        AxFolderReplica before = store.load();
        db.execSQL("UPDATE dialog_filter_neko SET title='Edited locally' WHERE id=1000");
        assertFalse(store.compareAndSave(before, before));
        assertEquals("Edited locally", store.load().nativeFolders().get(0).name);
    }

    @Test public void accountChangeAndCorruptStateDoNotResetFolders() throws Exception {
        store.load(); owner = 999;
        try { store.load(); fail(); } catch (java.io.IOException expected) { }
        owner = 12345;
        db.execSQL("UPDATE ax_folder_sync SET state='broken' WHERE owner=12345");
        try { store.load(); fail(); } catch (org.json.JSONException expected) { }
        assertEquals(2, bridge.query("SELECT id FROM dialog_filter_neko").size());
    }

    @Test public void importedChannelKeepsItsTypeBeforeChatMetadataLoads() throws Exception {
        AxFolderReplica before = store.load();
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 1000; filter.name = "Канал"; filter.flags = 31; filter.color = -1; filter.alwaysShow.add(-99L);
        AxFolderReplica remote = AxFolderReplica.fresh(12345).capture(Collections.singletonList(
                new AxFolderReplica.LocalFolder("10000000-0000-0000-0000-000000000002", filter)), id -> "channel");
        AxFolderReplica next = before.merge(remote.document);
        assertTrue(store.compareAndSave(before, next));
        assertArrayEquals(next.encode(), store.load().encode());
        assertEquals(Collections.singletonList(-99L), store.load().nativeFolders().get(0).alwaysShow);
    }

    @Test public void identicalSyncDoesNotDeleteAndRecreateNativeFolders() throws Exception {
        AxFolderReplica before = store.load(), next = incoming(before);
        assertTrue(store.compareAndSave(before, next));
        db.execSQL("UPDATE dialog_filter_neko SET unread_count=42 WHERE id=1000");
        assertTrue(store.compareAndSave(next, next));
        assertEquals(42L, bridge.query("SELECT unread_count FROM dialog_filter_neko WHERE id=1000").get(0)[0]);
    }

    @Test public void unpinnedSecretChatMembershipIsNotSilentlyRemovedDuringImport() throws Exception {
        long secret = org.telegram.messenger.DialogObject.makeEncryptedDialogId(77);
        db.execSQL("INSERT INTO dialog_filter_neko VALUES(1000,2,0,31,'Private',NULL,-1,NULL,0)");
        db.execSQL("INSERT INTO dialog_filter_pin_v2 VALUES(1000,?,?)", new Object[]{secret, Integer.MIN_VALUE});
        AxFolderReplica state = store.load();
        assertEquals(2, new org.json.JSONObject(new String(state.encode(), java.nio.charset.StandardCharsets.UTF_8)).getInt("v"));
        assertFalse(new String(state.document.encode(), java.nio.charset.StandardCharsets.UTF_8).contains(Long.toString(secret)));
        assertEquals(secret, bridge.query("SELECT peer FROM dialog_filter_pin_v2 WHERE id=1000").get(0)[0]);
        assertFalse(bridge.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ax_folder_identity'").isEmpty());
        assertEquals("Private", bridge.query("SELECT title FROM dialog_filter_neko WHERE id=1000").get(0)[0]);
    }

    @Test public void queuedUiRenamePersistsWithoutRevertingAlreadyImportedAppearance() throws Exception {
        AxFolderReplica seed = store.load(), initial = incoming(seed);
        assertTrue(store.compareAndSave(seed, initial));
        List<MessagesController.DialogFilter> oldUi = store.nativeSnapshot();
        AxFolderReplica beforeUi = AxFolderReplica.fresh(owner).captureNative(oldUi, id -> null);
        List<MessagesController.DialogFilter> remoteUi = store.nativeSnapshot();
        remoteUi.stream().filter(f -> f.id == 1000).findFirst().get().color = 6;
        AxFolderReplica remote = initial.captureNative(remoteUi, id -> null);
        assertTrue(store.compareAndSave(initial, remote));
        oldUi.stream().filter(f -> f.id == 1000).findFirst().get().name = "Renamed locally";
        AxFolderReplica afterUi = beforeUi.captureNative(oldUi, id -> null);
        AxFolderReplica saved = store.applyUiChanges(EDIT, beforeUi, afterUi);
        var filter = store.nativeSnapshot().stream().filter(f -> f.id == 1000).findFirst().get();
        assertEquals("Renamed locally", filter.name); assertEquals(6, filter.color);
        AxFolderSqlStore reopened = new AxFolderSqlStore(bridge, owner, () -> owner, id -> null);
        assertArrayEquals(saved.encode(), reopened.load().encode());
        db.execSQL("UPDATE dialog_filter_neko SET unread_count=42 WHERE id=1000");
        assertArrayEquals(saved.encode(), reopened.applyUiChanges(EDIT, beforeUi, afterUi).encode());
        assertEquals(42L, bridge.query("SELECT unread_count FROM dialog_filter_neko WHERE id=1000").get(0)[0]);
    }

    @Test public void queuedUiDeletionKeepsPreviouslyUnseenImportedFolder() throws Exception {
        AxFolderReplica seed = store.load(), initial = incoming(seed);
        assertTrue(store.compareAndSave(seed, initial));
        List<MessagesController.DialogFilter> oldUi = store.nativeSnapshot();
        AxFolderReplica beforeUi = AxFolderReplica.fresh(owner).captureNative(oldUi, id -> null);
        var imported = new MessagesController.DialogFilter();
        imported.id = 1000; imported.name = "Another device"; imported.flags = 31; imported.color = -1;
        String uuid = "10000000-0000-0000-0000-000000000002";
        AxFolderReplica remote = initial.merge(AxFolderReplica.fresh(owner).capture(Collections.singletonList(
                new AxFolderReplica.LocalFolder(uuid, imported)), id -> null).document);
        assertTrue(store.compareAndSave(initial, remote));
        oldUi.removeIf(f -> f.id >= 1000);
        AxFolderReplica saved = store.applyUiChanges(EDIT, beforeUi, beforeUi.captureNative(oldUi, id -> null));
        assertEquals(1, saved.nativeFolders().size()); assertEquals(uuid, saved.nativeFolders().get(0).axSyncUuid);
        assertTrue(saved.document.folder("10000000-0000-0000-0000-000000000001").has("deleted"));
        assertArrayEquals(saved.encode(), store.load().encode());
    }

    @Test public void failedQueuedUiWriteRollsBackAndRetainsImportedState() throws Exception {
        AxFolderReplica seed = store.load(), initial = incoming(seed);
        assertTrue(store.compareAndSave(seed, initial));
        List<MessagesController.DialogFilter> ui = store.nativeSnapshot();
        AxFolderReplica beforeUi = AxFolderReplica.fresh(owner).captureNative(ui, id -> null);
        ui.stream().filter(f -> f.id == 1000).findFirst().get().name = "Changed";
        AxFolderReplica afterUi = beforeUi.captureNative(ui, id -> null);
        failSql = "REPLACE INTO ax_folder_sync(owner, state) VALUES(?, ?)";
        try { store.applyUiChanges(EDIT, beforeUi, afterUi); fail(); } catch (java.io.IOException expected) { }
        assertArrayEquals(initial.encode(), store.load().encode());
        assertEquals("Работа", store.nativeSnapshot().stream().filter(f -> f.id == 1000).findFirst().get().name);
        assertEquals("Changed", store.applyUiChanges(EDIT, beforeUi, afterUi).nativeFolders().get(0).name);
    }

    @Test public void queuedUiChangeRejectsOtherAccountBeforeAnyProjectionIsWritten() throws Exception {
        AxFolderReplica initial = store.load();
        AxFolderReplica other = AxFolderReplica.fresh(999);
        try { store.applyUiChanges(EDIT, initial, other); fail(); } catch (org.json.JSONException expected) { }
        owner = 999;
        try { store.applyUiChanges(EDIT, initial, initial); fail(); } catch (java.io.IOException expected) { }
        owner = 12345;
        assertArrayEquals(initial.encode(), store.load().encode());
        assertEquals(2, store.nativeSnapshot().size());
    }

    @Test public void lateRetryOfOldUiOperationDoesNotRevertNewerSavedEdit() throws Exception {
        AxFolderReplica seed = store.load(), initial = incoming(seed);
        assertTrue(store.compareAndSave(seed, initial));
        List<MessagesController.DialogFilter> ui = store.nativeSnapshot();
        AxFolderReplica before = AxFolderReplica.fresh(owner).captureNative(ui, id -> null);
        var local = ui.stream().filter(f -> f.id == 1000).findFirst().get();
        local.name = "First edit";
        AxFolderReplica first = before.captureNative(ui, id -> null);
        store.applyUiChanges(EDIT, before, first);
        local.name = "Second edit";
        AxFolderReplica second = first.captureNative(ui, id -> null);
        AxFolderReplica latest = store.applyUiChanges(NEXT_EDIT, first, second);
        AxFolderSqlStore reopened = new AxFolderSqlStore(bridge, owner, () -> owner, id -> null);
        assertArrayEquals(latest.encode(), reopened.applyUiChanges(EDIT, before, first).encode());
        assertEquals("Second edit", reopened.nativeSnapshot().stream().filter(f -> f.id == 1000).findFirst().get().name);
        try { reopened.applyUiChanges(EDIT, first, second); fail(); } catch (org.json.JSONException expected) { }
        assertArrayEquals(latest.encode(), reopened.load().encode());
    }

    @Test public void failureToRecordOperationRollsBackProjectionAndCanBeRetried() throws Exception {
        AxFolderReplica seed = store.load(), initial = incoming(seed);
        assertTrue(store.compareAndSave(seed, initial));
        List<MessagesController.DialogFilter> ui = store.nativeSnapshot();
        AxFolderReplica before = AxFolderReplica.fresh(owner).captureNative(ui, id -> null);
        ui.stream().filter(f -> f.id == 1000).findFirst().get().name = "Changed";
        AxFolderReplica after = before.captureNative(ui, id -> null);
        failSql = "INSERT INTO ax_folder_edits(owner, id, digest) VALUES(?, ?, ?)";
        try { store.applyUiChanges(EDIT, before, after); fail(); } catch (java.io.IOException expected) { }
        assertArrayEquals(initial.encode(), store.load().encode());
        assertTrue(bridge.query("SELECT id FROM ax_folder_edits").isEmpty());
        assertEquals("Changed", store.applyUiChanges(EDIT, before, after).nativeFolders().get(0).name);
        assertEquals(1, bridge.query("SELECT id FROM ax_folder_edits").size());
    }

    private AxFolderReplica withSecret(AxFolderReplica before, long secret) throws Exception {
        var draft = before.nativeFolders().get(0);draft.alwaysShow.add(secret);
        draft.pinnedDialogs.put(77L,1);draft.pinnedDialogs.put(secret,0);
        return before.editedNativeFilter(draft,false,false,id->null);
    }

    @Test public void privateOnlyCommitAndLostReceiptReplayPreserveLatestLocalRules() throws Exception {
        AxFolderReplica seed=store.load(), initial=incoming(seed);assertTrue(store.compareAndSave(seed,initial));
        AxFolderReplica before=store.load();long first=org.telegram.messenger.DialogObject.makeEncryptedDialogId(771);
        AxFolderReplica after=withSecret(before,first);assertArrayEquals(before.document.encode(),after.document.encode());
        AxFolderReplica saved=store.applyUiChanges(EDIT,before,after);
        assertTrue(saved.nativeFolders().get(0).alwaysShow.contains(first));
        long second=org.telegram.messenger.DialogObject.makeEncryptedDialogId(772);
        var draft=saved.nativeFolders().get(0);draft.neverShow.add(second);
        var updated=saved.editedNativeFilter(draft,false,false,id->null);
        var latest=store.applyUiChanges(NEXT_EDIT,saved,updated);
        var replay=store.applyUiChanges(EDIT,before,after);
        assertArrayEquals(latest.encode(),replay.encode());assertTrue(replay.nativeFolders().get(0).neverShow.contains(second));
        try{store.applyUiChanges(EDIT,before,withSecret(before,second));fail();}catch(org.json.JSONException expected){}
        assertArrayEquals(latest.encode(),store.load().encode());
        assertFalse(store.compareAndSave(before,before));
    }

    @Test public void privateEditRollsBackWithItsReceiptAndCanBeRetried() throws Exception {
        AxFolderReplica seed=store.load(), initial=incoming(seed);assertTrue(store.compareAndSave(seed,initial));
        var before=store.load();long secret=org.telegram.messenger.DialogObject.makeEncryptedDialogId(773);
        var after=withSecret(before,secret);
        failSql="INSERT INTO ax_folder_edits(owner, id, digest) VALUES(?, ?, ?)";
        try{store.applyUiChanges(EDIT,before,after);fail();}catch(java.io.IOException expected){}
        assertArrayEquals(before.encode(),store.load().encode());
        assertTrue(bridge.query("SELECT peer FROM dialog_filter_pin_v2 WHERE id=1000 AND peer=?",secret).isEmpty());
        var saved=store.applyUiChanges(EDIT,before,after);
        assertTrue(saved.nativeFolders().get(0).alwaysShow.contains(secret));
        assertEquals(0,saved.nativeFolders().get(0).pinnedDialogs.get(secret));
        assertArrayEquals(saved.encode(),new AxFolderSqlStore(bridge,owner,()->owner,id->null).load().encode());
    }

    @Test public void remoteRenameAndStalePrivateOnlyEditorMergeWithoutLeakingIds() throws Exception {
        AxFolderReplica seed=store.load(), initial=incoming(seed);assertTrue(store.compareAndSave(seed,initial));
        var before=store.load();long secret=org.telegram.messenger.DialogObject.makeEncryptedDialogId(774);
        var after=withSecret(before,secret);
        String uuid=before.nativeFolders().get(0).axSyncUuid;
        var title=new org.json.JSONObject(before.document.folder(uuid).getJSONObject("fields").getJSONObject("title").getJSONObject("value").toString()).put("text","Remote");
        var remote=before.merge(before.document.edit(uuid,new org.json.JSONObject().put("title",title),"90000000-0000-0000-0000-000000000001"));
        assertTrue(store.compareAndSave(before,remote));
        var saved=store.applyUiChanges(EDIT,before,after);var nativeFilter=saved.nativeFolders().get(0);
        assertEquals("Remote",nativeFilter.name);assertTrue(nativeFilter.alwaysShow.contains(secret));
        assertFalse(new String(saved.document.encode(),java.nio.charset.StandardCharsets.UTF_8).contains(Long.toString(secret)));
        var removed=saved.merge(saved.document.delete(uuid,"90000000-0000-0000-0000-000000000001"));
        assertTrue(store.compareAndSave(saved,removed));
        assertTrue(bridge.query("SELECT peer FROM dialog_filter_pin_v2 WHERE id=1000").isEmpty());
        assertEquals("Telegram",bridge.query("SELECT title FROM dialog_filter_neko WHERE id=2").get(0)[0]);
    }
}
