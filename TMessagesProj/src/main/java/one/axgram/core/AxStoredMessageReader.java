package one.axgram.core;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.TLRPC;

public final class AxStoredMessageReader {
    // send_state lives in a SQLite column, outside the TL blob. It must be set
    // before readAttachPath so pending positive-ID text messages restore params.
    // Outgoing recordings also need their trailing attachment path read before
    // the caller releases Telegram's buffer and removes the original file.
    public static TLRPC.Message read(InputSerializedData source, long ownerId, int sendState) {
        if (source == null || source.remaining() < 4) return null;
        try {
            TLRPC.Message message = TLRPC.Message.TLdeserialize(source, source.readInt32(true), true);
            if (message != null) {
                message.send_state = sendState;
                if (source.remaining() > 0) message.readAttachPath(source, ownerId);
            }
            return message;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AxStoredMessageReader() {
    }
}
