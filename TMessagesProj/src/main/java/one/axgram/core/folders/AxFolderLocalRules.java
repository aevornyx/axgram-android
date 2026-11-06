package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import java.util.ArrayList;
import java.util.HashSet;

final class AxFolderLocalRules {
    private static final String[] FIELDS = {"include", "exclude", "pinned"};
    private final JSONObject value;

    private AxFolderLocalRules(JSONObject value) { this.value = value; }

    static AxFolderLocalRules capture(MessagesController.DialogFilter filter) throws JSONException {
        JSONArray include = secrets(filter.alwaysShow), exclude = secrets(filter.neverShow), pins = new JSONArray();
        ArrayList<Long> ordered = new ArrayList<>();
        for (int i = 0; i < filter.pinnedDialogs.size(); i++) ordered.add(filter.pinnedDialogs.keyAt(i));
        ordered.sort((a, b) -> {
            int order = Integer.compare(filter.pinnedDialogs.get(a), filter.pinnedDialogs.get(b));
            return order == 0 ? Long.compare(a, b) : order;
        });
        for (int i = 0; i < ordered.size(); i++) if (DialogObject.isEncryptedDialog(ordered.get(i))) {
            long peer = ordered.get(i);
            pins.put(new JSONArray().put(Long.toString(peer)).put(i));
            boolean present = false;
            for (int n = 0; n < include.length(); n++) if (include.getString(n).equals(Long.toString(peer))) present = true;
            if (!present) include.put(Long.toString(peer));
        }
        return parse(new JSONObject().put("include", include).put("exclude", exclude).put("pinned", pins));
    }

    private static JSONArray secrets(ArrayList<Long> peers) throws JSONException {
        JSONArray result = new JSONArray();
        for (long peer : peers) if (DialogObject.isEncryptedDialog(peer)) result.put(Long.toString(peer));
        return result;
    }

    static AxFolderLocalRules parse(JSONObject input) throws JSONException {
        if (input.length() != 3) throw new JSONException("Invalid local rules");
        JSONObject value = new JSONObject();
        HashSet<Long> included = new HashSet<>();
        for (String field : new String[]{"include", "exclude"}) {
            JSONArray peers = input.getJSONArray(field), copy = new JSONArray();
            HashSet<Long> seen = new HashSet<>();
            for (int i = 0; i < peers.length(); i++) {
                long peer = secret(peers.get(i));
                if (!seen.add(peer)) throw new JSONException("Duplicate local secret chat");
            }
            ArrayList<Long> ordered = new ArrayList<>(seen);ordered.sort(Long::compare);
            for (long peer : ordered) copy.put(Long.toString(peer));
            if (field.equals("include")) included.addAll(seen);
            value.put(field, copy);
        }
        JSONArray pins = input.getJSONArray("pinned"), copy = new JSONArray();
        HashSet<Long> seen = new HashSet<>();
        int previous = -1;
        for (int i = 0; i < pins.length(); i++) {
            JSONArray pin = pins.getJSONArray(i);
            if (pin.length() != 2) throw new JSONException("Invalid local pin");
            long peer = secret(pin.get(0));Object position = pin.get(1);
            if (!(position instanceof Number) || ((Number) position).doubleValue() != ((Number) position).intValue()
                    || ((Number) position).intValue() <= previous || !seen.add(peer) || !included.contains(peer)) throw new JSONException("Invalid local pin order");
            previous = ((Number) position).intValue();
            copy.put(new JSONArray().put(Long.toString(peer)).put(previous));
        }
        value.put("pinned", copy);
        return new AxFolderLocalRules(value);
    }

    private static long secret(Object value) throws JSONException {
        if (!(value instanceof String)) throw new JSONException("Invalid local peer");
        try {
            long peer = Long.parseLong((String) value);
            int chat = DialogObject.getEncryptedChatId(peer);
            if (!Long.toString(peer).equals(value) || !DialogObject.isEncryptedDialog(peer) || chat == 0
                    || DialogObject.makeEncryptedDialogId(chat) != peer) throw new NumberFormatException();
            return peer;
        } catch (NumberFormatException error) { throw new JSONException("Invalid local secret chat"); }
    }

    JSONObject json() throws JSONException { return new JSONObject(value.toString()); }
    boolean isEmpty() throws JSONException {
        for (String field : FIELDS) if (value.getJSONArray(field).length() != 0) return false;
        return true;
    }

    void apply(MessagesController.DialogFilter filter) throws JSONException {
        for (String field : new String[]{"include", "exclude"}) {
            ArrayList<Long> target = field.equals("include") ? filter.alwaysShow : filter.neverShow;
            JSONArray peers = value.getJSONArray(field);
            for (int i = 0; i < peers.length(); i++) target.add(Long.parseLong(peers.getString(i)));
        }
        ArrayList<Long> pins = new ArrayList<>();
        for (int i = 0; i < filter.pinnedDialogs.size(); i++) pins.add(filter.pinnedDialogs.keyAt(i));
        pins.sort((a, b) -> Integer.compare(filter.pinnedDialogs.get(a), filter.pinnedDialogs.get(b)));
        JSONArray local = value.getJSONArray("pinned");
        for (int i = 0; i < local.length(); i++) {
            JSONArray pin = local.getJSONArray(i);long peer = Long.parseLong(pin.getString(0));
            pins.add(Math.min(pin.getInt(1), pins.size()), peer);
        }
        filter.pinnedDialogs.clear();
        for (int i = 0; i < pins.size(); i++) filter.pinnedDialogs.put(pins.get(i), i);
    }

    static boolean same(AxFolderLocalRules a, AxFolderLocalRules b) throws JSONException {
        return AxFolderDocument.canonical(values(a)).equals(AxFolderDocument.canonical(values(b)));
    }

    private static JSONObject values(AxFolderLocalRules rules) throws JSONException {
        return rules == null ? new JSONObject().put("include", new JSONArray()).put("exclude", new JSONArray()).put("pinned", new JSONArray()) : rules.json();
    }

    static AxFolderLocalRules replay(AxFolderLocalRules current, AxFolderLocalRules before, AxFolderLocalRules after) throws JSONException {
        JSONObject result = values(current), old = values(before), edited = values(after);
        for (String field : FIELDS) if (!AxFolderDocument.canonical(old.get(field)).equals(AxFolderDocument.canonical(edited.get(field)))) result.put(field, edited.get(field));
        JSONArray include = result.getJSONArray("include"), pins = result.getJSONArray("pinned");
        HashSet<String> included = new HashSet<>();for (int i = 0; i < include.length(); i++) included.add(include.getString(i));
        for (int i = 0; i < pins.length(); i++) {
            String peer = pins.getJSONArray(i).getString(0);if (included.add(peer)) include.put(peer);
        }
        return parse(result);
    }
}
