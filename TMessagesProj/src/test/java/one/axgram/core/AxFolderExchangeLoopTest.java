package one.axgram.core;

import one.axgram.core.folders.AxFolderExchangeLoop;
import one.axgram.core.folders.AxFolderTransport;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class AxFolderExchangeLoopTest {
    private static final class Task {
        final Runnable run;
        final long at;
        Task(Runnable run, long at) { this.run = run; this.at = at; }
    }
    private static final class Fixture implements AxFolderExchangeLoop.Environment, AxFolderExchangeLoop.Exchange {
        long now;
        boolean active = true, allowed = true, pending;
        int calls, refreshes, closes;
        Exception failure;
        final ArrayList<Task> tasks = new ArrayList<>();
        final ArrayList<Runnable> work = new ArrayList<>();
        final ArrayList<AxFolderExchangeLoop.State> states = new ArrayList<>();
        final AxFolderExchangeLoop loop = new AxFolderExchangeLoop(this, this);
        public long now() { return now; }
        public void post(Runnable task, long delay) { tasks.add(new Task(task, now + delay)); }
        public void cancel(Runnable task) { tasks.removeIf(item -> item.run == task); }
        public void work(Runnable task) { work.add(task); }
        public boolean sessionActive() { return active; }
        public boolean canExchange() { return allowed; }
        public void refresh() { refreshes++; }
        public void status(AxFolderExchangeLoop.State state, Exception error) { states.add(state); }
        public boolean run() throws Exception { calls++; if (failure != null) throw failure; return pending; }
        public void close() { closes++; }
        void next() {
            assertFalse(tasks.isEmpty()); tasks.sort((a, b) -> Long.compare(a.at, b.at));
            Task task = tasks.remove(0); now = task.at; task.run.run();
        }
        void finish() { assertFalse(work.isEmpty()); work.remove(0).run(); next(); }
        long delay() { return tasks.stream().mapToLong(task -> task.at - now).min().orElse(-1); }
        AxFolderExchangeLoop.State state() { return states.get(states.size() - 1); }
    }

    @Test public void burstsAreCoalescedAndNeverStartParallelRequests() {
        Fixture f = new Fixture(); f.loop.setForeground(true);
        for (int i = 0; i < 20; i++) f.loop.wake();
        assertEquals(1, f.tasks.size()); f.next(); assertEquals(1, f.work.size());
        for (int i = 0; i < 20; i++) f.loop.wake();
        assertTrue(f.tasks.isEmpty()); f.finish();
        assertEquals(1, f.calls); assertEquals(1, f.refreshes); assertEquals(200, f.delay());
        f.next(); assertEquals(1, f.work.size()); f.finish(); assertEquals(30000, f.delay());
    }

    @Test public void pendingSqlWritesDeferNetworkUntilTheQueueIsReady() {
        Fixture f = new Fixture(); f.allowed = false; f.loop.setForeground(true); f.next();
        assertTrue(f.work.isEmpty()); assertEquals(1000, f.delay());
        f.allowed = true; f.loop.wake(); f.next(); f.finish(); assertEquals(1, f.calls);
    }

    @Test public void importIsRefreshedEvenWhenUploadFailsAndWakeRespectsBackoff() {
        Fixture f = new Fixture(); f.failure = new IOException("offline"); f.loop.setForeground(true);
        f.next(); f.finish(); assertEquals(1, f.refreshes); assertEquals(1000, f.delay());
        f.loop.wake(); assertEquals(1000, f.delay());
        f.next(); f.finish(); assertEquals(2000, f.delay());
        f.failure = null; f.loop.retry(); assertEquals(200, f.delay());
        f.next(); f.finish(); assertEquals(AxFolderExchangeLoop.State.WAITING, f.state()); assertEquals(30000, f.delay());
    }

    @Test public void retryBackoffIsBoundedAtOneMinute() {
        Fixture f = new Fixture(); f.failure = new IOException("offline"); f.loop.setForeground(true);
        for (int i = 0; i < 12; i++) { f.next(); f.finish(); }
        assertEquals(60000, f.delay());
    }

    @Test public void revokedLoginStopsAutomaticAndManualRetries() throws Exception {
        var constructor = AxFolderTransport.HttpFailure.class.getDeclaredConstructor(int.class); constructor.setAccessible(true);
        for (int code : new int[] {401, 403}) {
            Fixture f = new Fixture(); f.failure = constructor.newInstance(code); f.loop.setForeground(true); f.next(); f.finish();
            assertEquals(AxFolderExchangeLoop.State.LOGIN_REQUIRED, f.state()); assertEquals(1, f.refreshes);
            f.loop.wake(); f.loop.retry(); f.loop.setForeground(false); f.loop.setForeground(true);
            assertTrue(f.tasks.isEmpty()); assertTrue(f.work.isEmpty());
        }
    }

    @Test public void backgroundStopsPollingAndCompletionDoesNotRestartIt() {
        Fixture f = new Fixture(); f.loop.setForeground(true); f.next(); f.loop.setForeground(false); f.finish();
        assertEquals(1, f.refreshes); assertTrue(f.tasks.isEmpty()); assertEquals(AxFolderExchangeLoop.State.PAUSED, f.state());
        f.loop.wake(); assertTrue(f.tasks.isEmpty());
        f.loop.setForeground(true); assertEquals(200, f.delay());
    }

    @Test public void closeIgnoresLateResultsAndClosesKeysOnSerialWorker() {
        Fixture f = new Fixture(); f.loop.setForeground(true); f.next(); f.loop.close(); f.loop.close();
        assertEquals(2, f.work.size()); assertEquals(0, f.closes);
        f.work.remove(0).run(); f.next();
        assertEquals(0, f.refreshes); assertTrue(f.tasks.isEmpty()); assertEquals(AxFolderExchangeLoop.State.CLOSED, f.state());
        f.work.remove(0).run(); assertEquals(1, f.closes);
    }

    @Test public void accountExitBeforeOrDuringRequestPreventsRefreshAndFurtherExchange() {
        Fixture f = new Fixture(); f.loop.setForeground(true); f.active = false; f.next();
        assertEquals(AxFolderExchangeLoop.State.CLOSED, f.state()); assertEquals(0, f.calls);
        Fixture g = new Fixture(); g.loop.setForeground(true); g.next(); g.active = false; g.finish();
        assertEquals(0, g.refreshes); assertEquals(AxFolderExchangeLoop.State.CLOSED, g.state()); assertTrue(g.tasks.isEmpty());
    }

    @Test public void editsDiscoveredAtEndOfExchangeGetAnImmediateFollowup() {
        Fixture f = new Fixture(); f.pending = true; f.loop.setForeground(true); f.next(); f.finish();
        assertEquals(200, f.delay());
    }
}
