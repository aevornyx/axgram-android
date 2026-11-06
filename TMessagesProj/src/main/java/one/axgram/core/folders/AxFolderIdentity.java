package one.axgram.core.folders;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.FileLog;
import java.util.UUID;

public final class AxFolderIdentity {
    private AxFolderIdentity() { }

    private static void schema(SQLiteDatabase database) throws Exception {
        database.executeFast("CREATE TABLE IF NOT EXISTS ax_folder_identity(owner INTEGER NOT NULL, filter_id INTEGER NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(owner, filter_id), UNIQUE(owner, uuid))").stepThis().dispose();
    }

    private static boolean valid(String uuid) {
        try { return uuid != null && uuid.equals(UUID.fromString(uuid).toString()); }
        catch (IllegalArgumentException error) { return false; }
    }

    public static String load(SQLiteDatabase database, long owner, int id) {
        if (owner <= 0 || id < 1000) return null;
        SQLiteCursor cursor = null;
        try {
            schema(database);
            cursor = database.queryFinalized("SELECT uuid FROM ax_folder_identity WHERE owner = ? AND filter_id = ?", owner, id);
            if (cursor.next()) {
                String uuid = cursor.stringValue(0);
                if (!valid(uuid)) throw new IllegalStateException("Invalid folder identity");
                return uuid;
            }
        } catch (Exception error) {
            FileLog.e("AxGram folder identity unavailable: " + error.getClass().getSimpleName());
            return null;
        } finally { if (cursor != null) cursor.dispose(); }
        return save(database, owner, id, null);
    }

    public static String save(SQLiteDatabase database, long owner, int id, String uuid) {
        if (owner <= 0 || id < 1000) return null;
        SQLitePreparedStatement statement = null; SQLiteCursor existing = null;
        try {
            schema(database);
            if (uuid == null) {
                existing = database.queryFinalized("SELECT uuid FROM ax_folder_identity WHERE owner = ? AND filter_id = ?", owner, id);
                if (existing.next()) uuid = existing.stringValue(0);
                else uuid = UUID.randomUUID().toString();
                existing.dispose(); existing = null;
            }
            if (!valid(uuid)) throw new IllegalStateException("Invalid folder identity");
            existing = database.queryFinalized("SELECT filter_id FROM ax_folder_identity WHERE owner = ? AND uuid = ?", owner, uuid);
            if (existing.next() && existing.intValue(0) != id) throw new IllegalStateException("Folder identity collision");
            existing.dispose(); existing = null;
            statement = database.executeFast("REPLACE INTO ax_folder_identity(owner, filter_id, uuid) VALUES(?, ?, ?)");
            statement.bindLong(1, owner); statement.bindInteger(2, id); statement.bindString(3, uuid); statement.step();
            return uuid;
        } catch (Exception error) {
            FileLog.e("AxGram folder identity unavailable: " + error.getClass().getSimpleName());
            return null;
        } finally {
            if (existing != null) existing.dispose();
            if (statement != null) statement.dispose();
        }
    }

    public static void remove(SQLiteDatabase database, long owner, int id) {
        if (owner <= 0 || id < 1000) return;
        SQLitePreparedStatement statement = null;
        try {
            schema(database);
            statement = database.executeFast("DELETE FROM ax_folder_identity WHERE owner = ? AND filter_id = ?");
            statement.bindLong(1, owner); statement.bindInteger(2, id); statement.step();
        } catch (Exception error) {
            FileLog.e("AxGram folder identity unavailable: " + error.getClass().getSimpleName());
        } finally { if (statement != null) statement.dispose(); }
    }
}
