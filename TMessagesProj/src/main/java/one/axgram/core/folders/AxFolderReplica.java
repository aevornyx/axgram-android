package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class AxFolderReplica {
    public static final class LocalFolder {
        public final String uuid;
        public final MessagesController.DialogFilter filter;
        public LocalFolder(String uuid, MessagesController.DialogFilter filter) {
            this.uuid = uuid; this.filter = filter;
        }
    }
    public static final int MAX_STATE_BYTES = 3 * 1024 * 1024;
    public final long account;
    public final String device;
    public final AxFolderDocument document;
    private final Map<String, Integer> ids;
    private final Map<String, AxFolderLocalRules> localRules;

    private AxFolderReplica(long account, String device, AxFolderDocument document, Map<String, Integer> ids) throws JSONException {
        this(account, device, document, ids, Collections.emptyMap());
    }

    private AxFolderReplica(long account, String device, AxFolderDocument document, Map<String, Integer> ids,
            Map<String, AxFolderLocalRules> localRules) throws JSONException {
        try {
            if (account <= 0 || !device.equals(UUID.fromString(device).toString())) throw new JSONException("Invalid replica identity");
        } catch (IllegalArgumentException e) { throw new JSONException("Invalid replica identity"); }
        AxFolderDocument.parse(document.encode(), account);
        HashSet<Integer> used = new HashSet<>();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            JSONObject folder = document.folder(entry.getKey());
            if (folder == null || folder.has("deleted") || entry.getValue() < 1000 || !used.add(entry.getValue())) throw new JSONException("Invalid local mapping");
        }
        if (ids.size() != liveIds(document).size()) throw new JSONException("Incomplete local mapping");
        this.account = account; this.device = device; this.document = document;
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
        Map<String, AxFolderLocalRules> local = new TreeMap<>();
        for (Map.Entry<String, AxFolderLocalRules> entry : localRules.entrySet()) {
            if (!ids.containsKey(entry.getKey()) || entry.getValue() == null) throw new JSONException("Invalid local rules owner");
            local.put(entry.getKey(), entry.getValue());
        }
        this.localRules = Collections.unmodifiableMap(local);
    }

    public static AxFolderReplica fresh(long account) throws JSONException {
        return new AxFolderReplica(account, UUID.randomUUID().toString(), AxFolderDocument.empty(account), Collections.emptyMap());
    }

    public static AxFolderReplica decode(byte[] bytes, long account) throws JSONException {
        JSONObject state = AxFolderDocument.parseCanonicalJson(bytes, MAX_STATE_BYTES);
        Object version = state.get("v");
        boolean privateState = version instanceof Number && ((Number) version).doubleValue() == 2;
        if (state.length() != (privateState ? 6 : 5) || !(version instanceof Number) || (!privateState && ((Number) version).doubleValue() != 1)
                || !(state.get("account") instanceof String) || !state.getString("account").equals(Long.toString(account))) throw new JSONException("Invalid replica state");
        if (!(state.get("device") instanceof String) || !(state.get("document") instanceof String)) throw new JSONException("Invalid replica state");
        JSONObject mappings = state.getJSONObject("ids");
        Map<String, Integer> ids = new TreeMap<>();
        Iterator<String> keys = mappings.keys();
        while (keys.hasNext()) {
            String id = keys.next(); Object value = mappings.get(id);
            if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) throw new JSONException("Invalid native ID");
            ids.put(id, ((Number) value).intValue());
        }
        Map<String, AxFolderLocalRules> local = new TreeMap<>();
        if (privateState) {
            JSONObject values = state.getJSONObject("localRules");
            if (values.length() == 0) throw new JSONException("Empty private state");
            Iterator<String> folders = values.keys();
            while (folders.hasNext()) {
                String id = folders.next();AxFolderLocalRules rules = AxFolderLocalRules.parse(values.getJSONObject(id));
                local.put(id, rules);
            }
        }
        return new AxFolderReplica(account, state.getString("device"),
                AxFolderDocument.parse(state.getString("document").getBytes(StandardCharsets.UTF_8), account), ids, local);
    }

    public byte[] encode() throws JSONException {
        JSONObject mappings = new JSONObject();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) mappings.put(entry.getKey(), entry.getValue());
        JSONObject state = new JSONObject().put("v", localRules.isEmpty() ? 1 : 2).put("account", Long.toString(account)).put("device", device)
                .put("document", new String(document.encode(), StandardCharsets.UTF_8)).put("ids", mappings);
        if (!localRules.isEmpty()) {
            JSONObject local = new JSONObject();
            for (Map.Entry<String, AxFolderLocalRules> entry : localRules.entrySet()) local.put(entry.getKey(), entry.getValue().json());
            state.put("localRules", local);
        }
        byte[] bytes = AxFolderDocument.canonical(state).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATE_BYTES) throw new JSONException("Replica state too large");
        return bytes;
    }

    public AxFolderReplica capture(List<LocalFolder> filters, AxFolderFilterCodec.PeerTypes types) throws JSONException {
        Map<String, Integer> next = new TreeMap<>();
        Map<String, AxFolderLocalRules> localOnly = new TreeMap<>();
        HashSet<Integer> seen = new HashSet<>();
        JSONArray order = new JSONArray();
        AxFolderDocument changed = document;
        HashSet<String> seenUuids = new HashSet<>();
        for (LocalFolder local : filters) {
            MessagesController.DialogFilter filter = local.filter;
            if (filter.id < 0 || !seen.add(filter.id)) throw new JSONException("Invalid native folder list");
            if (filter.id < 1000) { order.put("remote:" + filter.id); continue; }
            JSONObject values = AxFolderFilterCodec.exportFilter(filter, types);
            String id = local.uuid;
            if (id == null || !seenUuids.add(id)) throw new JSONException("Stable folder UUID required");
            JSONObject old = document.folder(id), updates = new JSONObject();
            if (old != null && old.has("deleted")) continue;
            AxFolderLocalRules privateRules = AxFolderLocalRules.capture(filter);
            if (!privateRules.isEmpty() || localRules.containsKey(id)) localOnly.put(id, privateRules);
            Iterator<String> names = values.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object before = old == null ? null : old.getJSONObject("fields").getJSONObject(name).get("value");
                if (before == null || !AxFolderDocument.canonical(before).equals(AxFolderDocument.canonical(values.get(name)))) updates.put(name, values.get(name));
            }
            if (updates.length() > 0) changed = changed.edit(id, updates, device);
            next.put(id, filter.id); order.put("local:" + id);
        }
        for (String id : ids.keySet()) if (!next.containsKey(id)) changed = changed.delete(id, device);
        JSONObject snapshot = new JSONObject(new String(changed.encode(), StandardCharsets.UTF_8));
        JSONObject priorOrder = snapshot.optJSONObject("order");
        ArrayList<Integer> actual = new ArrayList<>(), remoteIds = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) {
            String reference = order.getString(i);
            int nativeId = reference.startsWith("local:") ? next.get(reference.substring(6)) : Integer.parseInt(reference.substring(7));
            actual.add(nativeId); if (nativeId < 1000) remoteIds.add(nativeId);
        }
        AxFolderReplica projected = new AxFolderReplica(account, device, changed, next, localOnly);
        if (priorOrder == null || !projected.nativeOrder(remoteIds).equals(actual)) changed = changed.reorder(order, device);
        return new AxFolderReplica(account, device, changed, next, localOnly);
    }

    public AxFolderReplica captureNative(List<MessagesController.DialogFilter> filters, AxFolderFilterCodec.PeerTypes types) throws JSONException {
        ArrayList<LocalFolder> source = new ArrayList<>();
        for (MessagesController.DialogFilter filter : filters) source.add(new LocalFolder(filter.axSyncUuid, filter));
        return capture(source, types);
    }

    public AxFolderReplica merge(AxFolderDocument incoming) throws JSONException {
        AxFolderDocument merged = document.merge(incoming);
        Map<String, Integer> mappings = new TreeMap<>(); HashSet<Integer> used = new HashSet<>();
        List<String> live = liveIds(merged);
        for (String id : live) if (ids.containsKey(id)) { mappings.put(id, ids.get(id)); used.add(ids.get(id)); }
        int candidate = 1000;
        for (String id : live) if (!mappings.containsKey(id)) {
            while (used.contains(candidate)) {
                if (candidate == Integer.MAX_VALUE) throw new JSONException("Native IDs exhausted");
                candidate++;
            }
            mappings.put(id, candidate); used.add(candidate);
        }
        Map<String, AxFolderLocalRules> local = new TreeMap<>();
        for (String id : live) if (localRules.containsKey(id)) local.put(id, localRules.get(id));
        return new AxFolderReplica(account, device, merged, mappings, local);
    }

    public AxFolderReplica replayUiChanges(AxFolderReplica before, AxFolderReplica after) throws JSONException {
        if (before.account != account || after.account != account) throw new JSONException("Wrong UI account");
        AxFolderDocument changed = document;
        List<String> previous = liveIds(before.document), current = liveIds(after.document);
        for (String id : previous) if (!current.contains(id)) changed = changed.delete(id, device);
        for (String id : current) {
            JSONObject target = changed.folder(id);
            if (target != null && target.has("deleted")) continue;
            JSONObject old = before.document.folder(id), now = after.document.folder(id), updates = new JSONObject();
            JSONObject fields = now.getJSONObject("fields");
            Iterator<String> names = fields.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = fields.getJSONObject(name).get("value");
                boolean edited = target == null || old == null || old.has("deleted") || !AxFolderDocument.canonical(value)
                        .equals(AxFolderDocument.canonical(old.getJSONObject("fields").getJSONObject(name).get("value")));
                Object existing = target == null ? null : target.getJSONObject("fields").getJSONObject(name).get("value");
                if (edited && (existing == null || !AxFolderDocument.canonical(value).equals(AxFolderDocument.canonical(existing)))) updates.put(name, value);
            }
            if (updates.length() > 0) changed = changed.edit(id, updates, device);
        }
        List<String> live = liveIds(changed);
        Map<String, Integer> mappings = new TreeMap<>(); HashSet<Integer> used = new HashSet<>();
        for (String id : current) if (live.contains(id)) {
            int nativeId = after.ids.get(id);
            if (!used.add(nativeId)) throw new JSONException("Duplicate UI folder ID");
            mappings.put(id, nativeId);
        }
        for (String id : live) if (!mappings.containsKey(id) && ids.containsKey(id) && used.add(ids.get(id))) mappings.put(id, ids.get(id));
        int candidate = 1000;
        for (String id : live) if (!mappings.containsKey(id)) {
            while (used.contains(candidate)) {
                if (candidate == Integer.MAX_VALUE) throw new JSONException("Native IDs exhausted");
                candidate++;
            }
            mappings.put(id, candidate); used.add(candidate);
        }
        List<String> oldOrder = before.document.visibleOrder(), newOrder = after.document.visibleOrder();
        ArrayList<String> oldCommon = new ArrayList<>(), newCommon = new ArrayList<>();
        for (String id : oldOrder) if (newOrder.contains(id)) oldCommon.add(id);
        for (String id : newOrder) if (oldOrder.contains(id)) newCommon.add(id);
        boolean created = false;
        for (String id : current) if (!previous.contains(id) && live.contains(id)) created = true;
        if (created || !oldCommon.equals(newCommon)) {
            ArrayList<String> requested = new ArrayList<>();
            for (String reference : newOrder) if (reference.startsWith("remote:") || live.contains(reference.substring(6))) requested.add(reference);
            ArrayList<String> reordered = new ArrayList<>(); int position = 0;
            for (String reference : changed.visibleOrder()) {
                if (!requested.contains(reference)) reordered.add(reference);
                else if (position < requested.size()) reordered.add(requested.get(position++));
            }
            while (position < requested.size()) reordered.add(requested.get(position++));
            if (!reordered.equals(changed.visibleOrder())) changed = changed.reorder(new JSONArray(reordered), device);
        }
        Map<String, AxFolderLocalRules> local = new TreeMap<>();
        for (String id : live) {
            AxFolderLocalRules rules = AxFolderLocalRules.replay(localRules.get(id), before.localRules.get(id), after.localRules.get(id));
            if (!rules.isEmpty() || localRules.containsKey(id) || before.localRules.containsKey(id) || after.localRules.containsKey(id)) local.put(id, rules);
        }
        return new AxFolderReplica(account, device, changed, mappings, local);
    }

    public List<MessagesController.DialogFilter> nativeFolders() throws JSONException {
        ArrayList<MessagesController.DialogFilter> result = new ArrayList<>();
        for (LocalFolder local : localFolders()) result.add(local.filter);
        return Collections.unmodifiableList(result);
    }

    public java.util.Set<Integer> nativeIds() { return Collections.unmodifiableSet(new HashSet<>(ids.values())); }
    boolean hasLocalRules(String uuid) { return localRules.containsKey(uuid); }

    public AxFolderFilterCodec.PeerTypes peerTypes(AxFolderFilterCodec.PeerTypes actual) throws JSONException {
        HashMap<Long, String> known = new HashMap<>();
        for (String id : liveIds(document)) {
            JSONObject fields = document.folder(id).getJSONObject("fields");
            JSONObject rules = fields.getJSONObject("rules").getJSONObject("value");
            for (JSONArray peers : new JSONArray[]{rules.getJSONArray("include"), rules.getJSONArray("exclude"), fields.getJSONObject("pinned").getJSONArray("value")}) {
                for (int i = 0; i < peers.length(); i++) {
                    JSONObject peer = peers.getJSONObject(i); String type = peer.getString("type");
                    if (type.equals("user")) continue;
                    long dialog = -Long.parseLong(peer.getString("id"));
                    if (known.containsKey(dialog) && !known.get(dialog).equals(type)) throw new JSONException("Native peer types collide");
                    String nativeType = actual.type(dialog);
                    if (nativeType != null && !nativeType.equals(type)) throw new JSONException("Peer type conflicts with Telegram metadata");
                    known.put(dialog, type);
                }
            }
        }
        return dialog -> { String type = actual.type(dialog); return type == null ? known.get(dialog) : type; };
    }

    public AxFolderReplica editedNativeFilter(MessagesController.DialogFilter edited, boolean creating, boolean atBegin, AxFolderFilterCodec.PeerTypes types) throws JSONException {
        if (edited.id < 1000 || edited.axSyncUuid == null) throw new JSONException("Local folder identity required");
        HashMap<Integer, MessagesController.DialogFilter> byId = new HashMap<>();
        ArrayList<Integer> serverIds = new ArrayList<>();
        for (String reference : document.visibleOrder()) if (reference.startsWith("remote:")) {
            long remoteId = Long.parseLong(reference.substring(7));
            if (remoteId >= 1000) continue;
            MessagesController.DialogFilter server = new MessagesController.DialogFilter();
            server.id = (int) remoteId;
            byId.put(server.id, server); serverIds.add(server.id);
        }
        for (MessagesController.DialogFilter local : nativeFolders()) byId.put(local.id, local);
        ArrayList<MessagesController.DialogFilter> snapshot = new ArrayList<>(); boolean found = false;
        for (int id : nativeOrder(serverIds)) {
            MessagesController.DialogFilter filter = byId.get(id);
            if (edited.axSyncUuid.equals(filter.axSyncUuid)) { snapshot.add(edited); found = true; }
            else snapshot.add(filter);
        }
        if (creating) {
            if (document.folder(edited.axSyncUuid) != null) throw new JSONException("New folder UUID already exists");
            if (atBegin) snapshot.add(!snapshot.isEmpty() && snapshot.get(0).id == 0 ? 1 : 0, edited);
            else snapshot.add(edited);
        } else if (!found) throw new JSONException("Edited folder is absent from baseline");
        return captureNative(snapshot, peerTypes(types));
    }

    public List<LocalFolder> localFolders() throws JSONException {
        peerTypes(dialog -> null);
        ArrayList<LocalFolder> result = new ArrayList<>();
        for (String id : liveIds(document)) {
            JSONObject fields = document.folder(id).getJSONObject("fields"), values = new JSONObject();
            Iterator<String> names = fields.keys();
            while (names.hasNext()) { String name = names.next(); values.put(name, fields.getJSONObject(name).get("value")); }
            MessagesController.DialogFilter filter = AxFolderFilterCodec.importFilter(ids.get(id), values);
            filter.axSyncUuid = id;
            if (localRules.containsKey(id)) localRules.get(id).apply(filter);
            result.add(new LocalFolder(id, filter));
        }
        return Collections.unmodifiableList(result);
    }

    public List<Integer> nativeOrder(List<Integer> existingServerIds) throws JSONException {
        ArrayList<Integer> result = new ArrayList<>();
        for (String reference : document.visibleOrder()) {
            int id;
            if (reference.startsWith("local:")) id = ids.get(reference.substring(6));
            else {
                long remote = Long.parseLong(reference.substring(7));
                if (remote >= 1000 || !existingServerIds.contains((int) remote)) continue;
                id = (int) remote;
            }
            result.add(id);
        }
        for (Integer id : existingServerIds) if (!result.contains(id)) result.add(id);
        return Collections.unmodifiableList(result);
    }

    private static List<String> liveIds(AxFolderDocument document) throws JSONException {
        ArrayList<String> ids = new ArrayList<>();
        for (String ref : document.visibleOrder()) if (ref.startsWith("local:")) ids.add(ref.substring(6));
        return ids;
    }
}
