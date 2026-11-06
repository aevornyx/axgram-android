package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public final class AxFolderFilterCodec {
    public interface PeerTypes { String type(long dialogId); }
    private static final String VALIDATOR = "00000000-0000-0000-0000-000000000001";
    private static final String[] RULES = {"contacts", "nonContacts", "groups", "channels", "bots", "excludeMuted", "excludeRead", "excludeArchived", "onlyArchived"};
    private AxFolderFilterCodec() { }

    public static JSONObject exportFilter(MessagesController.DialogFilter filter, PeerTypes types) throws JSONException {
        if (filter.id < MessagesController.LOCAL_FILTER_MIN_ID || (filter.flags & ~511) != 0) throw new JSONException("Only ordinary local folders are supported");
        JSONObject rules = new JSONObject();
        for (int i = 0; i < RULES.length; i++) rules.put(RULES[i], (filter.flags & (1 << i)) != 0);
        JSONArray entities = new JSONArray();
        for (TLRPC.MessageEntity entity : filter.entities) entities.put(exportEntity(entity));
        ArrayList<Long> pinned = new ArrayList<>();
        for (int i = 0; i < filter.pinnedDialogs.size(); i++) pinned.add(filter.pinnedDialogs.keyAt(i));
        Collections.sort(pinned, (a, b) -> {
            int order = Integer.compare(filter.pinnedDialogs.get(a), filter.pinnedDialogs.get(b));
            return order == 0 ? Long.compare(a, b) : order;
        });
        ArrayList<Long> include = new ArrayList<>(filter.alwaysShow);
        for (Long id : pinned) if (!include.contains(id)) include.add(id);
        rules.put("include", exportPeers(include, types, true));
        rules.put("exclude", exportPeers(filter.neverShow, types, true));
        JSONObject values = new JSONObject()
                .put("title", new JSONObject().put("text", filter.name).put("noAnimate", filter.title_noanimate).put("entities", entities))
                .put("appearance", new JSONObject().put("emoji", filter.emoticon == null ? "" : filter.emoticon).put("color", filter.color < 0 ? JSONObject.NULL : filter.color))
                .put("rules", rules).put("pinned", exportPeers(pinned, types, false));
        validate(values);
        return values;
    }

    public static MessagesController.DialogFilter importFilter(int localId, JSONObject values) throws JSONException {
        if (localId < MessagesController.LOCAL_FILTER_MIN_ID) throw new JSONException("Local folder ID required");
        validate(values);
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = localId;
        JSONObject title = values.getJSONObject("title"), appearance = values.getJSONObject("appearance"), rules = values.getJSONObject("rules");
        filter.name = title.getString("text"); filter.title_noanimate = title.getBoolean("noAnimate");
        filter.emoticon = appearance.getString("emoji"); filter.color = appearance.isNull("color") ? -1 : appearance.getInt("color");
        for (int i = 0; i < RULES.length; i++) if (rules.getBoolean(RULES[i])) filter.flags |= 1 << i;
        JSONArray entities = title.getJSONArray("entities");
        for (int i = 0; i < entities.length(); i++) filter.entities.add(importEntity(entities.getJSONObject(i)));
        HashMap<Long, String> types = new HashMap<>();
        filter.alwaysShow = importPeers(rules.getJSONArray("include"), types);
        filter.neverShow = importPeers(rules.getJSONArray("exclude"), types);
        ArrayList<Long> pinned = importPeers(values.getJSONArray("pinned"), types);
        for (int i = 0; i < pinned.size(); i++) {
            filter.pinnedDialogs.put(pinned.get(i), i);
            if (!filter.alwaysShow.contains(pinned.get(i))) filter.alwaysShow.add(pinned.get(i));
        }
        return filter;
    }

    private static void validate(JSONObject values) throws JSONException {
        AxFolderDocument.empty(1).edit(VALIDATOR, values, VALIDATOR);
    }

    private static JSONArray exportPeers(ArrayList<Long> ids, PeerTypes types, boolean sorted) throws JSONException {
        ArrayList<JSONObject> peers = new ArrayList<>();
        for (long dialog : ids) {
            if (DialogObject.isEncryptedDialog(dialog)) continue;
            if (dialog == 0 || dialog == Long.MIN_VALUE || DialogObject.isFolderDialogId(dialog)) throw new JSONException("Unsupported dialog");
            String type = dialog > 0 ? "user" : types.type(dialog);
            if (dialog < 0 && !("chat".equals(type) || "channel".equals(type))) throw new JSONException("Peer metadata not loaded");
            peers.add(new JSONObject().put("type", type).put("id", Long.toString(Math.abs(dialog))));
        }
        if (sorted) Collections.sort(peers, (a, b) -> {
            try { return AxFolderDocument.canonical(a).compareTo(AxFolderDocument.canonical(b)); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        });
        JSONArray result = new JSONArray(); for (JSONObject peer : peers) result.put(peer); return result;
    }

    private static ArrayList<Long> importPeers(JSONArray peers, HashMap<Long, String> types) throws JSONException {
        ArrayList<Long> result = new ArrayList<>();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i); String type = peer.getString("type");
            long id = Long.parseLong(peer.getString("id")); long dialog = type.equals("user") ? id : -id;
            if (DialogObject.isEncryptedDialog(dialog) || DialogObject.isFolderDialogId(dialog)) throw new JSONException("Unsupported dialog ID");
            if (types.containsKey(dialog) && !types.get(dialog).equals(type)) throw new JSONException("Peer types collide in native ID space");
            types.put(dialog, type); result.add(dialog);
        }
        return result;
    }

    private static JSONObject exportEntity(TLRPC.MessageEntity entity) throws JSONException {
        String type;
        if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) type = "customEmoji";
        else if (entity instanceof TLRPC.TL_messageEntityMentionName) type = "mentionName";
        else if (entity instanceof TLRPC.TL_messageEntityBold) type = "bold";
        else if (entity instanceof TLRPC.TL_messageEntityItalic) type = "italic";
        else if (entity instanceof TLRPC.TL_messageEntityUnderline) type = "underline";
        else if (entity instanceof TLRPC.TL_messageEntityStrike) type = "strike";
        else if (entity instanceof TLRPC.TL_messageEntitySpoiler) type = "spoiler";
        else if (entity instanceof TLRPC.TL_messageEntityCode) type = "code";
        else if (entity instanceof TLRPC.TL_messageEntityPre) type = "pre";
        else if (entity instanceof TLRPC.TL_messageEntityUrl) type = "url";
        else if (entity instanceof TLRPC.TL_messageEntityTextUrl) type = "textUrl";
        else if (entity instanceof TLRPC.TL_messageEntityMention) type = "mention";
        else if (entity instanceof TLRPC.TL_messageEntityBlockquote) type = entity.collapsed ? "expandableBlockquote" : "blockquote";
        else if (entity instanceof TLRPC.TL_messageEntityHashtag) type = "hashtag";
        else if (entity instanceof TLRPC.TL_messageEntityCashtag) type = "cashtag";
        else if (entity instanceof TLRPC.TL_messageEntityBotCommand) type = "botCommand";
        else if (entity instanceof TLRPC.TL_messageEntityEmail) type = "email";
        else if (entity instanceof TLRPC.TL_messageEntityPhone) type = "phone";
        else if (entity instanceof TLRPC.TL_messageEntityBankCard) type = "bankCard";
        else throw new JSONException("Unsupported title entity");
        JSONObject value = new JSONObject().put("type", type).put("offset", entity.offset).put("length", entity.length);
        if (type.equals("customEmoji")) value.put("id", Long.toString(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
        if (type.equals("mentionName")) value.put("id", Long.toString(((TLRPC.TL_messageEntityMentionName) entity).user_id));
        if (type.equals("textUrl")) value.put("url", entity.url);
        if (type.equals("pre")) value.put("language", entity.language == null ? "" : entity.language);
        return value;
    }

    private static TLRPC.MessageEntity importEntity(JSONObject value) throws JSONException {
        TLRPC.MessageEntity entity;
        switch (value.getString("type")) {
            case "customEmoji":
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                emoji.document_id = Long.parseLong(value.getString("id")); entity = emoji; break;
            case "mentionName":
                TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName();
                mention.user_id = Long.parseLong(value.getString("id")); entity = mention; break;
            case "bold": entity = new TLRPC.TL_messageEntityBold(); break;
            case "italic": entity = new TLRPC.TL_messageEntityItalic(); break;
            case "underline": entity = new TLRPC.TL_messageEntityUnderline(); break;
            case "strike": entity = new TLRPC.TL_messageEntityStrike(); break;
            case "spoiler": entity = new TLRPC.TL_messageEntitySpoiler(); break;
            case "code": entity = new TLRPC.TL_messageEntityCode(); break;
            case "pre": entity = new TLRPC.TL_messageEntityPre(); entity.language = value.getString("language"); break;
            case "url": entity = new TLRPC.TL_messageEntityUrl(); break;
            case "textUrl": entity = new TLRPC.TL_messageEntityTextUrl(); entity.url = value.getString("url"); break;
            case "mention": entity = new TLRPC.TL_messageEntityMention(); break;
            case "blockquote": entity = new TLRPC.TL_messageEntityBlockquote(); break;
            case "expandableBlockquote": entity = new TLRPC.TL_messageEntityBlockquote(); entity.collapsed = true; break;
            case "hashtag": entity = new TLRPC.TL_messageEntityHashtag(); break;
            case "cashtag": entity = new TLRPC.TL_messageEntityCashtag(); break;
            case "botCommand": entity = new TLRPC.TL_messageEntityBotCommand(); break;
            case "email": entity = new TLRPC.TL_messageEntityEmail(); break;
            case "phone": entity = new TLRPC.TL_messageEntityPhone(); break;
            case "bankCard": entity = new TLRPC.TL_messageEntityBankCard(); break;
            default: throw new JSONException("Unsupported title entity");
        }
        entity.offset = value.getInt("offset"); entity.length = value.getInt("length");
        return entity;
    }
}
