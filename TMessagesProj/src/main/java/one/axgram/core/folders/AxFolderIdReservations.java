package one.axgram.core.folders;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class AxFolderIdReservations {
    public static final class Lease {
        private final String id = UUID.randomUUID().toString();
        private final Set<Integer> ids;
        private Lease(Set<Integer> ids) { this.ids = ids; }
    }

    public final long account;
    private final WeakHashMap<Object, Integer> drafts = new WeakHashMap<>();
    private final HashMap<String, Lease> imports = new HashMap<>();
    private Set<Integer> stored = new HashSet<>();

    public AxFolderIdReservations(long account) {
        if (account <= 0) throw new IllegalArgumentException("Account required");
        this.account = account;
    }

    public synchronized int reserveDraft(Object draft, Collection<Integer> visible) {
        if (draft == null || visible == null) throw new IllegalArgumentException("Draft required");
        Integer prior = drafts.get(draft);
        if (prior != null) return prior;
        HashSet<Integer> unavailable = new HashSet<>(stored); unavailable.addAll(visible); unavailable.addAll(drafts.values());
        for (Lease lease : imports.values()) unavailable.addAll(lease.ids);
        int candidate = 1000;
        while (unavailable.contains(candidate)) {
            if (candidate == Integer.MAX_VALUE) throw new IllegalStateException("Local IDs exhausted");
            candidate++;
        }
        drafts.put(draft, candidate); return candidate;
    }

    public synchronized void releaseDraft(Object draft) { drafts.remove(draft); }

    public synchronized void confirmSaved(Object draft, int id) {
        if (id < 1000) throw new IllegalArgumentException("Local ID required");
        stored.add(id); drafts.remove(draft);
    }

    public synchronized void confirmDeleted(int id) { stored.remove(id); }

    public synchronized void observeStored(AxFolderReplica state) {
        checkOwner(state); stored = new HashSet<>(state.nativeIds());
    }

    public synchronized void confirmUiCommit(AxFolderReplica state) {
        observeStored(state);
        drafts.entrySet().removeIf(entry -> stored.contains(entry.getValue()));
    }

    public synchronized Lease beginImport(AxFolderReplica state) {
        checkOwner(state);
        Set<Integer> incoming = state.nativeIds();
        for (int id : drafts.values()) if (incoming.contains(id)) return null;
        Lease lease = new Lease(incoming); imports.put(lease.id, lease); return lease;
    }

    public synchronized void finishImport(Lease lease, AxFolderReplica committed) {
        if (lease == null || imports.get(lease.id) != lease) throw new IllegalArgumentException("Unknown import lease");
        if (committed != null) { checkOwner(committed); stored = new HashSet<>(committed.nativeIds()); }
        imports.remove(lease.id);
    }

    private void checkOwner(AxFolderReplica state) {
        if (state == null || state.account != account) throw new IllegalArgumentException("Wrong reservation account");
    }
}
