package one.axgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxDeletedMessagesDatabaseTest {
    private static final String DATABASE = "axgram_deleted.db";
    private Context context;
    private AxDeletedMessages archive;
    private ApplicationLoader previousLoader;
    private Context previousContext;
    private final Map<Integer, Long> owners = new HashMap<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        previousContext = ApplicationLoader.applicationContext;
        previousLoader = ApplicationLoader.applicationLoaderInstance;
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader() {
            @Override
            protected boolean isAndroidTestEnv() {
                return false;
            }
        };
        context.deleteDatabase(DATABASE);
        owners.put(0, 1001L);
        owners.put(1, 2002L);
    }

    @After
    public void tearDown() {
        if (archive != null) {
            archive.close();
        }
        context.deleteDatabase(DATABASE);
        ApplicationLoader.applicationContext = previousContext;
        ApplicationLoader.applicationLoaderInstance = previousLoader;
    }

    @Test
    public void migrationPreservesBlobsAndSeparatesIdenticalMessageIds() {
        createLegacyDatabase(2, 0, 1);
        openArchive();

        assertEquals(1, archive.getCount(0, -55));
        assertEquals(1, archive.getCount(1, -55));
        try (Cursor cursor = archive.getReadableDatabase().rawQuery(
                "SELECT owner_id, data FROM deleted_messages ORDER BY id", null)) {
            cursor.moveToFirst();
            assertEquals(1001L, cursor.getLong(0));
            assertArrayEquals(new byte[]{0, 11, 22}, cursor.getBlob(1));
            cursor.moveToNext();
            assertEquals(2002L, cursor.getLong(0));
            assertArrayEquals(new byte[]{1, 11, 22}, cursor.getBlob(1));
        }
        archive.deleteOne(0, -55, 7);
        assertEquals(0, archive.getCount(0, -55));
        assertEquals(1, archive.getCount(1, -55));
    }

    @Test
    public void newOwnerOfSameSlotCannotReadOrEraseOldOwnerArchive() {
        createLegacyDatabase(2, 0);
        openArchive();
        assertEquals(1, archive.getTotalCount(0));

        owners.put(0, 3003L);
        assertEquals(0, archive.getTotalCount(0));
        insertOwnedMessage(3003L);
        assertEquals(1, archive.getCount(0, -55));
        archive.clearAll(0);
        assertEquals(0, archive.getTotalCount(0));

        owners.put(0, 1001L);
        assertEquals(1, archive.getCount(0, -55));
    }

    @Test
    public void inactiveLegacySlotIsRetainedWithoutAssigningItToNextLogin() {
        createLegacyDatabase(2, 2);
        openArchive();
        assertEquals(0, archive.getTotalCount(2));
        owners.put(2, 9009L);
        assertEquals(0, archive.getTotalCount(2));
        archive.clearAll(2);

        try (Cursor cursor = archive.getReadableDatabase().rawQuery(
                "SELECT owner_id, legacy_account, data FROM deleted_messages", null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(0, cursor.getLong(0));
            assertEquals(2, cursor.getInt(1));
            assertArrayEquals(new byte[]{2, 11, 22}, cursor.getBlob(2));
        }
    }

    @Test
    public void schemaVersionOneCanUpgradeWithoutLosingMessages() {
        createLegacyDatabase(1, 0);
        openArchive();

        assertEquals(1, archive.getTotalCount(0));
        archive.setDialogTitle(0, -55, "Migrated chat");
        assertEquals("Migrated chat", archive.getDialogSummaries(0).get(0).title);
        assertEquals(4, archive.getReadableDatabase().getVersion());
    }

    @Test
    public void duplicateLegacyOwnersDoNotCauseMigrationDataLoss() {
        owners.put(1, 1001L);
        createLegacyDatabase(2, 0, 1);
        openArchive();

        assertEquals(1, archive.getTotalCount(0));
        try (Cursor cursor = archive.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM deleted_messages", null)) {
            cursor.moveToFirst();
            assertEquals(2, cursor.getInt(0));
        }
    }

    @Test
    public void freshDatabaseRejectsDuplicateMessagesForSameOwner() {
        openArchive();
        insertOwnedMessage(1001L);
        insertOwnedMessage(1001L);
        insertOwnedMessage(2002L);

        assertEquals(1, archive.getTotalCount(0));
        assertEquals(1, archive.getTotalCount(1));
        archive.clearDialog(0, -55);
        assertEquals(0, archive.getTotalCount(0));
        assertEquals(1, archive.getTotalCount(1));
    }

    @Test
    public void reopenedArchiveUsesPrivateMediaAfterCacheRemovalAndCleansItOnDelete() throws Exception {
        File source = new File(context.getCacheDir(), "archived-voice.ogg");
        Files.write(source.toPath(), new byte[]{1, 2, 3, 4});
        AxArchivedMediaFiles files = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
        File saved = files.save(1001, -55, 7, source, 4);
        Files.delete(source.toPath());

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 7;
        message.date = 100;
        message.flags = (1 << 8) | (1 << 9);
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = 1001;
        message.peer_id = new TLRPC.TL_peerChat();
        message.peer_id.chat_id = 55;
        message.message = "";
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags = 1;
        message.media.document = new TLRPC.TL_documentEmpty();
        message.media.document.id = 777;

        openArchive();
        ContentValues values = messageValues();
        values.put("owner_id", 1001L);
        values.put("data", AxDeletedMessageCodec.encode(message));
        values.put("media_path", saved.getAbsolutePath());
        values.put("media_size", saved.length());
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, values);
        archive.close();
        openArchive();

        TLRPC.Message restored = archive.getMessages(0, -55).get(0);
        assertEquals(saved.getAbsolutePath(), restored.attachPath);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(new File(restored.attachPath).toPath()));
        TLRPC.Message inlineCopy = archive.getMessage(0, -55, 7);
        assertNotNull(inlineCopy);
        assertEquals(saved.getAbsolutePath(), inlineCopy.attachPath);
        File exported = archive.exportMediaCopy(0, inlineCopy,
                new File(context.getCacheDir(), "inline-share"), "voice.ogg");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(exported.toPath()));
        assertNull(archive.getMessage(1, -55, 7));
        assertNull(archive.getMessage(0, -56, 7));
        assertNull(archive.getMessage(0, -55, 8));
        assertNull(archive.getMessage(0, -55, 0));

        archive.deleteOne(0, -55, 7);
        Field queueField = AxDeletedMessages.class.getDeclaredField("mediaQueue");
        queueField.setAccessible(true);
        CountDownLatch completed = new CountDownLatch(1);
        ((DispatchQueue) queueField.get(null)).postRunnable(completed::countDown);
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertFalse(saved.exists());
        assertEquals(0, archive.getTotalCount(0));
    }

    private void openArchive() {
        archive = new AxDeletedMessages(context, account -> owners.getOrDefault(account, 0L));
        archive.getWritableDatabase();
        archive.setMediaLimit(AxArchivePolicy.DEFAULT_LIMIT);
    }

    @Test
    public void versionThreeUpgradeAddsStateWithoutReplacingMediaOrMessage() throws Exception {
        File source = new File(context.getCacheDir(), "v3.ogg");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        AxArchivedMediaFiles files = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
        File saved = files.save(1001, -55, 7, source, 3);
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null)) {
            legacy.execSQL("CREATE TABLE deleted_messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "owner_id INTEGER NOT NULL DEFAULT 0,legacy_account INTEGER,dialog_id INTEGER NOT NULL,"
                    + "message_id INTEGER NOT NULL,sender_id INTEGER NOT NULL,date INTEGER NOT NULL,deleted_at INTEGER NOT NULL,"
                    + "text TEXT,dialog_title TEXT,media_path TEXT,media_key TEXT,media_size INTEGER NOT NULL DEFAULT 0,data BLOB NOT NULL)");
            ContentValues values = messageValues();
            values.put("owner_id", 1001L);
            values.put("media_path", saved.getAbsolutePath());
            values.put("media_size", 3);
            legacy.insertOrThrow("deleted_messages", null, values);
            legacy.setVersion(3);
        }
        openArchive();
        assertEquals(4, archive.getReadableDatabase().getVersion());
        assertEquals(AxArchivePolicy.SAVED, archive.getMediaInfo(0, -55, 7).state);
        try (Cursor row = archive.getReadableDatabase().rawQuery("SELECT text,data FROM deleted_messages", null)) {
            assertTrue(row.moveToFirst());
            assertEquals("Archived message", row.getString(0));
            assertArrayEquals(new byte[]{11, 22}, row.getBlob(1));
        }
        assertTrue(saved.isFile());
    }

    @Test
    public void mediaLimitBlocksNewCopyWithoutDeletingOldMediaOrText() throws Exception {
        openArchive();
        File source = new File(context.getCacheDir(), "quota.ogg");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        AxArchivedMediaFiles files = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
        File saved = files.save(1001, -55, 7, source, 3);
        ContentValues old = messageValues();
        old.put("owner_id", 1001L);
        old.put("media_path", saved.getAbsolutePath());
        old.put("media_size", 3);
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, old);
        ContentValues pending = messageValues();
        pending.put("owner_id", 2002L);
        long row = archive.getWritableDatabase().insertOrThrow("deleted_messages", null, pending);
        archive.setMediaLimit(4);
        archive.preserveMedia(1, 2002, -55, 7, row, files.open(source, 3, null));
        assertEquals(AxArchivePolicy.LIMIT_REACHED, archive.getMediaInfo(1, -55, 7).state);
        assertTrue(saved.isFile());
        assertEquals(1, archive.getTotalCount(0));
        assertEquals(1, archive.getTotalCount(1));
        archive.setMediaLimit(0);
        archive.preserveMedia(1, 2002, -55, 7, row, files.open(source, 3, null));
        assertEquals(AxArchivePolicy.DISABLED, archive.getMediaInfo(1, -55, 7).state);
        archive.setMediaLimit(10);
        archive.preserveMedia(1, 2002, -55, 7, row, files.open(source, 3, null));
        assertEquals(AxArchivePolicy.SAVED, archive.getMediaInfo(1, -55, 7).state);
        assertEquals(6, archive.getMediaBytes());
        assertTrue(saved.isFile());
    }

    @Test
    public void recoveryKeepsReferencedMediaAndResetsInterruptedRows() throws Exception {
        openArchive();
        File source = new File(context.getCacheDir(), "recovery.ogg");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        AxArchivedMediaFiles files = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
        File saved = files.save(1001, -55, 7, source, 3);
        File orphan = files.save(2002, -55, 8, source, 3);
        ContentValues old = messageValues();
        old.put("owner_id", 1001L);
        old.put("media_path", saved.getAbsolutePath());
        old.put("media_size", 100);
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, old);
        ContentValues interrupted = messageValues();
        interrupted.put("owner_id", 2002L);
        interrupted.put("media_state", AxArchivePolicy.SAVING);
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, interrupted);
        archive.recoverMediaFiles();
        assertTrue(saved.isFile());
        assertFalse(orphan.exists());
        assertEquals(AxArchivePolicy.SAVED, archive.getMediaInfo(0, -55, 7).state);
        assertEquals(AxArchivePolicy.UNAVAILABLE, archive.getMediaInfo(1, -55, 7).state);
        assertEquals(3, archive.getMediaBytes());
    }

    @Test
    public void delayedCopyCannotAttachOldBytesToRecreatedMessage() throws Exception {
        openArchive();
        ContentValues values = messageValues();
        values.put("owner_id", 1001L);
        long oldId = archive.getWritableDatabase().insertOrThrow("deleted_messages", null, values);
        File source = new File(context.getCacheDir(), "stale-copy.ogg");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        AxArchivedMediaFiles files = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
        AxArchivedMediaFiles.Source input = files.open(source, 3, null);
        archive.getWritableDatabase().delete("deleted_messages", "id=?", new String[]{String.valueOf(oldId)});
        long newId = archive.getWritableDatabase().insertOrThrow("deleted_messages", null, values);
        assertTrue(newId > oldId);
        archive.preserveMedia(0, 1001, -55, 7, oldId, input);
        try (Cursor row = archive.getReadableDatabase().rawQuery(
                "SELECT media_path FROM deleted_messages WHERE id=?", new String[]{String.valueOf(newId)})) {
            assertTrue(row.moveToFirst());
            assertTrue(row.isNull(0));
        }
    }

    @Test
    public void singleMessageLookupPreservesOwnerAndOriginalIdentity() {
        openArchive();
        TLRPC.TL_message first = lookupMessage("First account");
        ContentValues values = messageValues();
        values.put("owner_id", 1001L);
        values.put("data", AxDeletedMessageCodec.encode(first));
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, values);
        TLRPC.TL_message second = lookupMessage("Second account");
        values.put("owner_id", 2002L);
        values.put("data", AxDeletedMessageCodec.encode(second));
        archive.getWritableDatabase().insertOrThrow("deleted_messages", null, values);
        assertEquals("First account", archive.getMessage(0, -55, 7).message);
        assertEquals("Second account", archive.getMessage(1, -55, 7).message);
        owners.put(0, 3003L);
        assertNull(archive.getMessage(0, -55, 7));
        owners.put(2, 1001L);
        assertEquals("First account", archive.getMessage(2, -55, 7).message);
        TLRPC.Message independent = archive.getMessage(2, -55, 7);
        independent.message = "Changed preview";
        assertEquals("First account", archive.getMessage(2, -55, 7).message);
    }

    private TLRPC.TL_message lookupMessage(String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 7;
        message.date = 100;
        message.flags = 1 << 8;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = 77;
        message.peer_id = new TLRPC.TL_peerChat();
        message.peer_id.chat_id = 55;
        message.message = text;
        return message;
    }

    private void createLegacyDatabase(int version, int... accounts) {
        try (SQLiteDatabase db = context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE deleted_messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "account INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL,"
                    + "sender_id INTEGER NOT NULL, date INTEGER NOT NULL, deleted_at INTEGER NOT NULL, text TEXT,"
                    + (version >= 2 ? "dialog_title TEXT," : "")
                    + "data BLOB NOT NULL, UNIQUE(account, dialog_id, message_id) ON CONFLICT IGNORE)");
            db.execSQL("CREATE INDEX idx_dialog ON deleted_messages (account, dialog_id)");
            for (int account : accounts) {
                ContentValues values = messageValues();
                values.put("account", account);
                values.put("data", new byte[]{(byte) account, 11, 22});
                db.insertOrThrow("deleted_messages", null, values);
            }
            db.setVersion(version);
        }
    }

    private void insertOwnedMessage(long ownerId) {
        ContentValues values = messageValues();
        values.put("owner_id", ownerId);
        archive.getWritableDatabase().insertWithOnConflict(
                "deleted_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private ContentValues messageValues() {
        ContentValues values = new ContentValues();
        values.put("dialog_id", -55L);
        values.put("message_id", 7);
        values.put("sender_id", 77L);
        values.put("date", 100);
        values.put("deleted_at", 200);
        values.put("text", "Archived message");
        values.put("data", new byte[]{11, 22});
        return values;
    }
}
