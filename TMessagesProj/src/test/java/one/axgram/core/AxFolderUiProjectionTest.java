package one.axgram.core;

import one.axgram.core.folders.AxFolderReplica;
import one.axgram.core.folders.AxFolderUiProjection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderUiProjectionTest {
    private static final String X = "10000000-0000-0000-0000-000000000001";
    private static final String Y = "10000000-0000-0000-0000-000000000002";

    private MessagesController.DialogFilter folder(int id, String uuid, String title) {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = id; filter.axSyncUuid = uuid; filter.name = title; filter.flags = 31; filter.color = -1;
        return filter;
    }

    @Test public void updatesExistingObjectsAndKeepsServerAndSelectedFolderIdentity() throws Exception {
        var all = folder(0, null, "All"); var local = folder(1000, X, "Work"); local.unreadCount = 42;
        var existing = Arrays.asList(all, local);
        AxFolderReplica before = AxFolderReplica.fresh(1).captureNative(existing, id -> null);
        var renamed = folder(1000, X, "Renamed"); renamed.color = 2;
        AxFolderReplica after = before.captureNative(Arrays.asList(all, renamed, folder(1001, Y, "Imported")), id -> null);
        var result = new AxFolderUiProjection(after, existing).applyIfUnchanged(existing);
        assertEquals(3, result.size()); assertSame(all, result.get(0)); assertSame(local, result.get(1));
        assertEquals("Renamed", local.name); assertEquals(2, local.color); assertEquals(42, local.unreadCount);
        assertEquals(-1, result.get(2).unreadCount); assertEquals(2, result.get(2).order);
    }

    @Test public void changedUiRejectsProjectionWithoutPartiallyChangingAnything() throws Exception {
        var all = folder(0, null, "All"); var local = folder(1000, X, "Work");
        var existing = Arrays.asList(all, local);
        AxFolderReplica before = AxFolderReplica.fresh(1).captureNative(existing, id -> null);
        AxFolderReplica after = before.captureNative(Arrays.asList(all, folder(1000, X, "Remote")), id -> null);
        AxFolderUiProjection projection = new AxFolderUiProjection(after, existing);
        local.name = "New UI edit";
        assertNull(projection.applyIfUnchanged(existing)); assertEquals("New UI edit", local.name); assertEquals("All", all.name);
        local.name = "Work"; local.pinnedDialogs.put(77, 0);
        assertNull(projection.applyIfUnchanged(existing));
        local.pinnedDialogs.clear(); local.order = 10;
        assertNull(projection.applyIfUnchanged(existing));
    }

    @Test public void deletionRemovesOnlyLocalFolderAndCounterUpdatesDoNotInvalidateProjection() throws Exception {
        var all = folder(0, null, "All"); var local = folder(1000, X, "Work");
        var existing = Arrays.asList(all, local);
        AxFolderReplica before = AxFolderReplica.fresh(1).captureNative(existing, id -> null);
        AxFolderReplica after = before.captureNative(Collections.singletonList(all), id -> null);
        AxFolderUiProjection projection = new AxFolderUiProjection(after, existing);
        local.unreadCount = 12; local.pendingUnreadCount = 13;
        var result = projection.applyIfUnchanged(existing);
        assertEquals(1, result.size()); assertSame(all, result.get(0));
    }

    @Test public void sharedProjectionPreservesExistingLocalSecretChatMembership() throws Exception {
        var local = folder(1000, X, "Work");
        AxFolderReplica state = AxFolderReplica.fresh(1).captureNative(Collections.singletonList(local), id -> null);
        local.alwaysShow.add(DialogObject.makeEncryptedDialogId(77));
        var result = new AxFolderUiProjection(state, Collections.singletonList(local)).applyIfUnchanged(Collections.singletonList(local));
        assertSame(local, result.get(0));
        assertTrue(local.alwaysShow.contains(DialogObject.makeEncryptedDialogId(77)));
        assertEquals(1, local.alwaysShow.size());
    }
}
