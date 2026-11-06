package one.axgram.core;

import one.axgram.core.folders.AxFolderIdReservations;
import one.axgram.core.folders.AxFolderReplica;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesController;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderIdReservationsTest {
    private AxFolderReplica state(long account, int id) throws Exception {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = id; filter.name = "Folder"; filter.flags = 31; filter.color = -1;
        return AxFolderReplica.fresh(account).capture(Collections.singletonList(new AxFolderReplica.LocalFolder(
                "10000000-0000-0000-0000-000000000001", filter)), peer -> null);
    }

    @Test public void openDraftBlocksConflictingImportAndKeepsStableReservation() throws Exception {
        AxFolderIdReservations reservations = new AxFolderIdReservations(1);
        Object draft = new Object();
        assertEquals(1000, reservations.reserveDraft(draft, Collections.emptyList()));
        assertEquals(1000, reservations.reserveDraft(draft, Collections.singletonList(1000)));
        assertNull(reservations.beginImport(state(1, 1000)));
        reservations.releaseDraft(draft);
        var lease = reservations.beginImport(state(1, 1000)); assertNotNull(lease);
        reservations.finishImport(lease, state(1, 1000));
        assertEquals(1001, reservations.reserveDraft(new Object(), Collections.emptyList()));
    }

    @Test public void importReservesIdsBeforeUiSeesThemAndFailureReleasesThem() throws Exception {
        AxFolderIdReservations reservations = new AxFolderIdReservations(1);
        var lease = reservations.beginImport(state(1, 1000));
        Object draft = new Object();
        assertEquals(1001, reservations.reserveDraft(draft, Collections.emptyList()));
        reservations.finishImport(lease, null);
        assertEquals(1000, reservations.reserveDraft(new Object(), Collections.emptyList()));
    }

    @Test public void savedDraftRemainsUnavailableButNoLongerBlocksItsOwnImport() throws Exception {
        AxFolderIdReservations reservations = new AxFolderIdReservations(1);
        Object draft = new Object(); reservations.reserveDraft(draft, Collections.emptyList());
        reservations.confirmSaved(draft, 1000);
        var lease = reservations.beginImport(state(1, 1000)); assertNotNull(lease);
        reservations.finishImport(lease, state(1, 1000));
        assertEquals(1001, reservations.reserveDraft(new Object(), Collections.emptyList()));
    }

    @Test public void sqlUiCommitReleasesDraftAndDeletedButStillVisibleIdCannotBeReused() throws Exception {
        AxFolderIdReservations reservations = new AxFolderIdReservations(1);
        Object draft = new Object(); reservations.reserveDraft(draft, Collections.emptyList());
        reservations.confirmUiCommit(state(1, 1000));
        var lease = reservations.beginImport(state(1, 1000)); assertNotNull(lease);
        reservations.finishImport(lease, AxFolderReplica.fresh(1));
        Object next = new Object();
        assertEquals(1001, reservations.reserveDraft(next, Collections.singletonList(1000)));
        reservations.releaseDraft(next);
        assertEquals(1000, reservations.reserveDraft(new Object(), Collections.emptyList()));
    }

    @Test public void reservationAccountsAndLeaseOwnersCannotBeMixed() throws Exception {
        AxFolderIdReservations first = new AxFolderIdReservations(1), second = new AxFolderIdReservations(2);
        try { first.observeStored(state(2, 1000)); fail(); } catch (IllegalArgumentException expected) { }
        var lease = first.beginImport(state(1, 1000));
        try { second.finishImport(lease, null); fail(); } catch (IllegalArgumentException expected) { }
        first.finishImport(lease, null);
        try { first.finishImport(lease, null); fail(); } catch (IllegalArgumentException expected) { }
    }
}
