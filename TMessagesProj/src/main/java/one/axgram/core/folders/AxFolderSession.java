package one.axgram.core.folders;

import android.os.Looper;
import android.os.SystemClock;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AxFolderSession {
    public interface DevicesCallback { void complete(AxFolderAuthClient.Devices devices, boolean loginRequired); }
    public enum State { OFF, RESTORING, SIGNING_IN, STARTING, SYNCING, READY, RETRYING, LOGIN_REQUIRED, ERROR, CONFIRMATION_EXPIRED }
    public static final String ENDPOINT = "https://mistyk-games.ru/axgram-sync";
    private static final Map<AccountInstance, AxFolderSession> sessions = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ScheduledExecutorService loginWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "AxGramFolderLogin"); thread.setDaemon(true); return thread;
    });
    private final AccountInstance instance;
    private final long account, sessionGeneration;
    private final URL endpoint;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private volatile long generation;
    private volatile boolean closed, logoutRequested;
    private boolean restored, loginRequired;
    private State state = State.OFF;
    private AxFolderRuntime runtime;
    private AxFolderCredentialStore storage;
    private AxFolderTelegramConsent consent;
    private volatile String nativeCallback;

    private AxFolderSession(AccountInstance instance) {
        this.instance = instance; account = instance.getUserConfig().getClientUserId();
        sessionGeneration = instance.getMessagesController().getAxFolderSessionGeneration();
        if (account <= 0) throw new IllegalArgumentException("Account required");
        try { endpoint = new URL(ENDPOINT); } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static AxFolderSession get(AccountInstance instance) {
        checkUi();
        AxFolderSession current = sessions.get(instance);
        if (current != null && !current.alive(current.generation)) { current.stop(false); current = null; }
        if (current == null) { current = new AxFolderSession(instance); sessions.put(instance, current); }
        return current;
    }

    public static void foregroundChanged(boolean foreground) {
        checkUi();
        if (foreground) {
            for (int index : new ArrayList<>(SharedConfig.activeAccounts)) {
                if (UserConfig.getInstance(index).isClientActivated()) get(AccountInstance.getInstance(index)).restoreWhenReady();
            }
        }
        for (AxFolderSession current : new ArrayList<>(sessions.values())) {
            if (current.runtime != null) current.runtime.setForeground(foreground);
        }
    }

    public static void foldersLoaded(AccountInstance instance) {
        checkUi();
        if (!ApplicationLoader.mainInterfacePaused && instance.getUserConfig().isClientActivated()) get(instance).restoreWhenReady();
    }

    public static void stopForLogout(AccountInstance instance) {
        AxFolderSession current = sessions.get(instance);
        if (current != null) {
            current.logoutRequested = true;
            loginWorker.execute(() -> { try { current.storage().clear(); } catch (Exception ignored) { } });
            AndroidUtilities.runOnUIThread(() -> current.stop(false));
        }
    }

    private static void checkUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Folder UI queue required");
    }

    private boolean alive(long started) {
        return !closed && !logoutRequested && generation == started && instance.getUserConfig().getClientUserId() == account
                && instance.getMessagesController().getAxFolderSessionGeneration() == sessionGeneration;
    }

    private AxFolderCredentialStore storage() throws IOException {
        if (storage == null) storage = AxFolderCredentialStore.android(ApplicationLoader.applicationContext, endpoint, account);
        return storage;
    }

    public State state() { checkUi(); return state; }
    public void subscribe(Runnable listener) { checkUi(); if (!listeners.contains(listener)) listeners.add(listener); }
    public void unsubscribe(Runnable listener) { checkUi(); listeners.remove(listener); }
    private void state(State next) { if (next == State.LOGIN_REQUIRED) loginRequired = true; state = next; for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }

    public void restoreWhenReady() {
        checkUi();
        if (closed || restored || !instance.getMessagesController().dialogFiltersLoaded) return;
        restored = true; long started = ++generation; state(State.RESTORING);
        loginWorker.execute(() -> {
            try {
                AxFolderAuthClient.Credential credential = storage().load();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!alive(started)) { if (credential != null) credential.close(); return; }
                    if (credential == null) { loginRequired = false; state(State.OFF); } else startRuntime(credential, started);
                });
            } catch (Exception error) { failure(started); }
        });
    }

    public void connect() {
        checkUi();
        if (closed || state == State.SIGNING_IN || state == State.RESTORING || state == State.STARTING) return;
        if (runtime != null) { runtime.close(); runtime = null; }
        closeConsent(); nativeCallback = null;
        restored = true; long started = ++generation; state(State.SIGNING_IN);
        loginWorker.execute(() -> {
            try {
                if (!alive(started)) return;
                AxFolderAuthClient client = new AxFolderAuthClient(endpoint);
                AxFolderAuthClient.Flow flow = client.startNative(account, "AxGram Android");
                long deadline = SystemClock.elapsedRealtime() + 300000;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!alive(started)) return;
                    consent = new AxFolderTelegramConsent(instance, flow, () -> alive(started), accepted -> {
                        consent = null;
                        nativeCallback = accepted;
                        state(State.STARTING);
                    }, () -> failure(started), () -> cancelConfirmation(started), () -> confirmationExpired(started));
                    consent.start();
                });
                schedulePoll(client, flow, started, deadline);
            } catch (Exception error) { failure(started); }
        });
    }

    private void schedulePoll(AxFolderAuthClient client, AxFolderAuthClient.Flow flow, long started, long deadline) {
        loginWorker.schedule(() -> {
            if (!alive(started)) return;
            AxFolderAuthClient.Credential credential = null;
            try {
                credential = client.poll(flow);
                if (credential == null) {
                    String callback = nativeCallback;
                    if (callback != null && !callback.isEmpty()) {
                        try { client.completeNative(flow, callback); }
                        catch (AxFolderNativeAuth.Denied denied) { cancelConfirmation(started); return; }
                        catch (AxFolderNativeAuth.Invalid | org.json.JSONException invalid) { failure(started); return; }
                        catch (AxFolderAuthClient.LoginRejected rejected) { failure(started); return; }
                        catch (IOException unavailable) { }
                    }
                    schedulePoll(client, flow, started, deadline); return;
                }
                if (!alive(started)) return;
                nativeCallback = null;
                storage().save(credential);
                try { client.acknowledge(flow); } catch (IOException ignored) { }
                AxFolderAuthClient.Credential saved = credential;
                credential = null;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!alive(started)) { saved.close(); return; }
                    loginRequired = false;
                    startRuntime(saved, started);
                });
            } catch (Exception error) {
                if (!(error instanceof AxFolderAuthClient.LoginRejected) && SystemClock.elapsedRealtime() < deadline) schedulePoll(client, flow, started, deadline);
                else failure(started);
            } finally { if (credential != null) credential.close(); }
        }, flow.pollIntervalSeconds, TimeUnit.SECONDS);
    }

    private void startRuntime(AxFolderAuthClient.Credential credential, long started) {
        state(State.STARTING);
        startRuntimeWhenIdle(credential, started, SystemClock.elapsedRealtime() + 15000);
    }

    private void startRuntimeWhenIdle(AxFolderAuthClient.Credential credential, long started, long deadline) {
        if (!alive(started)) { credential.close(); return; }
        if (instance.getMessagesController().getAxFolderUiCoordinator() != null) {
            if (SystemClock.elapsedRealtime() >= deadline) { credential.close(); failure(started); return; }
            AndroidUtilities.runOnUIThread(() -> startRuntimeWhenIdle(credential, started, deadline), 100);
            return;
        }
        try {
            runtime = AxFolderRuntime.start(instance, endpoint, credential, ready -> {
                if (!alive(started)) { ready.close(); return; }
                if (!loginRequired) state(State.READY);
            }, (status, error) -> {
                if (!alive(started)) return;
                if (loginRequired && status != AxFolderExchangeLoop.State.LOGIN_REQUIRED) return;
                switch (status) {
                    case EXCHANGING: state(State.SYNCING); break;
                    case WAITING: case PAUSED: state(State.READY); break;
                    case RETRYING: state(State.RETRYING); break;
                    case LOGIN_REQUIRED: state(State.LOGIN_REQUIRED); break;
                    case CLOSED: break;
                }
            });
            if (runtime.isClosed()) { runtime = null; state(State.ERROR); }
            else runtime.setForeground(!ApplicationLoader.mainInterfacePaused);
        } catch (Exception error) { state(State.ERROR); }
        finally { credential.close(); }
    }

    private void cancelConfirmation(long started) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!alive(started)) return;
            generation++; closeConsent(); state(loginRequired ? State.LOGIN_REQUIRED : State.OFF);
        });
    }

    private void closeConsent() { nativeCallback = null; if (consent != null) { consent.close(); consent = null; } }

    private void failure(long started) { AndroidUtilities.runOnUIThread(() -> {
        if (alive(started)) { generation++; closeConsent(); state(State.ERROR); }
    }); }

    private void confirmationExpired(long started) { AndroidUtilities.runOnUIThread(() -> {
        if (alive(started)) { generation++; closeConsent(); state(State.CONFIRMATION_EXPIRED); }
    }); }

    public void retry() {
        checkUi();
        if (closed) return;
        if (state == State.CONFIRMATION_EXPIRED) { connect(); return; }
        if (loginRequired && state == State.ERROR) { connect(); return; }
        if (runtime != null && runtime.isClosed()) runtime = null;
        if (runtime != null && state != State.LOGIN_REQUIRED) runtime.retry();
        else if (state != State.SIGNING_IN && state != State.LOGIN_REQUIRED) {
            AxFolderUiCoordinator coordinator = instance.getMessagesController().getAxFolderUiCoordinator();
            if (coordinator != null) coordinator.retry();
            restored = false; restoreWhenReady();
        }
    }

    public void disconnect() {
        checkUi();
        if (closed) return;
        long started = ++generation;
        loginRequired = false;
        closeConsent();
        if (runtime != null) { runtime.close(); runtime = null; }
        restored = true; state(State.OFF);
        loginWorker.execute(() -> { try { storage().clear(); } catch (Exception error) { failure(started); } });
    }

    public void devices(DevicesCallback callback) {
        checkUi();
        long started = generation;
        loginWorker.execute(() -> {
            AxFolderAuthClient.Devices listed = null;
            boolean rejected = false;
            try (AxFolderAuthClient.Credential credential = storage().load()) {
                if (!alive(started)) return;
                if (credential == null) rejected = true;
                else listed = new AxFolderAuthClient(endpoint).devices(credential);
            } catch (AxFolderAuthClient.LoginRejected error) { rejected = error.status == 401; }
            catch (Exception error) { }
            AxFolderAuthClient.Devices result = listed;
            boolean loginRequired = rejected;
            AndroidUtilities.runOnUIThread(() -> {
                if (!alive(started)) return;
                if (loginRequired) {
                    generation++;
                    if (runtime != null) { runtime.close(); runtime = null; }
                    state(State.LOGIN_REQUIRED);
                }
                callback.complete(result, loginRequired);
            });
        });
    }

    public void revokeDevice(String device, java.util.function.Consumer<Boolean> callback) {
        checkUi();
        long started = generation;
        loginWorker.execute(() -> {
            boolean success = false, self = false;
            try (AxFolderAuthClient.Credential credential = storage().load()) {
                if (!alive(started)) return;
                if (credential != null) {
                    self = credential.device.equals(device);
                    new AxFolderAuthClient(endpoint).revokeDevice(credential, device);
                    success = true;
                }
            } catch (Exception error) { }
            boolean revoked = success, current = self;
            AndroidUtilities.runOnUIThread(() -> {
                if (!alive(started)) return;
                if (revoked && current) disconnect();
                callback.accept(revoked);
            });
        });
    }

    private void stop(boolean erase) {
        checkUi();
        if (closed) return;
        generation++; closed = true;
        closeConsent();
        if (runtime != null) { runtime.close(); runtime = null; }
        if (sessions.get(instance) == this) sessions.remove(instance);
        listeners.clear();
        if (erase) loginWorker.execute(() -> { try { storage().clear(); } catch (Exception ignored) { } });
    }
}
