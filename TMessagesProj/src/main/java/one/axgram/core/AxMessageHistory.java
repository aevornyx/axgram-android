package one.axgram.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

public final class AxMessageHistory extends SQLiteOpenHelper {
    private static volatile AxMessageHistory instance;

    public static AxMessageHistory getInstance() {
        if (instance == null) {
            synchronized (AxMessageHistory.class) {
                if (instance == null) {
                    instance = new AxMessageHistory(ApplicationLoader.applicationContext);
                }
            }
        }
        return instance;
    }

    AxMessageHistory(Context context) {
        super(context, "axgram_edits.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE versions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL,"
                + "version_date INTEGER NOT NULL, observed_at INTEGER NOT NULL, fingerprint BLOB NOT NULL, data BLOB NOT NULL)");
        db.execSQL("CREATE INDEX versions_message ON versions(owner_id, dialog_id, message_id, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void record(long ownerId, long dialogId, TLRPC.Message previous, TLRPC.Message next) {
        if (ownerId <= 0 || dialogId == 0 || previous == null || next == null
                || !(previous instanceof TLRPC.TL_message) || !(next instanceof TLRPC.TL_message)
                || previous.id <= 0 || previous.id != next.id || next.edit_date <= 0
                || next.edit_date < previous.edit_date || next.send_state != 0) {
            return;
        }
        try {
            byte[] before = fingerprint(previous);
            byte[] after = fingerprint(next);
            if (Arrays.equals(before, after)) return;
            byte[] oldBlob = AxDeletedMessageCodec.encode(previous);
            byte[] newBlob = AxDeletedMessageCodec.encode(next);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                byte[] last = null;
                int lastDate = 0;
                try (Cursor cursor = db.rawQuery("SELECT fingerprint, version_date FROM versions WHERE owner_id=?"
                        + " AND dialog_id=? AND message_id=? ORDER BY id DESC LIMIT 1", key(ownerId, dialogId, next.id))) {
                    if (cursor.moveToFirst()) {
                        last = cursor.getBlob(0);
                        lastDate = cursor.getInt(1);
                    }
                }
                if (next.edit_date >= lastDate && !Arrays.equals(last, after)) {
                    if (last == null || (!Arrays.equals(last, before)
                            && (previous.edit_date > 0 ? previous.edit_date : previous.date) >= lastDate)) {
                        insert(db, ownerId, dialogId, previous, before, oldBlob);
                    }
                    insert(db, ownerId, dialogId, next, after, newBlob);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static TLRPC.Message readPrevious(TLRPC.Message message, InputSerializedData source,
                                            long ownerId, int sendState) {
        if (message == null) return null;
        message.send_state = sendState;
        message.readAttachPath(source, ownerId);
        return previousConfirmed(message, sendState);
    }

    public static TLRPC.Message previousConfirmed(TLRPC.Message message, int sendState) {
        if (sendState == 0) return message;
        if (sendState != MessageObject.MESSAGE_SEND_STATE_EDITING || message == null
                || message.params == null || !message.params.containsKey("prevMedia")) return null;
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(message.params.get("prevMedia"), Base64.DEFAULT));
            TLRPC.MessageMedia media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(true), true);
            String text = data.readString(true);
            String path = data.readString(true);
            int count = data.readInt32(true);
            if (count < 0 || count > data.remaining() / 4) return null;
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entities.add(TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(true), true));
            }
            if (data.remaining() != 0 || media == null) return null;
            message.media = media;
            message.message = text;
            message.attachPath = path;
            message.entities = entities;
            message.flags = (message.flags | 512) & ~128;
            if (!entities.isEmpty()) message.flags |= 128;
            message.send_state = 0;
            return message;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (data != null) data.cleanup();
        }
    }

    private static byte[] fingerprint(TLRPC.Message message) throws Exception {
        SerializedData data = new SerializedData();
        try {
            data.writeString(message.message == null ? "" : message.message);
            data.writeInt32(message.entities.size());
            for (TLRPC.MessageEntity entity : message.entities) entity.serializeToStream(data);
            TLRPC.Document document = MessageObject.getDocument(message);
            TLRPC.Photo photo = MessageObject.getPhoto(message);
            data.writeInt64(document == null ? 0 : document.id);
            data.writeInt64(photo == null ? 0 : photo.id);
            return MessageDigest.getInstance("SHA-256").digest(data.toByteArray());
        } finally {
            data.cleanup();
        }
    }

    private static void insert(SQLiteDatabase db, long ownerId, long dialogId, TLRPC.Message message,
                               byte[] fingerprint, byte[] blob) {
        ContentValues values = new ContentValues();
        values.put("owner_id", ownerId);
        values.put("dialog_id", dialogId);
        values.put("message_id", message.id);
        values.put("version_date", message.edit_date > 0 ? message.edit_date : message.date);
        values.put("observed_at", System.currentTimeMillis() / 1000);
        values.put("fingerprint", fingerprint);
        values.put("data", blob);
        db.insertOrThrow("versions", null, values);
    }

    public static final class Version {
        public final long id;
        public final int date;
        public final long observedAt;
        public final TLRPC.Message message;

        private Version(long id, int date, long observedAt, TLRPC.Message message) {
            this.id = id;
            this.date = date;
            this.observedAt = observedAt;
            this.message = message;
        }
    }

    public ArrayList<Version> getVersions(long ownerId, long dialogId, int messageId, long beforeId) {
        ArrayList<Version> result = new ArrayList<>();
        if (ownerId <= 0) return result;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,version_date,observed_at,data FROM versions"
                + " WHERE owner_id=? AND dialog_id=? AND message_id=? AND id<? ORDER BY id DESC LIMIT 50",
                new String[]{Long.toString(ownerId), Long.toString(dialogId), Integer.toString(messageId), Long.toString(beforeId)})) {
            while (cursor.moveToNext()) {
                TLRPC.Message message = AxDeletedMessageCodec.decode(cursor.getBlob(3), ownerId);
                result.add(new Version(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2), message));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public void clear(long ownerId, long dialogId, int messageId) {
        if (ownerId <= 0) return;
        getWritableDatabase().delete("versions", "owner_id=? AND dialog_id=? AND message_id=?", key(ownerId, dialogId, messageId));
    }

    private static String[] key(long ownerId, long dialogId, int messageId) {
        return new String[]{Long.toString(ownerId), Long.toString(dialogId), Integer.toString(messageId)};
    }
}
