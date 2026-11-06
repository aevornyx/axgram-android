package one.axgram.core.folders;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.Vector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class AxFolderUiProjection {
    public final long account;
    private final byte[] expected;
    private final List<MessagesController.DialogFilter> imported;
    private final List<Integer> order;

    public AxFolderUiProjection(AxFolderReplica state, List<MessagesController.DialogFilter> existing) throws JSONException {
        account = state.account;
        expected = signature(existing);
        imported = state.nativeFolders();
        HashMap<String, MessagesController.DialogFilter> previous = new HashMap<>();
        ArrayList<Integer> server = new ArrayList<>();
        for (MessagesController.DialogFilter filter : existing) {
            if (filter.id < 1000) server.add(filter.id);
            else {
                if (filter.axSyncUuid == null || (filter.flags & ~511) != 0) throw new JSONException("Local folder is not ready for projection");
                previous.put(filter.axSyncUuid, filter);
            }
        }
        for (MessagesController.DialogFilter filter : imported) {
            MessagesController.DialogFilter old = previous.get(filter.axSyncUuid);
            if (old != null && !state.hasLocalRules(filter.axSyncUuid)) AxFolderLocalRules.capture(old).apply(filter);
        }
        order = state.nativeOrder(server);
    }

    public ArrayList<MessagesController.DialogFilter> applyIfUnchanged(List<MessagesController.DialogFilter> existing) throws JSONException {
        if (!Arrays.equals(expected, signature(existing))) return null;
        HashMap<Integer, MessagesController.DialogFilter> resultById = new HashMap<>();
        HashMap<String, MessagesController.DialogFilter> previous = new HashMap<>();
        for (MessagesController.DialogFilter filter : existing) {
            if (filter.id < 1000) resultById.put(filter.id, filter);
            else if (filter.axSyncUuid != null) previous.put(filter.axSyncUuid, filter);
        }
        ArrayList<MessagesController.DialogFilter> targets = new ArrayList<>();
        for (MessagesController.DialogFilter incoming : imported) {
            MessagesController.DialogFilter target = previous.get(incoming.axSyncUuid);
            if (target == null) target = incoming;
            resultById.put(incoming.id, target); targets.add(target);
        }
        ArrayList<MessagesController.DialogFilter> result = new ArrayList<>();
        for (int id : order) {
            MessagesController.DialogFilter filter = resultById.get(id);
            if (filter == null) throw new JSONException("Incomplete folder projection");
            result.add(filter);
        }
        if (result.size() != resultById.size()) throw new JSONException("Incomplete folder order");
        for (int i = 0; i < imported.size(); i++) {
            MessagesController.DialogFilter source = imported.get(i), target = targets.get(i);
            boolean rulesChanged = target == source || target.flags != source.flags || !target.alwaysShow.equals(source.alwaysShow) || !target.neverShow.equals(source.neverShow);
            target.id = source.id; target.axSyncUuid = source.axSyncUuid;
            target.name = source.name; target.entities = source.entities; target.title_noanimate = source.title_noanimate;
            target.emoticon = source.emoticon; target.color = source.color; target.flags = source.flags;
            target.alwaysShow = source.alwaysShow; target.neverShow = source.neverShow; target.pinnedDialogs = source.pinnedDialogs;
            target.locked = false;
            if (rulesChanged) { target.unreadCount = -1; target.pendingUnreadCount = -1; }
        }
        for (int i = 0; i < result.size(); i++) result.get(i).order = i;
        return result;
    }

    public static byte[] signature(List<MessagesController.DialogFilter> filters) throws JSONException {
        JSONArray values = new JSONArray(); HashSet<Integer> ids = new HashSet<>(); HashSet<String> uuids = new HashSet<>();
        for (MessagesController.DialogFilter filter : filters) {
            if (filter.id < 0 || !ids.add(filter.id)) throw new JSONException("Duplicate native folder ID");
            JSONObject value = new JSONObject().put("id", filter.id).put("order", filter.order);
            if (filter.id >= 1000) {
                if (filter.axSyncUuid != null && !uuids.add(filter.axSyncUuid)) throw new JSONException("Duplicate native folder UUID");
                value.put("uuid", filter.axSyncUuid == null ? JSONObject.NULL : filter.axSyncUuid);
                value.put("name", filter.name).put("flags", filter.flags).put("emoji", filter.emoticon == null ? JSONObject.NULL : filter.emoticon)
                        .put("color", filter.color).put("noAnimate", filter.title_noanimate);
                SerializedData entities = new SerializedData();
                try {
                    Vector.serialize(entities, filter.entities);
                    value.put("entities", Base64.encodeToString(entities.toByteArray(), Base64.NO_WRAP));
                } finally { entities.cleanup(); }
                JSONArray pins = new JSONArray();
                for (int i = 0; i < filter.pinnedDialogs.size(); i++) pins.put(new JSONArray().put(Long.toString(filter.pinnedDialogs.keyAt(i))).put(filter.pinnedDialogs.valueAt(i)));
                value.put("pins", pins).put("include", peers(filter.alwaysShow)).put("exclude", peers(filter.neverShow));
            }
            values.put(value);
        }
        return AxFolderDocument.canonical(values).getBytes(StandardCharsets.UTF_8);
    }

    private static JSONArray peers(List<Long> peers) {
        JSONArray result = new JSONArray();
        for (long peer : peers) result.put(Long.toString(peer));
        return result;
    }

}
