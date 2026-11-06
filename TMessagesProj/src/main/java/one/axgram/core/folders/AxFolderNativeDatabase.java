package one.axgram.core.folders;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.DispatchQueue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class AxFolderNativeDatabase implements AxFolderSqlStore.Database {
    private final SQLiteDatabase database;
    private final DispatchQueue queue;

    public AxFolderNativeDatabase(SQLiteDatabase database, DispatchQueue queue) {
        this.database = database; this.queue = queue;
    }

    private void checkThread() {
        if (Thread.currentThread() != queue) throw new IllegalStateException("Storage queue required");
    }

    @Override public List<Object[]> query(String sql, Object... arguments) throws Exception {
        checkThread();
        SQLiteCursor cursor = database.queryFinalized(sql, arguments);
        try {
            ArrayList<Object[]> rows = new ArrayList<>();
            while (cursor.next()) {
                Object[] row = new Object[cursor.getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    if (cursor.isNull(i)) row[i] = null;
                    else switch (cursor.getTypeOf(i)) {
                        case 1: row[i] = cursor.longValue(i); break;
                        case 3: row[i] = cursor.stringValue(i); break;
                        case 4: row[i] = cursor.byteArrayValue(i); break;
                        default: throw new IllegalStateException("Unsupported folder SQL value");
                    }
                }
                rows.add(row);
            }
            return rows;
        } finally { cursor.dispose(); }
    }

    @Override public void execute(String sql, Object... arguments) throws Exception {
        checkThread();
        SQLitePreparedStatement statement = database.executeFast(sql);
        ArrayList<ByteBuffer> buffers = new ArrayList<>();
        try {
            for (int i = 0; i < arguments.length; i++) {
                Object value = arguments[i]; int index = i + 1;
                if (value == null) statement.bindNull(index);
                else if (value instanceof Integer) statement.bindInteger(index, (Integer) value);
                else if (value instanceof Long) statement.bindLong(index, (Long) value);
                else if (value instanceof String) statement.bindString(index, (String) value);
                else if (value instanceof byte[]) {
                    byte[] bytes = (byte[]) value;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length); buffer.put(bytes); buffer.flip();
                    buffers.add(buffer); statement.bindByteBuffer(index, buffer);
                } else throw new IllegalArgumentException("Unsupported folder SQL binding");
            }
            statement.step();
        } finally { statement.dispose(); buffers.clear(); }
    }
}
