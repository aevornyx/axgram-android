package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.UUID;

public final class AxFolderDocument {
    public static final int MAX_BYTES = 1024 * 1024 - 33;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("title", "appearance", "rules", "pinned"));
    private final JSONObject root;

    private AxFolderDocument(JSONObject root) throws JSONException {
        validate(root);
        TreeMap<String, JSONObject> sorted = new TreeMap<>();
        JSONArray folders = root.getJSONArray("folders");
        for (int i = 0; i < folders.length(); i++) sorted.put(folders.getJSONObject(i).getString("id"), folders.getJSONObject(i));
        JSONArray ordered = new JSONArray();
        for (JSONObject folder : sorted.values()) ordered.put(folder);
        root.put("folders", ordered);
        this.root = root;
    }

    public static AxFolderDocument empty(long account) throws JSONException {
        return new AxFolderDocument(new JSONObject().put("v", 1).put("account", Long.toString(account))
                .put("folders", new JSONArray()));
    }

    public static AxFolderDocument parse(byte[] bytes, long account) throws JSONException {
        JSONObject decoded = parseCanonicalJson(bytes, MAX_BYTES);
        AxFolderDocument document = new AxFolderDocument(decoded);
        if (!Arrays.equals(bytes, document.encode())) throw new JSONException("Non-canonical document");
        if (!document.root.getString("account").equals(Long.toString(account))) throw new JSONException("Wrong account");
        return document;
    }

    public static JSONObject parseCanonicalJson(byte[] bytes, int maxBytes) throws JSONException {
        if (bytes.length > maxBytes) throw new JSONException("Document too large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new JSONException("Invalid UTF-8"); }
        int depth = 0;
        boolean string = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') {
                if (++depth > 24) throw new JSONException("Document too deep");
            } else if (c == '}' || c == ']') depth--;
        }
        JSONTokener tokener = new JSONTokener(text);
        Object decoded = tokener.nextValue();
        if (!(decoded instanceof JSONObject) || tokener.nextClean() != 0) throw new JSONException("Invalid document");
        if (!Arrays.equals(bytes, canonical(decoded).getBytes(StandardCharsets.UTF_8))) throw new JSONException("Non-canonical JSON");
        return (JSONObject) decoded;
    }

    public byte[] encode() throws JSONException {
        byte[] bytes = canonical(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new JSONException("Document too large");
        return bytes;
    }

    public long account() throws JSONException { return Long.parseLong(root.getString("account")); }

    public long maxClock() throws JSONException {
        long result = root.has("order") ? clock(root.getJSONObject("order")) : 0;
        JSONArray folders = root.getJSONArray("folders");
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.getJSONObject(i);
            if (folder.has("deleted")) result = Math.max(result, clock(folder.getJSONObject("deleted")));
            JSONObject fields = folder.getJSONObject("fields");
            Iterator<String> names = fields.keys();
            while (names.hasNext()) result = Math.max(result, clock(fields.getJSONObject(names.next())));
        }
        return result;
    }

    private long nextClock() throws JSONException {
        long max = maxClock();
        if (max == Long.MAX_VALUE) throw new JSONException("Clock exhausted");
        return max + 1;
    }

    public AxFolderDocument merge(AxFolderDocument other) throws JSONException {
        if (!root.getString("account").equals(other.root.getString("account"))) throw new JSONException("Wrong account");
        JSONObject result = copy(root);
        JSONArray merged = new JSONArray();
        TreeSet<String> ids = new TreeSet<>();
        for (AxFolderDocument doc : new AxFolderDocument[]{this, other}) {
            JSONArray folders = doc.root.getJSONArray("folders");
            for (int i = 0; i < folders.length(); i++) ids.add(folders.getJSONObject(i).getString("id"));
        }
        for (String id : ids) {
            JSONObject a = find(root, id), b = find(other.root, id);
            if (a == null || b == null) { merged.put(copy(a != null ? a : b)); continue; }
            JSONObject folder = new JSONObject().put("id", id), fields = new JSONObject();
            for (String field : FIELDS) {
                JSONObject value = winner(a.getJSONObject("fields").optJSONObject(field), b.getJSONObject("fields").optJSONObject(field));
                if (value != null) fields.put(field, copy(value));
            }
            folder.put("fields", fields);
            JSONObject deleted = winner(a.optJSONObject("deleted"), b.optJSONObject("deleted"));
            if (deleted != null) folder.put("deleted", copy(deleted));
            merged.put(folder);
        }
        result.put("folders", merged);
        JSONObject order = winner(root.optJSONObject("order"), other.root.optJSONObject("order"));
        if (order != null) result.put("order", copy(order));
        return new AxFolderDocument(result);
    }

    public AxFolderDocument edit(String id, JSONObject values, String device) throws JSONException {
        uuid(id); uuid(device);
        JSONObject result = copy(root), folder = find(result, id);
        if (folder == null) {
            folder = new JSONObject().put("id", id).put("fields", new JSONObject());
            result.getJSONArray("folders").put(folder);
        }
        if (folder.has("deleted")) throw new JSONException("Deleted folder needs a new UUID");
        long next = nextClock();
        Iterator<String> names = values.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!FIELDS.contains(name)) throw new JSONException("Unknown field");
            folder.getJSONObject("fields").put(name, register(next, device, values.get(name)));
        }
        return new AxFolderDocument(result);
    }

    public AxFolderDocument delete(String id, String device) throws JSONException {
        uuid(id); uuid(device);
        JSONObject result = copy(root), folder = find(result, id);
        if (folder == null) {
            folder = new JSONObject().put("id", id).put("fields", new JSONObject());
            result.getJSONArray("folders").put(folder);
        }
        if (folder.has("deleted")) return this;
        folder.put("deleted", register(nextClock(), device, true));
        return new AxFolderDocument(result);
    }

    public AxFolderDocument reorder(JSONArray ids, String device) throws JSONException {
        uuid(device);
        JSONObject result = copy(root).put("order", register(nextClock(), device, ids));
        return new AxFolderDocument(result);
    }

    public JSONObject folder(String id) throws JSONException {
        JSONObject found = find(root, id);
        return found == null ? null : copy(found);
    }

    public List<String> visibleOrder() throws JSONException {
        TreeSet<String> remaining = new TreeSet<>();
        JSONArray folders = root.getJSONArray("folders");
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.getJSONObject(i);
            if (!folder.has("deleted")) remaining.add("local:" + folder.getString("id"));
        }
        ArrayList<String> result = new ArrayList<>();
        if (root.has("order")) {
            JSONArray order = root.getJSONObject("order").getJSONArray("value");
            for (int i = 0; i < order.length(); i++) {
                String id = order.getString(i);
                if (id.startsWith("remote:") || remaining.remove(id)) result.add(id);
            }
        }
        result.addAll(remaining);
        return Collections.unmodifiableList(result);
    }

    private static JSONObject register(long clock, String device, Object value) throws JSONException {
        return copy(new JSONObject().put("clock", Long.toString(clock)).put("device", device).put("value", value));
    }

    private static JSONObject find(JSONObject doc, String id) throws JSONException {
        JSONArray folders = doc.getJSONArray("folders");
        for (int i = 0; i < folders.length(); i++) if (folders.getJSONObject(i).getString("id").equals(id)) return folders.getJSONObject(i);
        return null;
    }

    private static JSONObject winner(JSONObject a, JSONObject b) throws JSONException {
        if (a == null) return b;
        if (b == null) return a;
        int cmp = Long.compare(clock(a), clock(b));
        if (cmp == 0) cmp = a.getString("device").compareTo(b.getString("device"));
        if (cmp == 0) cmp = canonical(a.get("value")).compareTo(canonical(b.get("value")));
        return cmp >= 0 ? a : b;
    }

    private static long clock(JSONObject value) throws JSONException { return decimal(value.get("clock"), true); }

    private static long decimal(Object value, boolean zero) throws JSONException {
        if (!(value instanceof String) || !((String) value).matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) throw new JSONException("Invalid decimal");
        try { return Long.parseLong((String) value); }
        catch (NumberFormatException e) { throw new JSONException("Decimal overflow"); }
    }

    private static void uuid(Object value) throws JSONException {
        try {
            if (!(value instanceof String) || !value.equals(UUID.fromString((String) value).toString())) throw new JSONException("Invalid UUID");
        } catch (IllegalArgumentException e) { throw new JSONException("Invalid UUID"); }
    }

    private static void keys(JSONObject object, String... allowed) throws JSONException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) if (!names.contains(keys.next())) throw new JSONException("Unknown property");
    }

    private static void checkRegister(JSONObject register) throws JSONException {
        keys(register, "clock", "device", "value"); clock(register); uuid(register.get("device"));
        if (!register.has("value")) throw new JSONException("Missing value");
    }

    private static int integer(Object value, int min, int max) throws JSONException {
        if (!(value instanceof Number)) throw new JSONException("Integer required");
        double number = ((Number) value).doubleValue();
        if (number != Math.rint(number) || number < min || number > max) throw new JSONException("Integer out of range");
        return (int) number;
    }

    private static void peers(Object value) throws JSONException {
        if (!(value instanceof JSONArray)) throw new JSONException("Peer list required");
        JSONArray peers = (JSONArray) value;
        if (peers.length() > 10000) throw new JSONException("Too many peers");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i); keys(peer, "type", "id");
            Object type = peer.get("type");
            if (!Arrays.asList("user", "chat", "channel").contains(type)) throw new JSONException("Unknown peer type");
            decimal(peer.get("id"), false);
            if (!seen.add(type + ":" + peer.getString("id"))) throw new JSONException("Duplicate peer");
        }
    }

    private static void definition(String name, Object value) throws JSONException {
        if (name.equals("pinned")) { peers(value); return; }
        if (!(value instanceof JSONObject)) throw new JSONException("Definition object required");
        JSONObject object = (JSONObject) value;
        if (name.equals("appearance")) {
            keys(object, "emoji", "color");
            if (!(object.get("emoji") instanceof String) || object.getString("emoji").length() > 64) throw new JSONException("Invalid emoji");
            if (object.get("color") != JSONObject.NULL) integer(object.get("color"), 0, 255);
        } else if (name.equals("rules")) {
            keys(object, "contacts", "nonContacts", "groups", "channels", "bots", "excludeMuted", "excludeRead", "excludeArchived", "onlyArchived", "include", "exclude");
            for (String key : Arrays.asList("contacts", "nonContacts", "groups", "channels", "bots", "excludeMuted", "excludeRead", "excludeArchived", "onlyArchived")) {
                if (!(object.get(key) instanceof Boolean)) throw new JSONException("Boolean rule required");
            }
            if (object.getBoolean("onlyArchived") && object.getBoolean("excludeArchived")) throw new JSONException("Conflicting archive rules");
            peers(object.get("include")); peers(object.get("exclude"));
        } else if (name.equals("title")) {
            keys(object, "text", "noAnimate", "entities");
            if (!(object.get("text") instanceof String) || object.getString("text").trim().isEmpty()
                    || object.getString("text").length() > 256 || !(object.get("noAnimate") instanceof Boolean)) throw new JSONException("Invalid title");
            String text = object.getString("text");
            JSONArray entities = object.getJSONArray("entities");
            if (entities.length() > 256) throw new JSONException("Too many title entities");
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entity = entities.getJSONObject(i); keys(entity, "type", "offset", "length", "id", "url", "language");
                Object type = entity.get("type");
                if (!Arrays.asList("bold", "italic", "underline", "strike", "spoiler", "code", "pre", "url", "textUrl", "mention", "mentionName", "customEmoji", "blockquote", "expandableBlockquote", "hashtag", "cashtag", "botCommand", "email", "phone", "bankCard").contains(type)) throw new JSONException("Unknown title entity");
                int offset = integer(entity.get("offset"), 0, text.length());
                int length = integer(entity.get("length"), 1, text.length() - offset);
                if (offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                        || offset + length < text.length() && Character.isLowSurrogate(text.charAt(offset + length))) throw new JSONException("Entity splits a surrogate");
                if (type.equals("customEmoji") || type.equals("mentionName")) decimal(entity.get("id"), false);
                if (type.equals("textUrl") && (!(entity.get("url") instanceof String) || entity.getString("url").length() > 2048)) throw new JSONException("Invalid entity URL");
                if (type.equals("pre") && (!(entity.get("language") instanceof String) || entity.getString("language").length() > 128)) throw new JSONException("Invalid entity language");
            }
        }
    }

    private static void validate(JSONObject root) throws JSONException {
        keys(root, "v", "account", "folders", "order");
        if (!(root.get("v") instanceof Number) || ((Number) root.get("v")).doubleValue() != 1) throw new JSONException("Unsupported schema");
        decimal(root.get("account"), false);
        JSONArray folders = root.getJSONArray("folders");
        if (folders.length() > 2048) throw new JSONException("Too many folders");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.getJSONObject(i);
            keys(folder, "id", "fields", "deleted"); uuid(folder.get("id"));
            if (!seen.add(folder.getString("id"))) throw new JSONException("Duplicate folder UUID");
            JSONObject fields = folder.getJSONObject("fields");
            keys(fields, "title", "appearance", "rules", "pinned");
            for (String name : FIELDS) {
                if (fields.has(name)) {
                    checkRegister(fields.getJSONObject(name));
                    definition(name, fields.getJSONObject(name).get("value"));
                }
                else if (!folder.has("deleted")) throw new JSONException("Incomplete live folder");
            }
            if (folder.has("deleted")) {
                checkRegister(folder.getJSONObject("deleted"));
                if (!Boolean.TRUE.equals(folder.getJSONObject("deleted").get("value"))) throw new JSONException("Invalid tombstone");
            }
        }
        if (root.has("order")) {
            JSONObject order = root.getJSONObject("order"); checkRegister(order);
            JSONArray ids = order.getJSONArray("value"); seen.clear();
            if (ids.length() > 4096) throw new JSONException("Order too large");
            for (int i = 0; i < ids.length(); i++) {
                Object value = ids.get(i);
                if (!(value instanceof String) || !seen.add((String) value)) throw new JSONException("Invalid order reference");
                String id = (String) value;
                if (id.startsWith("local:")) uuid(id.substring(6));
                else if (id.startsWith("remote:")) decimal(id.substring(7), true);
                else throw new JSONException("Unknown order reference");
            }
        }
        if (canonical(root).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("Document too large");
    }

    private static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(canonical(value)); }

    public static String canonical(Object value) throws JSONException {
        if (value == JSONObject.NULL) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if ((Double.isNaN(n) || Double.isInfinite(n)) || n != Math.rint(n) || Math.abs(n) > Integer.MAX_VALUE) throw new JSONException("Invalid numeric value");
            return Long.toString(((Number) value).longValue());
        }
        if (value instanceof String) {
            String text = (String) value; StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw new JSONException("Invalid surrogate");
                    out.append(c).append(text.charAt(++i));
                } else if (Character.isLowSurrogate(c)) throw new JSONException("Invalid surrogate");
                else if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(canonical(array.get(i))); }
            return out.append(']').toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; TreeSet<String> names = new TreeSet<>();
            Iterator<String> keys = object.keys(); while (keys.hasNext()) names.add(keys.next());
            StringBuilder out = new StringBuilder("{"); boolean first = true;
            for (String name : names) { if (!first) out.append(','); first = false; out.append(canonical(name)).append(':').append(canonical(object.get(name))); }
            return out.append('}').toString();
        }
        throw new JSONException("Unsupported value");
    }
}
