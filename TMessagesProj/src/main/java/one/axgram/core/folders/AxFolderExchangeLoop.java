package one.axgram.core.folders;

public final class AxFolderExchangeLoop implements AutoCloseable {
    public enum State { PAUSED, WAITING, EXCHANGING, RETRYING, LOGIN_REQUIRED, CLOSED }

    public interface Environment {
        long now();
        void post(Runnable task, long delay);
        void cancel(Runnable task);
        void work(Runnable task);
        boolean sessionActive();
        boolean canExchange();
        void refresh();
        void status(State state, Exception error);
    }

    public interface Exchange extends AutoCloseable {
        boolean run() throws Exception;
        @Override void close();
    }

    private final Environment environment;
    private final Exchange exchange;
    private final Runnable tick = this::begin;
    private boolean foreground, running, requested, closed, loginRequired;
    private long notBefore;
    private int failures;

    public AxFolderExchangeLoop(Environment environment, Exchange exchange) {
        if (environment == null || exchange == null) throw new IllegalArgumentException("Exchange environment required");
        this.environment = environment; this.exchange = exchange;
    }

    public void setForeground(boolean foreground) {
        if (closed) return;
        this.foreground = foreground;
        if (foreground) wake();
        else {
            environment.cancel(tick);
            if (!running && !loginRequired) environment.status(State.PAUSED, null);
        }
    }

    public void wake() {
        if (closed || loginRequired) return;
        requested = true;
        if (foreground && !running) arm(200);
    }

    public void retry() {
        if (closed || loginRequired) return;
        notBefore = 0; failures = 0; wake();
    }

    private void arm(long delay) {
        environment.cancel(tick);
        if (!closed && foreground && !loginRequired) {
            environment.post(tick, Math.max(delay, Math.max(0, notBefore - environment.now())));
        }
    }

    private void begin() {
        if (closed || !foreground || running || loginRequired) return;
        if (!environment.sessionActive()) { close(); return; }
        if (!environment.canExchange()) { arm(1000); return; }
        running = true; requested = false;
        environment.status(State.EXCHANGING, null);
        try {
            environment.work(() -> {
                boolean pending = false; Exception failure = null;
                try { pending = exchange.run(); }
                catch (Exception error) { failure = error; }
                boolean result = pending; Exception error = failure;
                environment.post(() -> completed(result, error), 0);
            });
        } catch (RuntimeException error) { completed(false, error); }
    }

    private void completed(boolean pending, Exception error) {
        if (closed) return;
        running = false;
        if (!environment.sessionActive()) { close(); return; }
        environment.refresh();
        if (error instanceof AxFolderTransport.HttpFailure
                && (((AxFolderTransport.HttpFailure) error).status == 401 || ((AxFolderTransport.HttpFailure) error).status == 403)) {
            loginRequired = true; environment.cancel(tick);
            environment.status(State.LOGIN_REQUIRED, error); return;
        }
        if (error != null) {
            failures = Math.min(7, failures + 1);
            notBefore = environment.now() + Math.min(60000, 1000L << (failures - 1));
            environment.status(foreground ? State.RETRYING : State.PAUSED, error);
            arm(0); return;
        }
        failures = 0; notBefore = 0;
        environment.status(foreground ? State.WAITING : State.PAUSED, null);
        arm(pending || requested ? 200 : 30000);
    }

    @Override public void close() {
        if (closed) return;
        closed = true; environment.cancel(tick);
        environment.work(exchange::close);
        environment.status(State.CLOSED, null);
    }
}
