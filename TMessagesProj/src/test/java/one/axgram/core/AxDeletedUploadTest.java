package one.axgram.core;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxDeletedUploadTest {
    private TLRPC.TL_document source() {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 123;
        document.access_hash = 456;
        document.file_reference = new byte[]{1, 2, 3};
        document.dc_id = 4;
        document.date = 100;
        document.size = 42;
        document.flags = 1;
        document.mime_type = "audio/ogg";
        TLRPC.TL_photoSizeEmpty thumb = new TLRPC.TL_photoSizeEmpty();
        thumb.type = "s";
        document.thumbs.add(thumb);
        return document;
    }

    @Test public void voiceIsNewUploadAndOriginalIsUntouched() {
        TLRPC.TL_document source = source();
        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.voice = true;
        audio.duration = 3;
        audio.flags = 1024 | 4;
        audio.waveform = new byte[]{4, 5, 6};
        source.attributes.add(audio);
        TLRPC.TL_document copy = AxDeletedUpload.createLocalDocument(source);
        assertEquals(0, copy.id);
        assertEquals(0, copy.access_hash);
        assertEquals(0, copy.file_reference.length);
        assertEquals(0, copy.dc_id);
        assertTrue(copy.thumbs.isEmpty());
        assertTrue(copy.attributes.get(0).voice);
        assertEquals(3, copy.attributes.get(0).duration, 0);
        assertArrayEquals(audio.waveform, copy.attributes.get(0).waveform);
        copy.attributes.get(0).waveform[0] = 99;
        assertEquals(4, audio.waveform[0]);
        assertEquals(123, source.id);
        assertEquals(456, source.access_hash);
        assertEquals(1, source.thumbs.size());
    }

    @Test public void roundVideoKeepsRoundFlagAndDimensions() {
        TLRPC.TL_document source = source();
        source.mime_type = "video/mp4";
        TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.round_message = true;
        video.w = 240;
        video.h = 240;
        video.duration = 2;
        source.attributes.add(video);
        TLRPC.TL_document copy = AxDeletedUpload.createLocalDocument(source);
        assertTrue(copy.attributes.get(0).round_message);
        assertEquals(240, copy.attributes.get(0).w);
        assertEquals(240, copy.attributes.get(0).h);
        assertEquals(2, copy.attributes.get(0).duration, 0);
        assertEquals("video/mp4", copy.mime_type);
        assertEquals(0, copy.access_hash);
    }
    @Test public void genericFileKeepsNameMimeAndSizeWithoutRemoteReferences() {
        TLRPC.TL_document source = source();
        source.mime_type = "application/pdf";
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = "report.pdf";
        source.attributes.add(name);
        TLRPC.TL_document copy = AxDeletedUpload.createLocalDocument(source);
        assertEquals("application/pdf", copy.mime_type);
        assertEquals(source.size, copy.size);
        assertEquals("report.pdf", copy.attributes.get(0).file_name);
        assertEquals(0, copy.id);
        assertEquals(0, copy.access_hash);
        assertEquals(0, copy.file_reference.length);
        assertEquals(0, copy.dc_id);
        assertTrue(copy.thumbs.isEmpty());
        copy.attributes.get(0).file_name = "copy.pdf";
        assertEquals("report.pdf", name.file_name);
    }

}
