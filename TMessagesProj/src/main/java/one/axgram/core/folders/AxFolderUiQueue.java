package one.axgram.core.folders;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

public final class AxFolderUiQueue {
    public interface Writer {
        void write(String operation, AxFolderReplica before, AxFolderReplica after, Consumer<AxFolderReplica> saved, Consumer<Exception> failed);
    }

    private static final class Command {
        final String id = UUID.randomUUID().toString();
        final AxFolderReplica before, after;
        final Consumer<AxFolderReplica> saved;
        final Consumer<Exception> failed;
        boolean failureReported;
        Command(AxFolderReplica before, AxFolderReplica after, Consumer<AxFolderReplica> saved, Consumer<Exception> failed) {
            this.before = before; this.after = after; this.saved = saved; this.failed = failed;
        }
    }

    private final Writer writer;
    private final Consumer<Boolean> blocked;
    private final Consumer<AxFolderReplica> settled;
    private final Consumer<Exception> error;
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private boolean writing, failed, closed;

    public AxFolderUiQueue(Writer writer, Consumer<Boolean> blocked, Consumer<AxFolderReplica> settled, Consumer<Exception> error) {
        if (writer == null || blocked == null || settled == null || error == null) throw new IllegalArgumentException("Queue callbacks required");
        this.writer = writer; this.blocked = blocked; this.settled = settled; this.error = error;
    }

    public String enqueue(AxFolderReplica before, AxFolderReplica after, Consumer<AxFolderReplica> saved, Consumer<Exception> rejected) {
        if (closed) throw new IllegalStateException("Folder queue closed");
        if (before == null || after == null || before.account != after.account || saved == null || rejected == null) throw new IllegalArgumentException("Invalid folder command");
        Command command = new Command(before, after, saved, rejected);
        commands.add(command); blocked.accept(true); failed = false; drain();
        return command.id;
    }

    public boolean hasPending() { return !commands.isEmpty(); }
    public boolean isFailed() { return failed; }
    public void retry() { if (!closed) { failed = false; drain(); } }
    public void close() { closed = true; commands.clear(); blocked.accept(true); }

    private void drain() {
        if (closed || writing || failed || commands.isEmpty()) return;
        writing = true;
        Command command = commands.peek();
        try { writer.write(command.id, command.before, command.after, state -> completed(command, state), failure -> rejected(command, failure)); }
        catch (RuntimeException failure) { rejected(command, failure); }
    }

    private void completed(Command command, AxFolderReplica state) {
        if (closed || commands.peek() != command || !writing) return;
        if (state == null || state.account != command.after.account) { rejected(command, new IllegalStateException("Wrong saved account")); return; }
        writing = false; commands.remove();
        try {
            if (commands.isEmpty()) { blocked.accept(false); settled.accept(state); }
            command.saved.accept(state);
        } finally { drain(); }
    }

    private void rejected(Command command, Exception failure) {
        if (closed || commands.peek() != command || !writing) return;
        writing = false; failed = true; blocked.accept(true); error.accept(failure);
        for (Command pending : new ArrayList<>(commands)) if (!pending.failureReported) {
            pending.failureReported = true; pending.failed.accept(failure);
        }
    }
}
