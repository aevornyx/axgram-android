package one.axgram.core;

import one.axgram.core.folders.AxFolderReplica;
import one.axgram.core.folders.AxFolderLocalStore;
import one.axgram.core.folders.AxFolderDocument;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesController;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderReplicaTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String X = "10000000-0000-0000-0000-000000000001";
    private static final String Y = "10000000-0000-0000-0000-000000000002";

    private AxFolderReplica.LocalFolder folder(String uuid, int id, String name) {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = id; filter.name = name; filter.flags = 31; filter.color = -1;
        return new AxFolderReplica.LocalFolder(uuid, filter);
    }

    @Test public void captureAndRestartPreserveIdentityWithoutCreatingNewEdits() throws Exception {
        var input = Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work"));
        AxFolderReplica a = AxFolderReplica.fresh(1).capture(input, id -> null);
        AxFolderReplica restored = AxFolderReplica.decode(a.encode(), 1);
        assertEquals(a.device, restored.device);
        assertEquals(X, restored.localFolders().get(0).uuid);
        assertArrayEquals(a.encode(), restored.capture(input, id -> null).encode());
        assertEquals(Arrays.asList(0, 1000), restored.nativeOrder(Arrays.asList(0)));
    }

    @Test public void persistedStateMatchesSharedWindowsBytes() throws Exception {
        try (var input = getClass().getResourceAsStream("/axgram-folder-sync-v1.json")) {
            assertNotNull(input);
            JSONObject fixture = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject("replica");
            AxFolderReplica seed = AxFolderReplica.decode(fixture.getString("seedWire").getBytes(StandardCharsets.UTF_8), 12345);
            AxFolderReplica captured = seed.capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work")), id -> null);
            assertArrayEquals(fixture.getString("capturedWire").getBytes(StandardCharsets.UTF_8), captured.encode());
        }
    }

    @Test public void implicitNewServerFolderDoesNotCreateAReorderUntilUserMovesIt() throws Exception {
        var original = Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work"));
        AxFolderReplica state = AxFolderReplica.fresh(1).capture(original, id -> null);
        var projected = Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work"), folder(null, 2, "Telegram"));
        assertArrayEquals(state.encode(), state.capture(projected, id -> null).encode());
        AxFolderReplica moved = state.capture(Arrays.asList(folder(null, 2, "Telegram"), folder(null, 0, "All"), folder(X, 1000, "Work")), id -> null);
        assertEquals(Arrays.asList(2, 0, 1000), moved.nativeOrder(Arrays.asList(0, 2)));
        assertTrue(moved.document.maxClock() > state.document.maxClock());
    }

    @Test public void devicesWithSameNumericLocalIdKeepBothIndependentFolders() throws Exception {
        AxFolderReplica a = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(X, 1000, "Windows")), id -> null);
        AxFolderReplica b = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(Y, 1000, "Android")), id -> null);
        AxFolderReplica ab = a.merge(b.document), ba = b.merge(a.document);
        assertArrayEquals(ab.document.encode(), ba.document.encode());
        assertEquals(2, ab.nativeFolders().size());
        assertNotEquals(ab.nativeFolders().get(0).id, ab.nativeFolders().get(1).id);
        assertEquals(1000, ab.localFolders().stream().filter(f -> f.uuid.equals(X)).findFirst().get().filter.id);
        assertEquals(1000, ba.localFolders().stream().filter(f -> f.uuid.equals(Y)).findFirst().get().filter.id);
    }

    @Test public void nativeProjectionRetainsStableUuidAndMissingIdentityIsNotGuessed() throws Exception {
        AxFolderReplica original = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(X, 1000, "Work")), id -> null);
        assertEquals(X, original.nativeFolders().get(0).axSyncUuid);
        assertArrayEquals(original.encode(), original.captureNative(original.nativeFolders(), id -> null).encode());
        MessagesController.DialogFilter missing = folder(X, 1000, "Work").filter;
        assertNull(missing.axSyncUuid);
        try { original.captureNative(Collections.singletonList(missing), id -> null); fail(); }
        catch (JSONException expected) { }
    }

    @Test public void staleNativeSnapshotCannotResurrectDeletedUuidAndExplicitNewUuidCanReuseId() throws Exception {
        var old = Collections.singletonList(folder(X, 1000, "Old"));
        AxFolderReplica original = AxFolderReplica.fresh(1).capture(old, id -> null);
        AxFolderReplica deleted = original.capture(Collections.emptyList(), id -> null);
        AxFolderReplica stale = original.capture(Collections.singletonList(folder(X, 1000, "Offline edit")), id -> null);
        AxFolderReplica combined = stale.merge(deleted.document).capture(old, id -> null);
        assertTrue(combined.nativeFolders().isEmpty());
        assertTrue(combined.document.folder(X).has("deleted"));
        AxFolderReplica recreated = combined.capture(Collections.singletonList(folder(Y, 1000, "New")), id -> null);
        assertEquals(Y, recreated.localFolders().get(0).uuid);
        assertTrue(recreated.document.folder(X).has("deleted"));
    }

    @Test public void invalidAccountOrDuplicateLocalMappingCannotLoad() throws Exception {
        AxFolderReplica a = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(X, 1000, "X"), folder(Y, 1001, "Y")), id -> null);
        try { AxFolderReplica.decode(a.encode(), 2); fail(); } catch (JSONException expected) { }
        JSONObject state = new JSONObject(new String(a.encode(), StandardCharsets.UTF_8));
        state.getJSONObject("ids").put(Y, 1000);
        try { AxFolderReplica.decode(AxFolderDocument.canonical(state).getBytes(StandardCharsets.UTF_8), 1); fail(); }
        catch (JSONException expected) { }
    }

    @Test public void localStorePersistsPerAccountAndRejectsWrongOwner() throws Exception {
        File root = temporary.newFolder();
        AxFolderLocalStore first = new AxFolderLocalStore(root, 1), second = new AxFolderLocalStore(root, 2);
        AxFolderReplica a = first.load().capture(Collections.singletonList(folder(X, 1000, "Private")), id -> null);
        first.save(a);
        assertArrayEquals(a.encode(), first.load().encode());
        assertTrue(second.load().nativeFolders().isEmpty());
        try { second.save(a); fail(); } catch (JSONException expected) { }
    }

    @Test public void failedAtomicWriteRestoresPriorState() throws Exception {
        File root = temporary.newFolder();
        AxFolderLocalStore store = new AxFolderLocalStore(root, 1);
        AxFolderReplica original = store.load().capture(Collections.singletonList(folder(X, 1000, "Work")), id -> null);
        store.save(original);
        File file = new File(root, "1/replica.json");
        assertTrue(file.renameTo(new File(file.getPath() + ".bak")));
        Files.write(file.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(original.encode(), store.load().encode());
    }

    @Test public void freshIdentityIsDurableAndStaleWriterCannotReplaceNewerState() throws Exception {
        File root = temporary.newFolder();
        AxFolderLocalStore first = new AxFolderLocalStore(root, 1), second = new AxFolderLocalStore(root, 1);
        AxFolderReplica seed = first.load();
        assertArrayEquals(seed.encode(), second.load().encode());
        AxFolderReplica edited = seed.capture(Collections.singletonList(folder(X, 1000, "New")), id -> null);
        assertTrue(first.compareAndSave(seed, edited));
        assertFalse(second.compareAndSave(seed, seed));
        assertArrayEquals(edited.encode(), second.load().encode());
    }

    @Test public void corruptFileIsNotReplacedByEmptyReplica() throws Exception {
        File root = temporary.newFolder(); File account = new File(root, "1"); assertTrue(account.mkdir());
        File file = new File(account, "replica.json"); byte[] damaged = "{broken".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), damaged);
        try { new AxFolderLocalStore(root, 1).load(); fail(); } catch (JSONException expected) { }
        assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
    }

    @Test public void uiRenameKeepsRemoteAppearanceAndRulesInsteadOfReplayingWholeOldObject() throws Exception {
        var original = Collections.singletonList(folder(X, 1000, "Work"));
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(original, id -> null);
        var remoteFolder = folder(X, 1000, "Work"); remoteFolder.filter.color = 3; remoteFolder.filter.flags = 1;
        AxFolderReplica remote = before.capture(Collections.singletonList(remoteFolder), id -> null);
        AxFolderReplica after = before.capture(Collections.singletonList(folder(X, 1000, "Renamed")), id -> null);
        AxFolderReplica result = remote.replayUiChanges(before, after);
        var projected = result.nativeFolders().get(0);
        assertEquals("Renamed", projected.name); assertEquals(3, projected.color); assertEquals(1, projected.flags);
        assertArrayEquals(result.encode(), result.replayUiChanges(before, after).encode());
    }

    @Test public void unchangedUiDoesNotDeleteImportedFoldersOrOverwriteRemoteChanges() throws Exception {
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(X, 1000, "Work")), id -> null);
        AxFolderReplica incoming = before.capture(Arrays.asList(folder(X, 1000, "Remote rename"), folder(Y, 1001, "Imported")), id -> null);
        assertArrayEquals(incoming.encode(), incoming.replayUiChanges(before, before).encode());
    }

    @Test public void replayKeepsDeletionPermanentAndRecreatedUuidIndependent() throws Exception {
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(X, 1000, "Work")), id -> null);
        AxFolderReplica removed = before.capture(Collections.emptyList(), id -> null);
        AxFolderReplica edited = before.capture(Collections.singletonList(folder(X, 1000, "Edited in open screen")), id -> null);
        assertTrue(removed.replayUiChanges(before, edited).nativeFolders().isEmpty());
        assertTrue(edited.replayUiChanges(before, removed).nativeFolders().isEmpty());
        AxFolderReplica recreated = AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(Y, 1000, "Recreated")), id -> null);
        AxFolderReplica result = edited.replayUiChanges(before, recreated);
        assertTrue(result.document.folder(X).has("deleted")); assertEquals(Y, result.localFolders().get(0).uuid);
    }

    @Test public void newlyCreatedUiFolderKeepsItsIdAndRemapsConflictingImport() throws Exception {
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(Collections.emptyList(), id -> null);
        AxFolderReplica imported = before.merge(AxFolderReplica.fresh(1).capture(Collections.singletonList(folder(X, 1000, "Imported")), id -> null).document);
        AxFolderReplica after = before.capture(Collections.singletonList(folder(Y, 1000, "New draft saved")), id -> null);
        AxFolderReplica result = imported.replayUiChanges(before, after);
        assertEquals(2, result.localFolders().size());
        assertEquals(1000, result.localFolders().stream().filter(item -> item.uuid.equals(Y)).findFirst().get().filter.id);
        assertEquals(1001, result.localFolders().stream().filter(item -> item.uuid.equals(X)).findFirst().get().filter.id);
        assertArrayEquals(result.encode(), result.replayUiChanges(before, after).encode());
    }

    @Test public void uiReorderPreservesPreviouslyUnseenImportedFolder() throws Exception {
        String z = "10000000-0000-0000-0000-000000000003";
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "X"), folder(Y, 1001, "Y")), id -> null);
        AxFolderReplica imported = before.capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "X"), folder(z, 1002, "Remote"), folder(Y, 1001, "Y")), id -> null);
        AxFolderReplica after = before.capture(Arrays.asList(folder(null, 0, "All"), folder(Y, 1001, "Y"), folder(X, 1000, "X")), id -> null);
        AxFolderReplica result = imported.replayUiChanges(before, after);
        assertEquals(Arrays.asList(0, 1001, 1002, 1000), result.nativeOrder(Collections.singletonList(0)));
        assertEquals(3, result.nativeFolders().size());
    }

    @Test public void replayRejectsOtherAccountAndIgnoresImplicitNewServerFolder() throws Exception {
        AxFolderReplica before = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "X")), id -> null);
        AxFolderReplica after = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "X"), folder(null, 2, "Telegram")), id -> null);
        assertArrayEquals(before.encode(), before.replayUiChanges(before, after).encode());
        try { before.replayUiChanges(AxFolderReplica.fresh(2), after); fail(); } catch (JSONException expected) { }
    }

    @Test public void editorProposalKeepsBaselineImmutableAndDoesNotRevertConcurrentAppearance() throws Exception {
        AxFolderReplica baseline = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work")), id -> null);
        var draft = baseline.nativeFolders().get(0); draft.name = "Renamed";
        AxFolderReplica proposal = baseline.editedNativeFilter(draft, false, false, id -> null);
        assertEquals("Work", baseline.nativeFolders().get(0).name);
        var other = baseline.nativeFolders().get(0); other.color = 5;
        AxFolderReplica remote = baseline.editedNativeFilter(other, false, false, id -> null);
        var finalFilter = remote.replayUiChanges(baseline, proposal).nativeFolders().get(0);
        assertEquals("Renamed", finalFilter.name); assertEquals(5, finalFilter.color);
        assertEquals(Arrays.asList(0, 1000), proposal.nativeOrder(Collections.singletonList(0)));
    }

    @Test public void newEditorFolderCanBeInsertedAfterAllChatsWithoutLosingExistingOrder() throws Exception {
        AxFolderReplica baseline = AxFolderReplica.fresh(1).capture(Arrays.asList(folder(null, 0, "All"), folder(X, 1000, "Work"), folder(null, 2, "Server")), id -> null);
        var draft = folder(Y, 1001, "New").filter; draft.axSyncUuid = Y;
        AxFolderReplica proposal = baseline.editedNativeFilter(draft, true, true, id -> null);
        assertEquals(Arrays.asList(0, 1001, 1000, 2), proposal.nativeOrder(Arrays.asList(0, 2)));
        assertEquals(2, proposal.nativeFolders().size());
        try { proposal.editedNativeFilter(draft, true, false, id -> null); fail(); } catch (JSONException expected) { }
    }

    @Test public void editorCanKeepImportedChannelBeforeNativeChatMetadataIsLoaded() throws Exception {
        var original = folder(X, 1000, "Channel"); original.filter.alwaysShow.add(-99L);
        AxFolderReplica baseline = AxFolderReplica.fresh(1).capture(Collections.singletonList(original), id -> "channel");
        var draft = baseline.nativeFolders().get(0); draft.name = "Renamed channel";
        AxFolderReplica proposal = baseline.editedNativeFilter(draft, false, false, id -> null);
        assertEquals(Collections.singletonList(-99L), proposal.nativeFolders().get(0).alwaysShow);
    }
}
