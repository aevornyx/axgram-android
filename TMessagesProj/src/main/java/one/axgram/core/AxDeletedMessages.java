package one.axgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * AxGram: anti-delete storage.
 *
 * Saves the full serialized content of messages just before they are removed
 * from Telegram's local storage (i.e. when someone deletes/recalls them), so
 * they survive and can be viewed later — mirroring AyuGram Desktop.
 *
 * Uses a standalone SQLite database (independent of Telegram's own DB) to keep
 * the feature self-contained and low-risk.
 */
public class AxDeletedMessages extends SQLiteOpenHelper {

    private static final String DB_NAME = "axgram_deleted.db";
    private static final int DB_VERSION = 4;
    private static final String TABLE = "deleted_messages";

    private static volatile AxDeletedMessages instance;
    private static final DispatchQueue mediaQueue = new DispatchQueue("AxGramArchiveMedia");
    private final AccountOwnerResolver accountOwnerResolver;
    private final AxArchivedMediaFiles mediaFiles;
    private final Context context;
    private boolean recoveryScheduled;
    private final Object mediaMutationLock = new Object();
    private final HashMap<Integer, MediaObserver> mediaObservers = new HashMap<>();

    private static final class MediaObserver {
        long ownerId;
        NotificationCenter.NotificationCenterDelegate delegate;
    }

    interface AccountOwnerResolver {
        long getOwnerId(int account);
    }

    public static AxDeletedMessages getInstance() {
        if (instance == null) {
            synchronized (AxDeletedMessages.class) {
                if (instance == null) {
                    instance = new AxDeletedMessages(ApplicationLoader.applicationContext);
                }
            }
        }
        instance.queueRecovery();
        return instance;
    }

    private AxDeletedMessages(Context context) {
        this(context, AxDeletedMessages::resolveAccountOwner);
    }

    AxDeletedMessages(Context context, AccountOwnerResolver accountOwnerResolver) {
        super(context, DB_NAME, null, DB_VERSION);
        this.accountOwnerResolver = accountOwnerResolver;
        this.context = context.getApplicationContext();
        mediaFiles = new AxArchivedMediaFiles(new File(context.getFilesDir(), "axgram_archive"));
    }

    private static long resolveAccountOwner(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        UserConfig config = UserConfig.getInstance(account);
        config.loadConfig();
        return config.getClientUserId();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner_id INTEGER NOT NULL DEFAULT 0,"
                + "legacy_account INTEGER,"
                + "dialog_id INTEGER NOT NULL,"
                + "message_id INTEGER NOT NULL,"
                + "sender_id INTEGER NOT NULL,"
                + "date INTEGER NOT NULL,"
                + "deleted_at INTEGER NOT NULL,"
                + "text TEXT,"
                + "dialog_title TEXT,"
                + "media_path TEXT,"
                + "media_key TEXT,"
                + "media_size INTEGER NOT NULL DEFAULT 0,"
                + "media_state INTEGER NOT NULL DEFAULT 0,"
                + "data BLOB NOT NULL"
                + ")");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_owner_dialog_message ON " + TABLE
                + " (owner_id, dialog_id, message_id) WHERE owner_id>0");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_owner_dialog ON " + TABLE + " (owner_id, dialog_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_owner_media ON " + TABLE + " (owner_id, media_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // AxGram: храним название чата рядом с сообщением. Без него после
            // удаления самого чата показать в просмотре было бы нечего —
            // Telegram уже забыл, как он назывался.
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN dialog_title TEXT");
        }
        if (oldVersion < 3) {
            String legacyTable = TABLE + "_legacy";
            db.execSQL("ALTER TABLE " + TABLE + " RENAME TO " + legacyTable);
            onCreate(db);
            db.execSQL("INSERT INTO " + TABLE
                    + " (id, owner_id, legacy_account, dialog_id, message_id, sender_id, date, deleted_at, text, dialog_title, data)"
                    + " SELECT id, 0, account, dialog_id, message_id, sender_id, date, deleted_at, text, dialog_title, data FROM " + legacyTable);
            try (Cursor accounts = db.rawQuery("SELECT DISTINCT account FROM " + legacyTable, null)) {
                while (accounts.moveToNext()) {
                    int account = accounts.getInt(0);
                    long ownerId = accountOwnerResolver.getOwnerId(account);
                    if (ownerId > 0) {
                        db.execSQL("UPDATE OR IGNORE " + TABLE
                                        + " SET owner_id=? WHERE legacy_account=? AND owner_id=0",
                                new Object[]{ownerId, account});
                    }
                }
            }
            db.execSQL("DROP TABLE " + legacyTable);
        }
        if (oldVersion >= 3 && oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN media_state INTEGER NOT NULL DEFAULT 0");
        }
    }

    private synchronized void queueRecovery() {
        if (!recoveryScheduled) {
            recoveryScheduled = true;
            postMediaTask(this::recoverMediaFiles);
        }
    }

    void recoverMediaFiles() {
        Set<String> referenced = new HashSet<>();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT id,media_path,media_state FROM " + TABLE, null)) {
            while (rows.moveToNext()) {
                long rowId = rows.getLong(0);
                String path = rows.getString(1);
                int state = rows.getInt(2);
                ContentValues values = new ContentValues();
                if (hasMediaFile(path)) {
                    File file = new File(path);
                    referenced.add(file.getCanonicalPath());
                    values.put("media_size", file.length());
                    values.put("media_state", AxArchivePolicy.SAVED);
                } else if (path != null || state == AxArchivePolicy.SAVING || state == AxArchivePolicy.SAVED) {
                    values.putNull("media_path");
                    values.put("media_size", 0);
                    values.put("media_state", AxArchivePolicy.UNAVAILABLE);
                }
                if (values.size() > 0) {
                    getWritableDatabase().update(TABLE, values, "id=?", new String[]{String.valueOf(rowId)});
                }
            }
        } catch (IOException | RuntimeException e) {
            FileLog.e("AxDeletedMessages.recoverMedia", e);
            return;
        }
        try {
            mediaFiles.sweepOrphans(referenced);
        } catch (IOException e) {
            FileLog.e("AxDeletedMessages.sweepMedia", e);
        }
    }

    public long getMediaLimit() {
        return context.getSharedPreferences("axgram_archive_settings", Context.MODE_PRIVATE)
                .getLong("media_limit", AxArchivePolicy.DEFAULT_LIMIT);
    }

    public void setMediaLimit(long bytes) {
        if (bytes < -1) {
            throw new IllegalArgumentException("Invalid media limit");
        }
        context.getSharedPreferences("axgram_archive_settings", Context.MODE_PRIVATE)
                .edit().putLong("media_limit", bytes).apply();
    }

    public long getMediaBytes() {
        try (Cursor total = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(media_size),0) FROM " + TABLE, null)) {
            return total.moveToFirst() ? total.getLong(0) : 0;
        }
    }

    public static final class MediaInfo {
        public final int state;
        public final long size;

        MediaInfo(int state, long size) {
            this.state = state;
            this.size = size;
        }
    }

    public MediaInfo getMediaInfo(int account, long dialogId, int messageId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return new MediaInfo(AxArchivePolicy.UNAVAILABLE, 0);
        }
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT media_path,media_state FROM " + TABLE + " WHERE owner_id=? AND dialog_id=? AND message_id=?",
                new String[]{String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(messageId)})) {
            if (row.moveToFirst()) {
                String path = row.getString(0);
                if (hasMediaFile(path)) {
                    return new MediaInfo(AxArchivePolicy.SAVED, new File(path).length());
                }
                int state = row.getInt(1);
                return new MediaInfo(state == AxArchivePolicy.SAVED ? AxArchivePolicy.UNAVAILABLE : state, 0);
            }
        } catch (IOException | RuntimeException e) {
            FileLog.e("AxDeletedMessages.mediaInfo", e);
            return new MediaInfo(AxArchivePolicy.ERROR, 0);
        }
        return new MediaInfo(AxArchivePolicy.UNAVAILABLE, 0);
    }

    public void retryLocalMedia(int account, long dialogId, int messageId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return;
        }
        postMediaTask(() -> {
            if (accountOwnerResolver.getOwnerId(account) != ownerId) {
                return;
            }
            try (Cursor row = getReadableDatabase().rawQuery(
                    "SELECT id,data FROM " + TABLE + " WHERE owner_id=? AND dialog_id=? AND message_id=?",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(messageId)})) {
                if (row.moveToFirst()) {
                    TLRPC.Message message = AxDeletedMessageCodec.decode(row.getBlob(1), ownerId);
                    if (message != null) {
                        preserveMedia(account, ownerId, dialogId, messageId, row.getLong(0), message, getMediaFile(account, message));
                    }
                }
            }
        });
    }

    private void setMediaState(int account, long ownerId, long dialogId, int messageId, long rowId, int state) {
        ContentValues values = new ContentValues();
        values.put("media_state", state);
        if (getWritableDatabase().update(TABLE, values, "id=? AND owner_id=?",
                new String[]{String.valueOf(rowId), String.valueOf(ownerId)}) > 0) {
            notifyMedia(account, ownerId, dialogId, messageId, null);
        }
    }

    private void notifyMedia(int account, long ownerId, long dialogId, int messageId, File saved) {
        AndroidUtilities.runOnUIThread(() -> {
            if (resolveAccountOwner(account) != ownerId) {
                return;
            }
            if (saved != null && saved.isFile()) {
                for (MessageObject cached : sessionCache.get(account, ownerId, dialogId)) {
                    if (cached.getId() == messageId) {
                        cached.messageOwner.attachPath = saved.getAbsolutePath();
                        cached.checkMediaExistance();
                        cached.forceUpdate = true;
                    }
                }
            }
            NotificationCenter.getInstance(account).postNotificationName(
                    NotificationCenter.axArchivedMediaUpdated, dialogId, messageId, saved);
        });
    }

    private int checkMediaBudget(long bytes, long previousSize, boolean alreadyCopied) {
        long used = Math.max(0, getMediaBytes() - previousSize);
        long free = context.getFilesDir().getUsableSpace();
        int limitState = AxArchivePolicy.check(used, bytes, Long.MAX_VALUE, getMediaLimit(), 0);
        if (limitState != AxArchivePolicy.SAVED) {
            return limitState;
        }
        return AxArchivePolicy.check(0, alreadyCopied ? 0 : bytes, free, -1, AxArchivePolicy.FREE_SPACE_RESERVE);
    }

    /**
     * Save a message that is about to be deleted. Reads the already-deserialized
     * TLRPC.Message, re-serializes it to a blob and stores it.
     * Called from MessagesStorage on the storage thread.
     */
    public void saveMessage(int account, long dialogId, TLRPC.Message message) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0 || message == null || message instanceof TLRPC.TL_messageEmpty) {
            return;
        }
        // Skip service messages with no real content and locally-created placeholders.
        try {
            byte[] blob = AxDeletedMessageCodec.encode(message);

            long senderId = MessageObject.getFromChatId(message);

            ContentValues cv = new ContentValues();
            cv.put("owner_id", ownerId);
            cv.put("dialog_id", dialogId);
            cv.put("message_id", message.id);
            cv.put("sender_id", senderId);
            cv.put("date", message.date);
            cv.put("deleted_at", System.currentTimeMillis() / 1000L);
            cv.put("text", message.message != null ? message.message : "");
            cv.put("dialog_title", resolveDialogTitle(account, dialogId));
            cv.put("data", blob);
            cv.put("media_key", FileLoader.getMessageFileName(message));
            long insertedId = getWritableDatabase().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            long rowId = insertedId >= 0 ? insertedId : findRowId(ownerId, dialogId, message.id);
            if (rowId < 0) {
                return;
            }
            observeMedia(account, ownerId);
            AxArchivedMediaFiles.Source source = openMediaSource(account, message, getMediaFile(account, message));
            if (source != null) {
                int messageId = message.id;
                postMediaTask(() -> preserveMedia(account, ownerId, dialogId, messageId, rowId, source));
            }
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.saveMessage", e);
        }
    }

    public static File getMediaFile(int account, TLRPC.Message message) {
        if (message == null) {
            return null;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        long expectedSize = media != null && media.document != null
                && !(media.document instanceof TLRPC.TL_documentEmpty) ? media.document.size : -1;
        if (message != null && message.attachPath != null && !message.attachPath.isEmpty()) {
            File attached = new File(message.attachPath);
            if (attached.isFile() && (expectedSize < 0 || attached.length() == expectedSize)) {
                return attached;
            }
        }
        File normal = FileLoader.getInstance(account).getPathToMessage(message);
        if (!normal.isFile()) {
            File encrypted = new File(normal.getAbsolutePath() + ".enc");
            File key = new File(FileLoader.getInternalCacheDir(), encrypted.getName() + ".key");
            if (encrypted.isFile() && key.isFile()) {
                return encrypted;
            }
        }
        return normal;
    }

    private long findRowId(long ownerId, long dialogId, int messageId) {
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT id FROM " + TABLE + " WHERE owner_id=? AND dialog_id=? AND message_id=?",
                new String[]{String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(messageId)})) {
            return row.moveToFirst() ? row.getLong(0) : -1;
        }
    }

    private void preserveMedia(int account, long ownerId, long dialogId, int messageId, long rowId,
                               TLRPC.Message message, File source) {
        try {
            preserveMedia(account, ownerId, dialogId, messageId, rowId, openMediaSource(account, message, source));
        } catch (IOException | RuntimeException e) {
            FileLog.e("AxDeletedMessages.openMedia", e);
            setMediaState(account, ownerId, dialogId, messageId, rowId, AxArchivePolicy.ERROR);
        }
    }

    public File exportMediaCopy(int account, TLRPC.Message message, File folder, String filename) throws IOException {
        try (AxArchivedMediaFiles.Source source = openMediaSource(account, message, getMediaFile(account, message))) {
            return mediaFiles.export(source, folder, filename);
        }
    }

    private AxArchivedMediaFiles.Source openMediaSource(int account, TLRPC.Message message, File source) throws IOException {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        long expectedSize = media != null && media.document != null
                && !(media.document instanceof TLRPC.TL_documentEmpty) ? media.document.size : -1;
        File key = null;
        if (source != null && source.getName().endsWith(".enc")) {
            File candidate = new File(FileLoader.getInternalCacheDir(), source.getName() + ".key");
            if (candidate.isFile()) {
                key = candidate;
            } else {
                File normal = FileLoader.getInstance(account).getPathToMessage(message);
                if (source.getAbsolutePath().equals(normal.getAbsolutePath() + ".enc")) {
                    return null;
                }
            }
        }
        return mediaFiles.open(source, expectedSize, key);
    }

    void preserveMedia(int account, long ownerId, long dialogId, int messageId, long rowId,
                               AxArchivedMediaFiles.Source source) {
        try (AxArchivedMediaFiles.Source input = source) {
            long previousSize;
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT media_path,media_size FROM " + TABLE + " WHERE id=? AND owner_id=?",
                    new String[]{String.valueOf(rowId), String.valueOf(ownerId)})) {
                if (!cursor.moveToFirst() || hasMediaFile(cursor.getString(0))) {
                    return;
                }
                previousSize = cursor.getLong(1);
            }
            if (input == null) {
                setMediaState(account, ownerId, dialogId, messageId, rowId, AxArchivePolicy.UNAVAILABLE);
                return;
            }
            int budget = checkMediaBudget(input.size, previousSize, false);
            if (budget != AxArchivePolicy.SAVED) {
                setMediaState(account, ownerId, dialogId, messageId, rowId, budget);
                return;
            }
            setMediaState(account, ownerId, dialogId, messageId, rowId, AxArchivePolicy.SAVING);
            File saved;
            try (AxArchivedMediaFiles.Prepared prepared = mediaFiles.prepare(
                    ownerId, dialogId, messageId, input)) {
                if (prepared == null) {
                    return;
                }
                synchronized (mediaMutationLock) {
                    try (Cursor cursor = getReadableDatabase().rawQuery(
                            "SELECT media_path FROM " + TABLE + " WHERE id=? AND owner_id=?",
                            new String[]{String.valueOf(rowId), String.valueOf(ownerId)})) {
                        if (!cursor.moveToFirst() || hasMediaFile(cursor.getString(0))) {
                            return;
                        }
                    }
                    budget = checkMediaBudget(input.size, previousSize, true);
                    if (budget != AxArchivePolicy.SAVED) {
                        setMediaState(account, ownerId, dialogId, messageId, rowId, budget);
                        return;
                    }
                    saved = prepared.commit();
                    ContentValues values = new ContentValues();
                    values.put("media_path", saved.getAbsolutePath());
                    values.put("media_size", saved.length());
                    values.put("media_state", AxArchivePolicy.SAVED);
                    try {
                        int updated = getWritableDatabase().update(TABLE, values, "id=? AND owner_id=?",
                                new String[]{String.valueOf(rowId), String.valueOf(ownerId)});
                        if (updated == 0) {
                            mediaFiles.delete(saved.getAbsolutePath());
                            return;
                        }
                    } catch (RuntimeException e) {
                        mediaFiles.delete(saved.getAbsolutePath());
                        throw e;
                    }
                }
            }
            notifyMedia(account, ownerId, dialogId, messageId, saved);
        } catch (IOException | RuntimeException e) {
            FileLog.e("AxDeletedMessages.preserveMedia", e);
            setMediaState(account, ownerId, dialogId, messageId, rowId, AxArchivePolicy.ERROR);
        }
    }

    private boolean hasMediaFile(String path) throws IOException {
        return path != null && !path.isEmpty() && new File(path).isFile() && mediaFiles.contains(new File(path));
    }

    private TLRPC.Message deserialize(byte[] blob, long ownerId, String mediaPath) {
        TLRPC.Message message = AxDeletedMessageCodec.decode(blob, ownerId);
        if (message != null) {
            try {
                if (hasMediaFile(mediaPath)) {
                    message.attachPath = mediaPath;
                }
            } catch (IOException e) {
                FileLog.e("AxDeletedMessages.deserializeMedia", e);
            }
        }
        return message;
    }

    private void preserveExistingMediaLater(int account, long ownerId, TLRPC.Message message, String storedPath, long rowId) {
        try {
            if (hasMediaFile(storedPath)) {
                return;
            }
        } catch (IOException e) {
            FileLog.e("AxDeletedMessages.checkArchivedMedia", e);
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null || (media.document == null && media.photo == null)) {
            return;
        }
        postMediaTask(() -> {
            if (accountOwnerResolver.getOwnerId(account) != ownerId) {
                return;
            }
            long dialogId = MessageObject.getDialogId(message);
            ContentValues values = new ContentValues();
            values.put("media_key", FileLoader.getMessageFileName(message));
            getWritableDatabase().update(TABLE, values,
                    "id=? AND owner_id=? AND (media_key IS NULL OR media_key='')",
                    new String[]{String.valueOf(rowId), String.valueOf(ownerId)});
            preserveMedia(account, ownerId, dialogId, message.id, rowId, message, getMediaFile(account, message));
        });
    }

    public void observeMedia(int account) {
        observeMedia(account, accountOwnerResolver.getOwnerId(account));
    }

    private void observeMedia(int account, long ownerId) {
        if (ownerId <= 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (accountOwnerResolver.getOwnerId(account) != ownerId) {
                return;
            }
            MediaObserver current = mediaObservers.get(account);
            if (current != null && current.ownerId == ownerId) {
                return;
            }
            stopObservingMediaOnUIThread(account);
            MediaObserver observer = new MediaObserver();
            observer.ownerId = ownerId;
            observer.delegate = (id, eventAccount, args) -> {
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof File)) {
                    return;
                }
                String key = (String) args[0];
                File file = (File) args[1];
                postMediaTask(() -> archiveDownloadedMedia(account, ownerId, key, file));
            };
            mediaObservers.put(account, observer);
            NotificationCenter.getInstance(account).addObserver(observer.delegate, NotificationCenter.fileLoaded);
            postMediaTask(() -> notifyMedia(account, ownerId, 0, 0, null));
        });
    }

    private void archiveDownloadedMedia(int account, long ownerId, String key, File file) {
        if (accountOwnerResolver.getOwnerId(account) != ownerId) {
            return;
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT dialog_id, message_id, data, id FROM " + TABLE
                        + " WHERE owner_id=? AND media_key=?",
                new String[]{String.valueOf(ownerId), key})) {
            while (cursor.moveToNext()) {
                TLRPC.Message message = AxDeletedMessageCodec.decode(cursor.getBlob(2), ownerId);
                if (message != null) {
                    preserveMedia(account, ownerId, cursor.getLong(0), cursor.getInt(1), cursor.getLong(3), message, file);
                }
            }
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.archiveDownloadedMedia", e);
        }
    }

    private void stopObservingMediaOnUIThread(int account) {
        MediaObserver observer = mediaObservers.remove(account);
        if (observer != null) {
            NotificationCenter.getInstance(account).removeObserver(observer.delegate, NotificationCenter.fileLoaded);
        }
    }

    private static void postMediaTask(Runnable task) {
        mediaQueue.postRunnable(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                FileLog.e("AxDeletedMessages.mediaTask", e);
            }
        });
    }

    private void deleteEntries(String where, String[] args) {
        ArrayList<String> paths = new ArrayList<>();
        synchronized (mediaMutationLock) {
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT media_path FROM " + TABLE + " WHERE " + where + " AND media_path IS NOT NULL", args)) {
                while (cursor.moveToNext()) {
                    paths.add(cursor.getString(0));
                }
            }
            getWritableDatabase().delete(TABLE, where, args);
        }
        postMediaTask(() -> {
            for (String path : paths) {
                try {
                    mediaFiles.delete(path);
                } catch (IOException e) {
                    FileLog.e("AxDeletedMessages.removeMedia", e);
                }
            }
        });
    }

    /**
     * Название чата на момент сохранения. Запрашивается у Telegram, пока чат
     * ещё существует: после его удаления имя взять будет неоткуда.
     */
    private static String resolveDialogTitle(int account, long dialogId) {
        try {
            org.telegram.messenger.MessagesController controller =
                    org.telegram.messenger.MessagesController.getInstance(account);
            if (dialogId > 0) {
                TLRPC.User user = controller.getUser(dialogId);
                if (user != null) {
                    return org.telegram.messenger.UserObject.getUserName(user);
                }
            } else {
                TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat != null && chat.title != null) {
                    return chat.title;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** Сводка по чату для списка: сколько сохранено и когда последнее. */
    public static class DialogSummary {
        public long dialogId;
        public String title;
        public int count;
        public int lastDate;
        public String lastText;
    }

    /**
     * Чаты, по которым что-то сохранено, свежие сверху. Включая те, которых
     * в Telegram уже нет — ради них и заведена колонка с названием.
     */
    public List<DialogSummary> getDialogSummaries(int account) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return new ArrayList<>();
        }
        ArrayList<DialogSummary> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT t.dialog_id, COUNT(*), MAX(t.date), "
                            + "MAX(COALESCE(t.dialog_title,'')), "
                            + "(SELECT x.text FROM " + TABLE + " x WHERE x.owner_id=t.owner_id "
                            + "AND x.dialog_id=t.dialog_id ORDER BY x.date DESC LIMIT 1) "
                            + "FROM " + TABLE + " t WHERE t.owner_id=? "
                            + "GROUP BY t.dialog_id ORDER BY MAX(t.date) DESC",
                    new String[]{String.valueOf(ownerId)});
            while (c.moveToNext()) {
                DialogSummary d = new DialogSummary();
                d.dialogId = c.getLong(0);
                d.count = c.getInt(1);
                d.lastDate = c.getInt(2);
                String t = c.getString(3);
                d.title = (t == null || t.isEmpty()) ? null : t;
                d.lastText = c.getString(4);
                out.add(d);
            }
            c.close();
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.getDialogSummaries", e);
        }
        return out;
    }

    public int getCount(int account, long dialogId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return 0;
        }
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE owner_id=? AND dialog_id=?",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId)});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.getCount", e);
            return 0;
        }
    }

    public int getTotalCount(int account) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return 0;
        }
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE owner_id=?",
                    new String[]{String.valueOf(ownerId)});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Throwable e) {
            return 0;
        }
    }

    public TLRPC.Message getMessage(int account, long dialogId, int messageId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0 || messageId <= 0) return null;
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT data, media_path FROM " + TABLE + " WHERE owner_id=? AND dialog_id=? AND message_id=?",
                new String[]{String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(messageId)})) {
            if (!row.moveToFirst()) return null;
            TLRPC.Message message = deserialize(row.getBlob(0), ownerId, row.getString(1));
            return accountOwnerResolver.getOwnerId(account) == ownerId ? message : null;
        } catch (Exception error) {
            FileLog.e("AxDeletedMessages.getMessage", error);
            return null;
        }
    }

    /**
     * Load saved deleted messages for a dialog, newest-deleted first,
     * reconstructed as TLRPC.Message objects.
     */
    public List<TLRPC.Message> getMessages(int account, long dialogId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return new ArrayList<>();
        }
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT data, media_path, id FROM " + TABLE + " WHERE owner_id=? AND dialog_id=? ORDER BY date ASC, message_id ASC",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId)});
            while (c.moveToNext()) {
                TLRPC.Message message = deserialize(c.getBlob(0), ownerId, c.getString(1));
                if (message != null) {
                    result.add(message);
                    preserveExistingMediaLater(account, ownerId, message, c.getString(1), c.getLong(2));
                }
            }
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.getMessages", e);
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    /** Lightweight holder for the viewer. */
    public static class Entry {
        public long dialogId;
        public long senderId;
        public int date;
        public long deletedAt;
        public TLRPC.Message message;
    }

    /** All saved deleted messages for an account, most-recently-deleted first. */
    public List<Entry> getAllEntries(int account) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return new ArrayList<>();
        }
        ArrayList<Entry> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT dialog_id, sender_id, date, deleted_at, data, media_path, id FROM " + TABLE
                            + " WHERE owner_id=? ORDER BY deleted_at DESC, date DESC",
                    new String[]{String.valueOf(ownerId)});
            while (c.moveToNext()) {
                byte[] blob = c.getBlob(4);
                if (blob == null) continue;
                TLRPC.Message m = deserialize(blob, ownerId, c.getString(5));
                if (m == null) continue;
                Entry e = new Entry();
                e.dialogId = c.getLong(0);
                e.senderId = c.getLong(1);
                e.date = c.getInt(2);
                e.deletedAt = c.getLong(3);
                e.message = m;
                result.add(e);
                preserveExistingMediaLater(account, ownerId, m, c.getString(5), c.getLong(6));
            }
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.getAllEntries", e);
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    private static final AxDeletedMessageCache<MessageObject> sessionCache = new AxDeletedMessageCache<>();

    public static void cacheDeleted(int account, long dialogId, MessageObject mo) {
        if (mo == null || mo.currentAccount != account) {
            return;
        }
        if (mo.getArchivedMediaFile() != null) {
            mo.checkMediaExistance();
        }
        sessionCache.put(account, resolveAccountOwner(account), dialogId, mo.getId(), mo);
    }

    public static void uncacheDeleted(int account, long dialogId, int msgId) {
        sessionCache.remove(account, resolveAccountOwner(account), dialogId, msgId);
    }

    public static ArrayList<MessageObject> getCachedDeleted(int account, long dialogId) {
        return sessionCache.get(account, resolveAccountOwner(account), dialogId);
    }

    public static void clearSessionCache(int account) {
        sessionCache.clearAccount(account);
        AxDeletedMessages archive = instance;
        if (archive != null) {
            AndroidUtilities.runOnUIThread(() -> archive.stopObservingMediaOnUIThread(account));
        }
    }

    /** Сохранённое одного чата, старые сверху — как в обычной переписке. */
    public List<Entry> getDialogEntries(int account, long dialogId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return new ArrayList<>();
        }
        ArrayList<Entry> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT dialog_id, sender_id, date, deleted_at, data, media_path, id FROM " + TABLE
                            + " WHERE owner_id=? AND dialog_id=? ORDER BY date ASC, message_id ASC",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId)});
            while (c.moveToNext()) {
                TLRPC.Message m = deserialize(c.getBlob(4), ownerId, c.getString(5));
                if (m == null) {
                    continue;
                }
                Entry e = new Entry();
                e.dialogId = c.getLong(0);
                e.senderId = c.getLong(1);
                e.date = c.getInt(2);
                e.deletedAt = c.getLong(3);
                e.message = m;
                result.add(e);
                preserveExistingMediaLater(account, ownerId, m, c.getString(5), c.getLong(6));
            }
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.getDialogEntries", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    /** Сколько места занимает архив на диске, в байтах. */
    public long getDatabaseSize() {
        try {
            java.io.File file = new java.io.File(getReadableDatabase().getPath());
            long size = file.exists() ? file.length() : 0;
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(media_size), 0) FROM " + TABLE, null)) {
                if (cursor.moveToFirst()) {
                    size += cursor.getLong(0);
                }
            }
            return size;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Проставляет название чата записям, сохранённым до появления этой колонки —
     * пока чат ещё существует и спросить его имя есть у кого.
     */
    public void setDialogTitle(int account, long dialogId, String title) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return;
        }
        if (title == null || title.isEmpty()) {
            return;
        }
        try {
            getWritableDatabase().execSQL(
                    "UPDATE " + TABLE + " SET dialog_title=? WHERE owner_id=? AND dialog_id=? "
                            + "AND (dialog_title IS NULL OR dialog_title='')",
                    new Object[]{title, ownerId, dialogId});
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.setDialogTitle", e);
        }
    }

    public void clearAll(int account) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            sessionCache.clearAccount(account);
            return;
        }
        try {
            deleteEntries("owner_id=?", new String[]{String.valueOf(ownerId)});
            sessionCache.clearAccount(account);
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.clearAll", e);
        }
    }

    public void deleteOne(int account, long dialogId, int messageId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return;
        }
        try {
            deleteEntries("owner_id=? AND dialog_id=? AND message_id=?",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId), String.valueOf(messageId)});
            sessionCache.remove(account, ownerId, dialogId, messageId);
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.deleteOne", e);
        }
    }

    public void clearDialog(int account, long dialogId) {
        long ownerId = accountOwnerResolver.getOwnerId(account);
        if (ownerId <= 0) {
            return;
        }
        try {
            deleteEntries("owner_id=? AND dialog_id=?",
                    new String[]{String.valueOf(ownerId), String.valueOf(dialogId)});
            sessionCache.clearDialog(account, ownerId, dialogId);
        } catch (Throwable e) {
            FileLog.e("AxDeletedMessages.clearDialog", e);
        }
    }
}
