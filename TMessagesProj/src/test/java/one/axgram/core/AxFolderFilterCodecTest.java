package one.axgram.core;

import one.axgram.core.folders.AxFolderFilterCodec;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderFilterCodecTest {
    @Test public void archiveRuleRejectsChatsFromTheOtherListBeforeTypeMatching() {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        TLRPC.Dialog dialog = new TLRPC.TL_dialog();
        dialog.id = 42;
        filter.flags = MessagesController.DIALOG_FILTER_FLAG_ONLY_ARCHIVED;
        dialog.folder_id = 0;
        assertFalse(filter.includesDialog(null, dialog.id, dialog));
        filter.flags = MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED;
        dialog.folder_id = 1;
        assertFalse(filter.includesDialog(null, dialog.id, dialog));
    }

    @Test public void explicitInclusionsAndExclusionsKeepTheirArchivePrecedence() {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        TLRPC.Dialog dialog = new TLRPC.TL_dialog();
        dialog.id = 42;
        filter.flags = MessagesController.DIALOG_FILTER_FLAG_ONLY_ARCHIVED;
        filter.alwaysShow.add(dialog.id);
        assertTrue(filter.includesDialog(null, dialog.id, dialog));
        filter.neverShow.add(dialog.id);
        assertFalse(filter.includesDialog(null, dialog.id, dialog));
    }

    private MessagesController.DialogFilter fixture() {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 1000; filter.name = "🚀 Work"; filter.emoticon = "📁"; filter.color = -1;
        filter.title_noanimate = true;
        filter.flags = 31 | 32 | 64 | 256;
        filter.alwaysShow.addAll(Arrays.asList(3L, -7L, -8L)); filter.neverShow.add(4L);
        filter.pinnedDialogs.put(-8L, 0); filter.pinnedDialogs.put(3L, 1);
        TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
        emoji.document_id = 1234567890123L; emoji.offset = 0; emoji.length = 2;
        filter.entities.add(emoji);
        return filter;
    }

    private String peerType(long dialog) { return dialog == -7 ? "chat" : dialog == -8 ? "channel" : null; }

    @Test public void roundtripPreservesRulesPinsTitleEntitiesAndUnsetColor() throws Exception {
        MessagesController.DialogFilter original = fixture();
        JSONObject values = AxFolderFilterCodec.exportFilter(original, this::peerType);
        MessagesController.DialogFilter imported = AxFolderFilterCodec.importFilter(1007, values);
        assertEquals(1007, imported.id); assertEquals(original.name, imported.name);
        assertEquals(original.emoticon, imported.emoticon); assertEquals(-1, imported.color);
        assertEquals(original.flags, imported.flags); assertTrue(imported.title_noanimate);
        assertEquals(0, imported.pinnedDialogs.get(-8)); assertEquals(1, imported.pinnedDialogs.get(3));
        assertEquals(2, imported.pinnedDialogs.size());
        assertTrue(imported.alwaysShow.containsAll(original.alwaysShow));
        assertEquals(original.neverShow, imported.neverShow);
        assertEquals(1234567890123L, ((TLRPC.TL_messageEntityCustomEmoji) imported.entities.get(0)).document_id);
        assertEquals(2, imported.entities.get(0).length);
        assertEquals(one.axgram.core.folders.AxFolderDocument.canonical(values),
                one.axgram.core.folders.AxFolderDocument.canonical(AxFolderFilterCodec.exportFilter(imported, this::peerType)));
    }

    @Test public void missingPeerMetadataIsNotGuessed() throws Exception {
        try { AxFolderFilterCodec.exportFilter(fixture(), id -> null); fail(); }
        catch (JSONException expected) { }
    }

    @Test public void serverFoldersAreRejectedAndSecretChatsAreExcludedFromSharedRules() throws Exception {
        MessagesController.DialogFilter filter = fixture(); filter.id = 2;
        try { AxFolderFilterCodec.exportFilter(filter, this::peerType); fail(); }
        catch (JSONException expected) { }
        filter.id = 1000; filter.neverShow.add(DialogObject.makeEncryptedDialogId(77));
        JSONObject shared = AxFolderFilterCodec.exportFilter(filter, this::peerType);
        assertFalse(shared.toString().contains(Long.toString(DialogObject.makeEncryptedDialogId(77))));
    }

    @Test public void importingCannotOverwriteServerFolderId() throws Exception {
        JSONObject values = AxFolderFilterCodec.exportFilter(fixture(), this::peerType);
        try { AxFolderFilterCodec.importFilter(2, values); fail(); }
        catch (JSONException expected) { }
    }

    @Test public void differentPeerTypesCannotSilentlyCollapseToOneNativeDialogId() throws Exception {
        JSONObject values = AxFolderFilterCodec.exportFilter(fixture(), this::peerType);
        values.getJSONObject("rules").getJSONArray("exclude")
                .put(new JSONObject().put("type", "channel").put("id", "7"));
        try { AxFolderFilterCodec.importFilter(1000, values); fail(); }
        catch (JSONException expected) { }
    }
}
