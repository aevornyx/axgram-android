package one.axgram.core.folders;

import android.os.Looper;
import org.json.JSONException;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Consumer;

public final class AxFolderUiCoordinator implements AutoCloseable {
    public final long account;
    private final AccountInstance instance;
    private final MessagesController controller;
    private final AxFolderAccountStore store;
    private final AxFolderUiQueue queue;
    private final Consumer<Exception> onError;
    private final Runnable onLocalChanged;
    private AxFolderReplica observed;
    private byte[] expectedUi;
    private boolean closed, reading, refreshScheduled, closeAfterWrites;
    private boolean validUi = true;
    private long generation;

    private AxFolderUiCoordinator(AccountInstance instance, AxFolderAccountStore store, AxFolderReplica initial,
            Consumer<Exception> onError, Runnable onLocalChanged) throws JSONException {
        this.instance = instance; controller = instance.getMessagesController(); this.store = store;
        account = initial.account; observed = initial; this.onError = onError; this.onLocalChanged = onLocalChanged;
        expectedUi = AxFolderUiProjection.signature(controller.dialogFilters);
        queue = new AxFolderUiQueue(store::enqueueUiChanges, blocked -> store.setNetworkAllowed(!blocked && validUi), this::settled, this::report);
    }

    public static void start(AccountInstance instance, Consumer<AxFolderUiCoordinator> ready, Consumer<Exception> failed, Runnable changed) {
        checkUi();
        if (instance == null || ready == null || failed == null || changed == null) throw new IllegalArgumentException("Folder callbacks required");
        AxFolderUiCoordinator existing = instance.getMessagesController().getAxFolderUiCoordinator();
        if (existing != null && existing.active()) { ready.accept(existing); return; }
        try {
            AxFolderAccountStore store = new AxFolderAccountStore(instance, instance.getUserConfig().getClientUserId());
            startAttempt(instance, store, ready, failed, changed, 0);
        } catch (Exception error) { failed.accept(error); }
    }

    private static void startAttempt(AccountInstance instance, AxFolderAccountStore store, Consumer<AxFolderUiCoordinator> ready,
            Consumer<Exception> failed, Runnable changed, int attempt) {
        try {
            MessagesController controller = instance.getMessagesController();
            byte[] before = AxFolderUiProjection.signature(controller.dialogFilters);
            store.readUiState(initial -> {
                try {
                    AxFolderUiCoordinator attached = controller.getAxFolderUiCoordinator();
                    if (attached != null && attached.active()) { store.close(); ready.accept(attached); return; }
                    if (attached != null) attached.close();
                    boolean unstable = controller.frozenDialogFilters != null || !Arrays.equals(before, AxFolderUiProjection.signature(controller.dialogFilters));
                    if (!unstable) {
                        AxFolderFilterCodec.PeerTypes types = initial.peerTypes(dialog -> {
                            var chat = controller.getChat(-dialog); return chat == null ? null : ChatObject.isChannel(chat) ? "channel" : "chat";
                        });
                        AxFolderUiIdentity.restoreMissing(initial, controller.dialogFilters, types);
                        unstable = !Arrays.equals(initial.encode(), initial.captureNative(controller.dialogFilters, types).encode());
                    }
                    if (unstable) {
                        if (attempt < 3) AndroidUtilities.runOnUIThread(() -> startAttempt(instance, store, ready, failed, changed, attempt + 1), 150);
                        else { store.close(); failed.accept(new IOException("Folder list is still changing")); }
                        return;
                    }
                    AxFolderUiCoordinator coordinator = new AxFolderUiCoordinator(instance, store, initial, failed, changed);
                    controller.setAxFolderUiCoordinator(coordinator); ready.accept(coordinator);
                } catch (Exception error) { store.close(); failed.accept(error); }
            }, error -> { store.close(); failed.accept(error); });
        } catch (Exception error) { store.close(); failed.accept(error); }
    }

