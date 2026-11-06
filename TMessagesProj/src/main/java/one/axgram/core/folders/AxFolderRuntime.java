package one.axgram.core.folders;

import android.os.Looper;
import android.os.SystemClock;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AxFolderRuntime implements AutoCloseable {
    private static final java.util.Map<AccountInstance, AxFolderRuntime> instances = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private final AccountInstance instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AxGramFolderSync"); thread.setDaemon(true); return thread;
    });
    private AxFolderUiCoordinator coordinator;
    private AxFolderExchangeLoop loop;
    private byte[] startingKey;
    private boolean closed, foreground;

    private AxFolderRuntime(AccountInstance instance) { this.instance = instance; }

    public static AxFolderRuntime start(AccountInstance instance, URL endpoint, AxFolderAuthClient.Credential credential,
            Consumer<AxFolderRuntime> ready, BiConsumer<AxFolderExchangeLoop.State, Exception> status) {
        checkUi();
        if (instance == null || credential == null || ready == null || status == null
                || credential.account != instance.getUserConfig().getClientUserId()) throw new IllegalArgumentException("Current folder login required");
        byte[] key = credential.encryptionKey();
        if (instances.containsKey(instance) || instance.getMessagesController().getAxFolderUiCoordinator() != null) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("Folder runtime already starting or closing");
        }
        AxFolderRuntime runtime = new AxFolderRuntime(instance);
        runtime.startingKey = key;
        instances.put(instance, runtime);
        try {
            AxFolderTransport transport = new AxFolderTransport(new URL(endpoint.toExternalForm() + "/v1/snapshot"), credential.token());
            String workspace = credential.workspace; long account = credential.account;
            AxFolderUiCoordinator.start(instance, coordinator -> {
                if (runtime.closed) { Arrays.fill(key, (byte) 0); coordinator.closeWhenIdle(); return; }
                try {
                    runtime.coordinator = coordinator;
                    AxFolderSync sync = new AxFolderSync(coordinator.stateStore(), transport, workspace, account, key);
                    runtime.loop = new AxFolderExchangeLoop(new AxFolderExchangeLoop.Environment() {
                        public long now() { return SystemClock.elapsedRealtime(); }
                        public void post(Runnable task, long delay) { AndroidUtilities.runOnUIThread(task, delay); }
                        public void cancel(Runnable task) { AndroidUtilities.cancelRunOnUIThread(task); }
                        public void work(Runnable task) { runtime.worker.execute(task); }
                        public boolean sessionActive() { return coordinator.stateStore().isSessionActive(); }
                        public boolean canExchange() { return coordinator.canExchange(); }
                        public void refresh() { coordinator.requestRefresh(); }
                        public void status(AxFolderExchangeLoop.State state, Exception error) { status.accept(state, error); }
                    }, new AxFolderExchangeLoop.Exchange() {
                        public boolean run() throws Exception { return sync.run().pendingLocalChanges; }
                        public void close() { sync.close(); }
                    });
                    runtime.loop.setForeground(runtime.foreground); ready.accept(runtime);
                } catch (Exception error) { runtime.close(); status.accept(AxFolderExchangeLoop.State.RETRYING, error); }
                finally { Arrays.fill(key, (byte) 0); }
            }, error -> {
                Arrays.fill(key, (byte) 0);
                if (!runtime.closed) { runtime.close(); status.accept(AxFolderExchangeLoop.State.RETRYING, error); }
            }, () -> { if (!runtime.closed && runtime.loop != null) runtime.loop.wake(); });
        } catch (Exception error) {
            Arrays.fill(key, (byte) 0); runtime.close(); status.accept(AxFolderExchangeLoop.State.RETRYING, error);
        }
        return runtime;
    }

    private static void checkUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Folder UI queue required");
    }

    public static void stopForLogout(AccountInstance instance) {
        AxFolderRuntime runtime = instances.get(instance);
        if (runtime != null) AndroidUtilities.runOnUIThread(runtime::close);
    }

    public void setForeground(boolean foreground) { checkUi(); this.foreground = foreground; if (!closed && loop != null) loop.setForeground(foreground); }
    public boolean isClosed() { checkUi(); return closed; }
    public void wake() { checkUi(); if (!closed && loop != null) loop.wake(); }
    public void retry() { checkUi(); if (!closed && loop != null) { coordinator.retry(); loop.retry(); } }

    @Override public void close() {
        checkUi();
        if (closed) return;
        closed = true;
        if (startingKey != null) { Arrays.fill(startingKey, (byte) 0); startingKey = null; }
        if (instances.get(instance) == this) instances.remove(instance);
        if (loop != null) loop.close();
        if (coordinator != null) coordinator.closeWhenIdle();
        worker.shutdown();
    }
}
