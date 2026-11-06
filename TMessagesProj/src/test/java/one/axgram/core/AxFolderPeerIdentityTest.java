package one.axgram.core;

import one.axgram.core.folders.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderPeerIdentityTest {
    private static final String X = "10000000-0000-0000-0000-000000000001";
    private static final String Y = "10000000-0000-0000-0000-000000000002";

    private AxFolderReplica folder(String uuid, String type, long id) throws Exception {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 1000; filter.name = "Peer QA"; filter.flags = 31; filter.color = -1;
        long dialog = type.equals("user") ? id : -id;
        filter.alwaysShow.add(dialog); filter.pinnedDialogs.put(dialog, 0);
        return AxFolderReplica.fresh(1).capture(Collections.singletonList(
                new AxFolderReplica.LocalFolder(uuid, filter)), ignored -> type);
    }

    @Test public void differentFoldersCannotCollapseChatAndChannelIntoOneNativeId() throws Exception {
        AxFolderReplica collision = folder(X, "chat", 77).merge(folder(Y, "channel", 77).document);
        try { collision.nativeFolders(); fail("Cross-folder peer collision accepted"); }
        catch (JSONException expected) { }
    }

    @Test public void collisionsAreRejectedBeforeAnyNativeOrReplicaSqlChanges() throws Exception {
        try (AxFolderSqlStoreTest.LabDatabase db = new AxFolderSqlStoreTest.LabDatabase()) {
            AxFolderSqlStore store = new AxFolderSqlStore(db, 1, () -> 1, ignored -> null);
            AxFolderReplica before = store.load();
            AxFolderReplica updated = before.merge(folder(X, "chat", 77).document)
                    .merge(folder(Y, "channel", 77).document);
            try { store.compareAndSave(before, updated); fail("Collision committed to SQL"); }
            catch (JSONException expected) { }
            assertArrayEquals(before.encode(), store.load().encode());
            assertEquals(1, store.nativeSnapshot().size());
            assertTrue(db.query("SELECT uuid FROM ax_folder_identity").isEmpty());
            assertTrue(db.query("SELECT peer FROM dialog_filter_pin_v2").isEmpty());
        }
    }

    @Test public void knownTelegramMetadataCannotReinterpretAnImportedPeer() throws Exception {
        AxFolderReplica incoming = folder(X, "channel", 77);
        try { incoming.peerTypes(ignored -> "chat"); fail("Known chat silently replaces channel"); }
        catch (JSONException expected) { }
        try (AxFolderSqlStoreTest.LabDatabase db = new AxFolderSqlStoreTest.LabDatabase()) {
            AxFolderSqlStore store = new AxFolderSqlStore(db, 1, () -> 1, ignored -> "chat");
            AxFolderReplica before = store.load();
            try { store.compareAndSave(before, before.merge(incoming.document)); fail("Metadata mismatch committed"); }
            catch (JSONException expected) { }
            assertArrayEquals(before.encode(), store.load().encode());
            assertEquals(1, store.nativeSnapshot().size());
        }
    }

    @Test public void unloadedMetadataPreservesWireTypeAndMatchingMetadataIsAccepted() throws Exception {
        AxFolderReplica incoming = folder(X, "channel", 77);
        assertEquals("channel", incoming.peerTypes(ignored -> null).type(-77));
        assertEquals("channel", incoming.peerTypes(ignored -> "channel").type(-77));
        assertArrayEquals(incoming.encode(), incoming.captureNative(incoming.nativeFolders(),
                incoming.peerTypes(ignored -> null)).encode());
    }

    @Test public void userAndChatWithSameBareIdRemainDistinctAndDeletedCollisionIsIgnored() throws Exception {
        AxFolderReplica users = folder(X, "user", 77).merge(folder(Y, "channel", 77).document);
        assertEquals(2, users.nativeFolders().size());
        assertEquals("channel", users.peerTypes(ignored -> null).type(-77));
        AxFolderReplica collision = folder(X, "chat", 77).merge(folder(Y, "channel", 77).document);
        AxFolderReplica deleted = collision.merge(collision.document.delete(Y, collision.device));
        assertEquals(1, deleted.nativeFolders().size());
        assertEquals("chat", deleted.peerTypes(ignored -> null).type(-77));
    }

    @Test public void androidRoundtripsLargeIdsWithoutFloatingPointOrIntTruncation() throws Exception {
        for (long id : new long[]{1, Integer.MAX_VALUE, 2147483648L, 281474976710655L,
                281474976710656L, 4503599627370495L, 2305843009213693951L}) {
            for (String type : new String[]{"user", "chat", "channel"}) {
                AxFolderReplica state = folder(X, type, id);
                MessagesController.DialogFilter projected = state.nativeFolders().get(0);
                assertEquals(type.equals("user") ? id : -id, (long) projected.alwaysShow.get(0));
                assertArrayEquals(state.encode(), state.captureNative(state.nativeFolders(),
                        state.peerTypes(ignored -> type)).encode());
            }
        }
        for (String type : new String[]{"chat", "channel"}) {
            assertEquals(-Long.MAX_VALUE, (long) folder(X, type, Long.MAX_VALUE)
                    .nativeFolders().get(0).alwaysShow.get(0));
        }
    }

    @Test public void sharedUserIdsCannotAliasLocalFolderOrSecretDialogIds() throws Exception {
        JSONObject values = AxFolderFilterCodec.exportFilter(folder(X, "user", 77).nativeFolders().get(0), ignored -> null);
        for (long id : new long[]{DialogObject.makeFolderDialogId(1), DialogObject.makeEncryptedDialogId(77), Long.MAX_VALUE}) {
            JSONObject changed = new JSONObject(values.toString());
            changed.getJSONObject("rules").getJSONArray("include").getJSONObject(0).put("id", Long.toString(id));
            try { AxFolderFilterCodec.importFilter(1000, changed); fail("Local pseudo-dialog accepted as shared user"); }
            catch (JSONException expected) { }
        }
    }
}