    private static void checkUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Folder UI queue required");
    }

    private boolean active() { return !closed && store.isSessionActive(); }
    public AxFolderAccountStore stateStore() { return store; }
    public boolean hasPendingChanges() { checkUi(); return queue.hasPending(); }
    public boolean canExchange() { checkUi(); return active() && validUi && !queue.hasPending() && store.isNetworkAllowed(); }

    private AxFolderFilterCodec.PeerTypes types() throws JSONException {
        return observed.peerTypes(dialog -> {
            var chat = controller.getChat(-dialog); return chat == null ? null : ChatObject.isChannel(chat) ? "channel" : "chat";
        });
    }

    public AxFolderReplica editorSnapshot() throws JSONException {
        checkUi();
        if (!active()) throw new IllegalStateException("Folder coordinator closed");
        return observed.captureNative(controller.dialogFilters, types());
    }

    public boolean captureNativeChange() {
        checkUi();
        if (!active()) return false;
        try {
            AxFolderReplica before = observed, after = editorSnapshot();
            validUi = true;
            expectedUi = AxFolderUiProjection.signature(controller.dialogFilters);
            observed = after;
            if (!Arrays.equals(before.encode(), after.encode())) {
                generation++;
                queue.enqueue(before, after, state -> onLocalChanged.run(), ignored -> { });
            }
            if (!queue.hasPending()) store.setNetworkAllowed(true);
        } catch (Exception error) { validUi = false; store.setNetworkAllowed(false); report(error); }
        return true;
    }

    public boolean remove(MessagesController.DialogFilter filter) {
        checkUi();
        if (!active()) return false;
        MessagesController.DialogFilter current = controller.dialogFiltersById.get(filter.id);
        if (current != null && java.util.Objects.equals(current.axSyncUuid, filter.axSyncUuid)) controller.removeFilter(current);
        return captureNativeChange();
    }

    public void submitEditor(AxFolderReplica baseline, MessagesController.DialogFilter draft, boolean creating, boolean atBegin,
            Consumer<AxFolderReplica> saved, Consumer<Exception> failed) {
        checkUi();
        try {
            if (!active() || baseline == null || baseline.account != account) throw new IOException("Editor baseline unavailable; reopen the folder");
            AxFolderReplica after = baseline.editedNativeFilter(draft, creating, atBegin, types());
            expectedUi = AxFolderUiProjection.signature(controller.dialogFilters); generation++;
            queue.enqueue(baseline, after, state -> { onLocalChanged.run(); saved.accept(state); }, failed);
        } catch (Exception error) { failed.accept(error); }
    }

    private void settled(AxFolderReplica state) {
        if (!active()) return;
        try {
            if (!Arrays.equals(expectedUi, AxFolderUiProjection.signature(controller.dialogFilters))) {
                captureNativeChange();
                if (!validUi || queue.hasPending()) return;
            }
            if (!controller.applyAxFolderProjection(new AxFolderUiProjection(state, controller.dialogFilters))) { requestRefresh(); return; }
            observed = state.captureNative(controller.dialogFilters, state.peerTypes(dialog -> {
                var chat = controller.getChat(-dialog); return chat == null ? null : ChatObject.isChannel(chat) ? "channel" : "chat";
            }));
            expectedUi = AxFolderUiProjection.signature(controller.dialogFilters);
            store.adoptUiFilters(controller.dialogFilters);
            if (closeAfterWrites) close();
        } catch (Exception error) { store.setNetworkAllowed(false); report(error); }
    }

    public void requestRefresh() {
        checkUi();
        if (!active() || queue.hasPending() || reading || refreshScheduled) return;
        refreshScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            refreshScheduled = false;
            if (!active() || queue.hasPending() || reading) return;
            try {
                byte[] before = AxFolderUiProjection.signature(controller.dialogFilters);
                long started = generation; reading = true;
                store.readUiState(state -> {
                    reading = false;
                    if (!active()) return;
                    try {
                        if (generation != started || queue.hasPending() || !Arrays.equals(before, AxFolderUiProjection.signature(controller.dialogFilters))) { requestRefresh(); return; }
                        settled(state);
                    } catch (Exception error) { report(error); }
                }, error -> { reading = false; if (active()) report(error); });
            } catch (Exception error) { report(error); }
        }, 120);
    }

    public ArrayList<MessagesController.DialogFilter> mergeTelegramFilters(ArrayList<MessagesController.DialogFilter> incoming) {
        checkUi();
        ArrayList<MessagesController.DialogFilter> result = new ArrayList<>(), leading = new ArrayList<>();
        HashMap<Integer, ArrayList<MessagesController.DialogFilter>> after = new HashMap<>();
        Integer anchor = null;
        for (MessagesController.DialogFilter filter : controller.dialogFilters) {
            if (filter.id < 1000) anchor = filter.id;
            else if (anchor == null) leading.add(filter);
            else after.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(filter);
        }
        result.addAll(leading);
        ArrayList<MessagesController.DialogFilter> servers = new ArrayList<>();
        for (MessagesController.DialogFilter filter : incoming) if (filter.id < 1000) servers.add(filter);
        servers.sort((first, second) -> Integer.compare(first.order, second.order));
        for (MessagesController.DialogFilter filter : servers) {
            result.add(filter);
            ArrayList<MessagesController.DialogFilter> locals = after.remove(filter.id);
            if (locals != null) result.addAll(locals);
        }
        for (MessagesController.DialogFilter filter : controller.dialogFilters) if (filter.id >= 1000 && !result.contains(filter)) result.add(filter);
        for (int i = 0; i < result.size(); i++) result.get(i).order = i;
        return result;
    }

    public void retry() {
        checkUi();
        if (!active()) return;
        captureNativeChange();
        if (!validUi) return;
        queue.retry();
        if (!queue.hasPending()) { store.setNetworkAllowed(true); requestRefresh(); onLocalChanged.run(); }
    }
    public void closeWhenIdle() { checkUi(); closeAfterWrites = true; if (!queue.hasPending()) close(); }
    public void stopForLogout() { store.close(); AndroidUtilities.runOnUIThread(this::close); }

    private void report(Exception error) { if (active()) onError.accept(error); }

    @Override public void close() {
        checkUi();
        if (closed) return;
        closed = true; queue.close(); store.close();
        if (controller.getAxFolderUiCoordinator() == this) controller.setAxFolderUiCoordinator(null);
    }
}
