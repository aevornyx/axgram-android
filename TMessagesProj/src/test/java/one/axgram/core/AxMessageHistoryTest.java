package one.axgram.core;

import android.content.Context;
import android.util.Base64;

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

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxMessageHistoryTest {
    private Context context;
    private Context oldContext;
    private ApplicationLoader oldLoader;
    private AxMessageHistory history;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        oldContext = ApplicationLoader.applicationContext;
        oldLoader = ApplicationLoader.applicationLoaderInstance;
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader() {
            @Override
            protected boolean isAndroidTestEnv() {
                return false;
            }
        };
        context.deleteDatabase("axgram_edits.db");
        history = new AxMessageHistory(context);
    }

    @After
    public void tearDown() {
        history.close();
        context.deleteDatabase("axgram_edits.db");
        ApplicationLoader.applicationContext = oldContext;
        ApplicationLoader.applicationLoaderInstance = oldLoader;
    }

    @Test
    public void consecutiveEditsIncludeOriginalAndRevertedTextWithinSameSecond() {
        TLRPC.Message a = message("A", 0);
        TLRPC.Message b = message("B", 200);
        TLRPC.Message c = message("A", 200);
        history.record(1001, -55, a, b);
        history.record(1001, -55, b, c);
        ArrayList<AxMessageHistory.Version> versions = versions(1001, -55);
        assertEquals(3, versions.size());
        assertEquals("A", versions.get(0).message.message);
        assertEquals("B", versions.get(1).message.message);
        assertEquals("A", versions.get(2).message.message);
        assertEquals(100, versions.get(2).date);
        assertTrue(versions.get(0).observedAt > 0);
    }

    @Test
    public void repeatedUpdatesAndOlderOfflineResponsesDoNotDuplicateVersions() {
        TLRPC.Message a = message("A", 0);
        TLRPC.Message b = message("B", 200);
        TLRPC.Message c = message("C", 300);
        history.record(1001, -55, a, b);
        history.record(1001, -55, a, b);
        history.record(1001, -55, b, c);
        history.record(1001, -55, a, b);
        history.record(1001, -55, c, b);
        assertEquals(3, versions(1001, -55).size());
        assertEquals("C", versions(1001, -55).get(0).message.message);
    }

    @Test
    public void formattingOnlyEditsAreRetainedButReadStateIsIgnored() {
        TLRPC.Message a = message("Hello", 0);
        TLRPC.Message b = message("Hello", 200);
        b.unread = true;
        history.record(1001, -55, a, b);
        assertTrue(versions(1001, -55).isEmpty());
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        bold.offset = 0;
        bold.length = 5;
        b.flags |= 128;
        b.entities.add(bold);
        history.record(1001, -55, a, b);
        assertEquals(2, versions(1001, -55).size());
        assertTrue(versions(1001, -55).get(0).message.entities.get(0) instanceof TLRPC.TL_messageEntityBold);
    }

    @Test
    public void mediaCaptionsAndIdentityArePreservedWithoutCopyingFiles() {
        TLRPC.Message a = message("First caption", 0);
        TLRPC.Message b = message("Second caption", 200);
        for (TLRPC.Message message : new TLRPC.Message[]{a, b}) {
            message.flags |= 512;
            message.media = new TLRPC.TL_messageMediaPhoto();
            message.media.flags = 1;
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = 777;
            photo.file_reference = new byte[0];
            message.media.photo = photo;
        }
        history.record(1001, -55, a, b);
        assertEquals(2, versions(1001, -55).size());
        assertEquals("First caption", versions(1001, -55).get(1).message.message);
        assertEquals(777, versions(1001, -55).get(1).message.media.photo.id);
    }

    @Test
    public void ownerAndDialogKeysRemainIsolatedAcrossClearAndReopen() {
        history.record(1001, -55, message("A", 0), message("B", 200));
        history.record(2002, -55, message("Other account", 0), message("Private", 200));
        history.record(1001, -66, message("Other dialog", 0), message("Caption", 200));
        history.close();
        history = new AxMessageHistory(context);
        history.clear(1001, -55, 7);
        assertTrue(versions(1001, -55).isEmpty());
        assertEquals(2, versions(2002, -55).size());
        assertEquals(2, versions(1001, -66).size());
        assertTrue(versions(3003, -55).isEmpty());
        assertTrue(versions(0, -55).isEmpty());
    }

    @Test
    public void paginationKeepsOrderWhenServerEditDatesAreEqual() {
        TLRPC.Message before = message("Original", 0);
        for (int i = 0; i < 65; i++) {
            TLRPC.Message next = message("Version " + i, 200);
            history.record(1001, -55, before, next);
            before = next;
        }
        ArrayList<AxMessageHistory.Version> first = versions(1001, -55);
        assertEquals(50, first.size());
        ArrayList<AxMessageHistory.Version> second = history.getVersions(1001, -55, 7, first.get(49).id);
        assertEquals(16, second.size());
        assertTrue(first.get(49).id > second.get(0).id);
        assertEquals("Original", second.get(15).message.message);
    }

    @Test
    public void noUnknownOriginalIsInventedAndInvalidKeysAreRejected() {
        history.record(1001, -55, null, message("B", 200));
        history.record(0, -55, message("A", 0), message("B", 200));
        history.record(1001, 0, message("A", 0), message("B", 200));
        TLRPC.Message differentId = message("B", 200);
        differentId.id = 8;
        history.record(1001, -55, message("A", 0), differentId);
        assertTrue(versions(1001, -55).isEmpty());
    }

    @Test
    public void failedSecondInsertRollsBackOriginalAsWell() {
        history.getWritableDatabase().execSQL("CREATE TRIGGER reject_new BEFORE INSERT ON versions"
                + " WHEN NEW.version_date=200 BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        history.record(1001, -55, message("A", 0), message("B", 200));
        assertTrue(versions(1001, -55).isEmpty());
    }

    @Test
    public void unreadableVersionDoesNotHideOlderVersionsOrBreakPagination() {
        history.record(1001, -55, message("A", 0), message("B", 200));
        history.getWritableDatabase().execSQL("UPDATE versions SET data=x'010203' WHERE version_date=200");
        ArrayList<AxMessageHistory.Version> versions = versions(1001, -55);
        assertEquals(2, versions.size());
        assertNull(versions.get(0).message);
        assertNotNull(versions.get(1).message);
    }

    private ArrayList<AxMessageHistory.Version> versions(long ownerId, long dialogId) {
        return history.getVersions(ownerId, dialogId, 7, Long.MAX_VALUE);
    }

    @Test
    public void ownConfirmedEditRestoresOriginalFromPendingSnapshot() {
        TLRPC.Message pending = message("Pending text", 0);
        pending.params = new HashMap<>();
        SerializedData data = new SerializedData();
        new TLRPC.TL_messageMediaEmpty().serializeToStream(data);
        data.writeString("Original text");
        data.writeString("");
        data.writeInt32(0);
        pending.params.put("prevMedia", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT));
        data.cleanup();
        TLRPC.Message original = AxMessageHistory.previousConfirmed(pending, 3);
        assertNotNull(original);
        history.record(1001, -55, original, message("Confirmed text", 200));
        assertEquals(2, versions(1001, -55).size());
        assertEquals("Original text", versions(1001, -55).get(1).message.message);
    }

    @Test
    public void serializedPositiveIdTextEditReadsPendingStateBeforeAttachmentParams() {
        TLRPC.Message pending = message("Pending text", 0);
        pending.out = true;
        pending.send_state = 3;
        pending.params = new HashMap<>();
        SerializedData snapshot = new SerializedData();
        new TLRPC.TL_messageMediaEmpty().serializeToStream(snapshot);
        snapshot.writeString("Original text");
        snapshot.writeString("");
        snapshot.writeInt32(0);
        pending.params.put("prevMedia", Base64.encodeToString(snapshot.toByteArray(), Base64.DEFAULT));
        snapshot.cleanup();
        SerializedData source = new SerializedData(AxDeletedMessageCodec.encode(pending));
        TLRPC.Message stored = TLRPC.Message.TLdeserialize(source, source.readInt32(true), true);
        assertEquals(0, stored.send_state);
        assertNull(stored.params);
        TLRPC.Message original = AxMessageHistory.readPrevious(stored, source, 1001, 3);
        source.cleanup();
        assertNotNull(original);
        assertEquals("Original text", original.message);
        history.record(1001, -55, original, message("Confirmed text", 200));
        assertEquals(2, versions(1001, -55).size());
        assertEquals("Original text", versions(1001, -55).get(1).message.message);
    }

    @Test
    public void pendingEditIsNotArchivedBeforeServerConfirmation() {
        TLRPC.Message pending = message("Pending", 200);
        pending.send_state = 3;
        history.record(1001, -55, message("Original", 0), pending);
        assertTrue(versions(1001, -55).isEmpty());
    }

    @Test
    public void invalidPendingSnapshotDoesNotInventOriginal() {
        TLRPC.Message pending = message("Pending", 200);
        pending.params = new HashMap<>();
        pending.params.put("prevMedia", "AQID");
        assertNull(AxMessageHistory.previousConfirmed(pending, 3));
        pending.params.clear();
        assertNull(AxMessageHistory.previousConfirmed(pending, 3));
    }

    private static TLRPC.Message message(String text, int editDate) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 7;
        message.date = 100;
        message.edit_date = editDate;
        if (editDate > 0) message.flags |= 32768;
        message.message = text;
        message.peer_id = new TLRPC.TL_peerChat();
        message.peer_id.chat_id = 55;
        return message;
    }
}
