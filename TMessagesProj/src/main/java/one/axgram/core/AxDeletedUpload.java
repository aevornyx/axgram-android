package one.axgram.core;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

public final class AxDeletedUpload {
    private AxDeletedUpload() { }

    public static TLRPC.TL_document createLocalDocument(TLRPC.Document source) {
        SerializedData serialized = new SerializedData();
        source.serializeToStream(serialized);
        SerializedData input = new SerializedData(serialized.toByteArray());
        try {
            TLRPC.TL_document document = (TLRPC.TL_document) TLRPC.Document.TLdeserialize(input, input.readInt32(true), true);
            document.id = 0;
            document.access_hash = 0;
            document.file_reference = new byte[0];
            document.dc_id = 0;
            document.flags = 0;
            document.thumbs.clear();
            document.video_thumbs.clear();
            return document;
        } finally {
            input.cleanup();
            serialized.cleanup();
        }
    }
}
