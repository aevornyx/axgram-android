package one.axgram.core.folders;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.OAuthSheet;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class AxFolderTelegramConsent implements OAuthSheet.NativeCompletion, AutoCloseable {
    private final AccountInstance instance;
    private final AxFolderAuthClient.Flow flow;
    private final BooleanSupplier current;
    private final Consumer<String> accepted;
    private final Runnable failed, cancelled, expired;
    private final ArrayList<Runnable> dismissers = new ArrayList<>();
    private int request;
    private boolean finished;

    public AxFolderTelegramConsent(AccountInstance instance, AxFolderAuthClient.Flow flow, BooleanSupplier current, Consumer<String> accepted, Runnable failed, Runnable cancelled, Runnable expired) {
        if (instance == null || flow == null || current == null || accepted == null || failed == null || cancelled == null || expired == null) throw new IllegalArgumentException("Confirmation context required");
        this.instance = instance; this.flow = flow; this.current = current; this.accepted = accepted; this.failed = failed; this.cancelled = cancelled; this.expired = expired;
    }

    public void start() {
        try {
            AxFolderNativeAuth.confirmation(flow.confirmationUrl);
            if (!active()) { close(); return; }
            TLRPC.TL_messages_requestUrlAuth query = new TLRPC.TL_messages_requestUrlAuth();
            query.flags = 4; query.url = flow.confirmationUrl;
            request = instance.getConnectionsManager().sendRequest(query, (result, error) -> AndroidUtilities.runOnUIThread(() -> {
                request = 0;
                if (!active()) { close(); return; }
                if (error != null || !(result instanceof TLRPC.UrlAuthResult)) { failed(error == null ? null : error.text); return; }
                if (result instanceof TLRPC.TL_urlAuthResultRequest) {
                    TLRPC.TL_urlAuthResultRequest prompt = (TLRPC.TL_urlAuthResultRequest) result;
                    try {
                        AxFolderNativeAuth.prompt(prompt.bot == null ? 0 : prompt.bot.id, flow.clientId,
                                prompt.request_write_access, prompt.request_phone_number, prompt.user_id_hint, flow.account);
                    } catch (Exception rejected) { failed(); return; }
                }
                OAuthSheet.handleNative(instance.getCurrentAccount(), query, (TLRPC.UrlAuthResult) result, this);
            }), org.telegram.tgnet.ConnectionsManager.RequestFlagFailOnServerErrors);
        } catch (Exception error) { failed(); }
    }

    @Override public boolean active() { return !finished && current.getAsBoolean() && instance.getUserConfig().getClientUserId() == flow.account; }
    @Override public void bindDismiss(Runnable dismiss) { if (active()) dismissers.add(dismiss); else dismiss.run(); }

    @Override public void accepted(int account, String url) {
        if (!active() || account != instance.getCurrentAccount()) { close(); return; }
        finished = true; dismissers.clear(); accepted.accept(url);
    }

    @Override public void failed() {
        failed(null);
    }

    @Override public void failed(String error) {
        if (!active()) { close(); return; }
        close();
        if ("URL_EXPIRED".equalsIgnoreCase(error)) expired.run();
        else failed.run();
    }

    @Override public void cancelled() {
        if (!active()) { close(); return; }
        close(); cancelled.run();
    }

    @Override public void close() {
        if (finished) return;
        finished = true;
        if (request != 0) { instance.getConnectionsManager().cancelRequest(request, true); request = 0; }
        ArrayList<Runnable> pending = new ArrayList<>(dismissers); dismissers.clear();
        for (Runnable dismiss : pending) dismiss.run();
    }
}
