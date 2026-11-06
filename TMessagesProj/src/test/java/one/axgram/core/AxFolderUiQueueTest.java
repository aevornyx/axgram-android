package one.axgram.core;

import one.axgram.core.folders.AxFolderReplica;
import one.axgram.core.folders.AxFolderUiQueue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderUiQueueTest {
    private static final class Pending {
        final String id;
        final Consumer<AxFolderReplica> saved;
        final Consumer<Exception> failed;
        Pending(String id, Consumer<AxFolderReplica> saved, Consumer<Exception> failed) { this.id = id; this.saved = saved; this.failed = failed; }
    }
    private static final class Fixture {
        final ArrayList<Pending> writes = new ArrayList<>();
        final ArrayList<AxFolderReplica> settled = new ArrayList<>();
        final ArrayList<Boolean> blocked = new ArrayList<>();
        final ArrayList<Exception> errors = new ArrayList<>();
        final AxFolderUiQueue queue = new AxFolderUiQueue((id, before, after, saved, failed) -> writes.add(new Pending(id, saved, failed)), blocked::add, settled::add, errors::add);
    }

    @Test public void onlyOneWriteRunsAndOnlyLatestSettledStateIsProjected() throws Exception {
        Fixture fixture = new Fixture(); AxFolderReplica state = AxFolderReplica.fresh(1);
        ArrayList<String> callbacks = new ArrayList<>();
        fixture.queue.enqueue(state, state, saved -> callbacks.add("first"), error -> fail());
        fixture.queue.enqueue(state, state, saved -> callbacks.add("second"), error -> fail());
        assertEquals(1, fixture.writes.size()); assertTrue(fixture.queue.hasPending());
        fixture.writes.get(0).saved.accept(state);
        assertEquals(2, fixture.writes.size()); assertTrue(fixture.settled.isEmpty());
        fixture.writes.get(1).saved.accept(state);
        assertEquals(java.util.Arrays.asList("first", "second"), callbacks);
        assertEquals(1, fixture.settled.size()); assertFalse(fixture.queue.hasPending());
        assertEquals(Boolean.FALSE, fixture.blocked.get(fixture.blocked.size() - 1));
    }

    @Test public void failureKeepsLaterChangesBlockedAndRetryKeepsOriginalOperationId() throws Exception {
        Fixture fixture = new Fixture(); AxFolderReplica state = AxFolderReplica.fresh(1);
        ArrayList<Exception> failures = new ArrayList<>();
        String first = fixture.queue.enqueue(state, state, saved -> { }, failures::add);
        fixture.queue.enqueue(state, state, saved -> { }, failures::add);
        fixture.writes.get(0).failed.accept(new IOException("Disk unavailable"));
        assertTrue(fixture.queue.isFailed()); assertEquals(1, fixture.writes.size()); assertEquals(2, failures.size());
        fixture.queue.retry(); assertEquals(first, fixture.writes.get(1).id);
        fixture.writes.get(1).saved.accept(state);
        assertEquals(3, fixture.writes.size()); assertTrue(fixture.settled.isEmpty());
        fixture.writes.get(0).failed.accept(new IOException("Old callback"));
        assertFalse(fixture.queue.isFailed());
        fixture.writes.get(2).saved.accept(state);
        assertFalse(fixture.queue.hasPending()); assertEquals(1, fixture.settled.size());
    }

    @Test public void logoutCloseIgnoresLateCallbacksAndDoesNotStartPendingWrites() throws Exception {
        Fixture fixture = new Fixture(); AxFolderReplica state = AxFolderReplica.fresh(1);
        fixture.queue.enqueue(state, state, saved -> fail(), error -> fail());
        fixture.queue.enqueue(state, state, saved -> fail(), error -> fail());
        fixture.queue.close(); fixture.writes.get(0).saved.accept(state);
        assertTrue(fixture.settled.isEmpty()); assertEquals(1, fixture.writes.size());
        try { fixture.queue.enqueue(state, state, saved -> { }, error -> { }); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void wrongAccountResultCannotBePresentedAsSaved() throws Exception {
        Fixture fixture = new Fixture(); AxFolderReplica state = AxFolderReplica.fresh(1);
        fixture.queue.enqueue(state, state, saved -> fail(), error -> { });
        fixture.writes.get(0).saved.accept(AxFolderReplica.fresh(2));
        assertTrue(fixture.queue.isFailed()); assertTrue(fixture.settled.isEmpty()); assertEquals(1, fixture.errors.size());
    }

    @Test public void failedUiCallbackDoesNotPreventNextQueuedWrite() throws Exception {
        Fixture fixture = new Fixture(); AxFolderReplica state = AxFolderReplica.fresh(1);
        fixture.queue.enqueue(state, state, saved -> { throw new IllegalStateException("Closed editor"); }, error -> { });
        fixture.queue.enqueue(state, state, saved -> { }, error -> { });
        try { fixture.writes.get(0).saved.accept(state); fail(); } catch (IllegalStateException expected) { }
        assertEquals(2, fixture.writes.size()); fixture.writes.get(1).saved.accept(state);
        assertFalse(fixture.queue.hasPending());
    }
}
