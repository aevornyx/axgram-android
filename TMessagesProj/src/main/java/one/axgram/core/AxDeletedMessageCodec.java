package one.axgram.core;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

final class AxDeletedMessageCodec {
    static byte[] encode(TLRPC.Message message) {
        SerializedData data = new SerializedData(message.getObjectSize());
        try {
            message.serializeToStream(data);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    static TLRPC.Message decode(byte[] blob, long ownerId) {
        if (blob == null || blob.length < 4) {
            return null;
        }
        SerializedData data = new SerializedData(blob);
        try {
            return AxStoredMessageReader.read(data, ownerId, 0);
        } finally {
            data.cleanup();
        }
    }
}
