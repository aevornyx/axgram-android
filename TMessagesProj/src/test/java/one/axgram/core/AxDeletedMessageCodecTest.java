package one.axgram.core;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.SerializedData;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxDeletedMessageCodecTest {
    private ApplicationLoader previousLoader;
    private Context previousContext;

    @Before
    public void setUp() {
        previousLoader = ApplicationLoader.applicationLoaderInstance;
        previousContext = ApplicationLoader.applicationContext;
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader() {
            @Override
            protected boolean isAndroidTestEnv() {
                return false;
            }
        };
    }

    @After
    public void tearDown() {
        ApplicationLoader.applicationLoaderInstance = previousLoader;
        ApplicationLoader.applicationContext = previousContext;
    }

    @Test
    public void restoredMediaRetainsSerializedAttachmentPath() {
        TLRPC.TL_message message = message();
        message.attachPath = "/private/archive/voice.ogg";

        TLRPC.Message decoded = AxDeletedMessageCodec.decode(AxDeletedMessageCodec.encode(message), 1001);

        assertNotNull(decoded);
        assertEquals(message.id, decoded.id);
        assertEquals(message.media.document.id, decoded.media.document.id);
        assertEquals(message.attachPath, decoded.attachPath);
        assertEquals(message.message, decoded.message);
    }

    @Test
    public void deletionReaderRestoresPositiveIdRecordingPathForVoiceAndRound() {
        for (boolean round : new boolean[]{false, true}) {
            TLRPC.TL_message message = message();
            message.message = "";
            message.attachPath = round ? "/recordings/round.mp4" : "/recordings/voice.ogg";
            SerializedData source = new SerializedData(AxDeletedMessageCodec.encode(message));
            TLRPC.Message stored = AxStoredMessageReader.read(source, 1001, 0);
            source.cleanup();
            assertNotNull(stored);
            assertEquals(message.attachPath, stored.attachPath);
            assertEquals(message.media.document.id, stored.media.document.id);
            assertTrue(stored.out);
        }
    }

    @Test
    public void deletionReaderAppliesSqlSendStateBeforeReadingPendingTextParams() {
        TLRPC.TL_message message = message();
        message.media = null;
        message.flags &= ~512;
        message.send_state = 3;
        message.params = new HashMap<>();
        message.params.put("prevMedia", "snapshot");
        message.attachPath = "";
        SerializedData source = new SerializedData(AxDeletedMessageCodec.encode(message));
        TLRPC.Message stored = AxStoredMessageReader.read(source, 1001, 3);
        source.cleanup();
        assertNotNull(stored);
        assertEquals(3, stored.send_state);
        assertEquals("snapshot", stored.params.get("prevMedia"));
    }

    @Test
    public void corruptedArchiveDataDoesNotCreatePartialMessages() {
        byte[] encoded = AxDeletedMessageCodec.encode(message());

        assertNull(AxDeletedMessageCodec.decode(null, 1001));
        assertNull(AxDeletedMessageCodec.decode(new byte[]{1, 2}, 1001));
        assertNull(AxDeletedMessageCodec.decode(new byte[]{0, 0, 0, 0}, 1001));
        assertNull(AxDeletedMessageCodec.decode(Arrays.copyOf(encoded, 8), 1001));
    }

    @Test
    public void voiceAndRoundVideoAttributesSurviveArchiving() {
        for (boolean round : new boolean[]{false, true}) {
            TLRPC.TL_message message = message();
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = 777;
            document.access_hash = 888;
            document.date = 100;
            document.dc_id = 4;
            document.size = 4;
            document.file_reference = new byte[]{3, 4, 5};
            document.mime_type = round ? "video/mp4" : "audio/ogg";
            if (round) {
                TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
                video.round_message = true;
                video.supports_streaming = true;
                video.duration = 12;
                video.w = 240;
                video.h = 240;
                document.attributes.add(video);
            } else {
                TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
                audio.voice = true;
                audio.duration = 12;
                audio.flags = 4;
                audio.waveform = new byte[]{1, 2, 3};
                document.attributes.add(audio);
            }
            message.media.document = document;
            TLRPC.Message restored = AxDeletedMessageCodec.decode(AxDeletedMessageCodec.encode(message), 1001);
            assertNotNull(restored);
            assertEquals(document.mime_type, restored.media.document.mime_type);
            assertEquals(4, restored.media.document.size);
            if (round) {
                assertTrue(restored.media.document.attributes.get(0).round_message);
            } else {
                assertTrue(restored.media.document.attributes.get(0).voice);
                assertArrayEquals(new byte[]{1, 2, 3}, restored.media.document.attributes.get(0).waveform);
            }
        }
    }

    private TLRPC.TL_message message() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 7;
        message.date = 100;
        message.out = true;
        message.flags = (1 << 8) | (1 << 9);
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = 1001;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = 2002;
        message.message = "Voice caption";
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags = 1;
        message.media.document = new TLRPC.TL_documentEmpty();
        message.media.document.id = 777;
        return message;
    }
}
