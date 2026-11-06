package one.axgram.core;

import one.axgram.core.folders.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=android.app.Application.class)
public class AxFolderSecretRulesTest {
    private static final String FOLDER="10000000-0000-0000-0000-000000000001", OTHER="10000000-0000-0000-0000-000000000002";
    private static final String REMOTE="20000000-0000-0000-0000-000000000001";
    private static final long FIRST=DialogObject.makeEncryptedDialogId(77), SECOND=DialogObject.makeEncryptedDialogId(88), EXCLUDED=DialogObject.makeEncryptedDialogId(99);
    private MessagesController.DialogFilter folder() {
        var f=new MessagesController.DialogFilter();f.id=1000;f.axSyncUuid=FOLDER;f.name="Private";f.flags=31;f.color=-1;
        f.alwaysShow.addAll(Arrays.asList(3L,FIRST,4L,SECOND));f.neverShow.add(EXCLUDED);
        f.pinnedDialogs.put(3L,0);f.pinnedDialogs.put(FIRST,1);f.pinnedDialogs.put(4L,2);f.pinnedDialogs.put(SECOND,3);
        return f;
    }
    private AxFolderReplica state(MessagesController.DialogFilter f)throws Exception {
        return AxFolderReplica.fresh(1).captureNative(Collections.singletonList(f),id->null);
    }
    private JSONObject json(byte[] bytes)throws Exception{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private byte[] bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    private JSONObject title(AxFolderReplica state,String name)throws Exception {
        var value=new JSONObject(state.document.folder(FOLDER).getJSONObject("fields").getJSONObject("title").getJSONObject("value").toString());
        return value.put("text",name);
    }
    private void assertPrivate(MessagesController.DialogFilter f) {
        assertTrue(f.alwaysShow.contains(FIRST));assertTrue(f.alwaysShow.contains(SECOND));assertTrue(f.neverShow.contains(EXCLUDED));
        assertEquals(1,f.pinnedDialogs.get(FIRST));assertEquals(3,f.pinnedDialogs.get(SECOND));
    }
    @Test public void privateRulesRoundtripLocallyButNeverEnterSharedDocumentOrOtherDevice()throws Exception {
        var state=state(folder());assertEquals(2,json(state.encode()).getInt("v"));
        var restored=AxFolderReplica.decode(state.encode(),1);assertPrivate(restored.nativeFolders().get(0));
        String shared=new String(state.document.encode(),StandardCharsets.UTF_8);
        for(long id:new long[]{FIRST,SECOND,EXCLUDED})assertFalse(shared.contains(Long.toString(id)));
        assertFalse(shared.contains("localRules"));assertEquals(1,json(state.document.encode()).getInt("v"));
        var other=AxFolderReplica.fresh(1).merge(state.document).nativeFolders().get(0);
        assertEquals(Arrays.asList(3L,4L),other.alwaysShow);assertTrue(other.neverShow.isEmpty());assertEquals(2,other.pinnedDialogs.size());
    }
    @Test public void remoteRenameAndPinReorderKeepPrivateSlotsAndSharedPinOrder()throws Exception {
        var state=state(folder());
        var pins=new JSONArray().put(new JSONObject().put("type","user").put("id","4")).put(new JSONObject().put("type","user").put("id","3"));
        var remote=state.document.edit(FOLDER,new JSONObject().put("title",title(state,"Remote")).put("pinned",pins),REMOTE);
        var next=state.merge(remote);var f=next.nativeFolders().get(0);
        assertEquals("Remote",f.name);assertPrivate(f);assertEquals(0,f.pinnedDialogs.get(4L));assertEquals(2,f.pinnedDialogs.get(3L));
        assertEquals(AxFolderDocument.canonical(remote.folder(FOLDER).getJSONObject("fields").getJSONObject("pinned").get("value")),
                AxFolderDocument.canonical(AxFolderFilterCodec.exportFilter(f,id->null).get("pinned")));
    }
    @Test public void privateOnlyEditsDoNotChangeSharedClocksAndStaleRenameDoesNotRevertThem()throws Exception {
        var before=state(folder());var edited=before.nativeFolders().get(0);long added=DialogObject.makeEncryptedDialogId(123);
        edited.alwaysShow.add(added);var after=before.editedNativeFilter(edited,false,false,id->null);
        assertArrayEquals(before.document.encode(),after.document.encode());
        var remote=before.merge(before.document.edit(FOLDER,new JSONObject().put("appearance",new JSONObject().put("emoji","⭐").put("color",2)),REMOTE));
        var committed=remote.replayUiChanges(before,after);
        var rename=before.nativeFolders().get(0);rename.name="Later local rename";
        var finalState=committed.replayUiChanges(before,before.editedNativeFilter(rename,false,false,id->null));
        var f=finalState.nativeFolders().get(0);assertTrue(f.alwaysShow.contains(added));assertEquals("Later local rename",f.name);assertEquals(2,f.color);
    }
    @Test public void explicitPrivateRemovalSurvivesSerializationAndDoesNotReappearInUi()throws Exception {
        var old=folder();var before=state(old);var cleared=before.nativeFolders().get(0);
        cleared.alwaysShow.removeIf(DialogObject::isEncryptedDialog);cleared.neverShow.clear();
        cleared.pinnedDialogs.delete(FIRST);cleared.pinnedDialogs.delete(SECOND);
        var after=before.editedNativeFilter(cleared,false,false,id->null);assertArrayEquals(before.document.encode(),after.document.encode());
        var restored=AxFolderReplica.decode(after.encode(),1);
        var applied=new AxFolderUiProjection(restored,Collections.singletonList(old)).applyIfUnchanged(Collections.singletonList(old));
        assertFalse(applied.get(0).alwaysShow.contains(FIRST));assertTrue(applied.get(0).neverShow.isEmpty());
        assertEquals(2,applied.get(0).pinnedDialogs.size());
    }
    @Test public void deletionAndNativeIdReuseDoNotAttachPrivateRulesToAnotherUuid()throws Exception {
        var before=state(folder());var values=AxFolderFilterCodec.exportFilter(folder(),id->null);
        var changed=before.document.delete(FOLDER,REMOTE).edit(OTHER,values,REMOTE);
        var result=before.merge(changed);var f=result.nativeFolders().get(0);
        assertEquals(1000,f.id);assertEquals(OTHER,f.axSyncUuid);assertFalse(f.alwaysShow.contains(FIRST));
        assertEquals(1,json(result.encode()).getInt("v"));
    }
    @Test public void secretMembershipOrderIsASetAndSqlIterationCannotChangeIdentity()throws Exception {
        var f=folder();var before=state(f);Collections.reverse(f.alwaysShow);
        assertArrayEquals(before.encode(),before.captureNative(Collections.singletonList(f),id->null).encode());
        f.axSyncUuid=null;assertEquals(1,AxFolderUiIdentity.restoreMissing(before,Collections.singletonList(f),id->null));
        f.axSyncUuid=null;f.neverShow.add(DialogObject.makeEncryptedDialogId(111));
        try{AxFolderUiIdentity.restoreMissing(before,Collections.singletonList(f),id->null);fail();}catch(JSONException expected){}
        assertNull(f.axSyncUuid);
    }
    @Test public void malformedLocalSidecarIsRejectedAndCannotBeInjectedIntoSharedDocument()throws Exception {
        var before=state(folder());
        var bad=json(before.encode());bad.getJSONObject("localRules").getJSONObject(FOLDER).put("exclude",new JSONArray().put("3"));
        try{AxFolderReplica.decode(bytes(bad),1);fail();}catch(JSONException expected){}
        bad=json(before.encode());bad.getJSONObject("localRules").getJSONObject(FOLDER).put("exclude",new JSONArray().put(Long.toString(0x4000000000000000L)));
        try{AxFolderReplica.decode(bytes(bad),1);fail();}catch(JSONException expected){}
        bad=json(before.encode());var local=bad.getJSONObject("localRules");local.put(OTHER,local.remove(FOLDER));
        try{AxFolderReplica.decode(bytes(bad),1);fail();}catch(JSONException expected){}
        var wire=json(before.document.encode()).put("localRules",json(before.encode()).get("localRules"));
        try{AxFolderDocument.parse(bytes(wire),1);fail();}catch(JSONException expected){}
    }
    @Test public void ordinaryStateKeepsVersionOneAndEachAndroidRetainsItsOwnPrivateRules()throws Exception {
        var a=state(folder());var other=folder();other.alwaysShow.remove(FIRST);other.pinnedDialogs.delete(FIRST);
        long otherSecret=DialogObject.makeEncryptedDialogId(999);other.alwaysShow.add(otherSecret);other.pinnedDialogs.put(otherSecret,1);
        var b=state(other).merge(a.document);var f=b.nativeFolders().get(0);
        assertTrue(f.alwaysShow.contains(otherSecret));assertFalse(f.alwaysShow.contains(FIRST));assertTrue(a.nativeFolders().get(0).alwaysShow.contains(FIRST));
        var plain=folder();plain.alwaysShow.removeIf(DialogObject::isEncryptedDialog);plain.neverShow.clear();plain.pinnedDialogs.delete(FIRST);plain.pinnedDialogs.delete(SECOND);
        assertEquals(1,json(state(plain).encode()).getInt("v"));
    }

    @Test public void sharedUserPeerCannotInjectASecretChatIntoIncludeExcludeOrPins()throws Exception {
        for(String field:new String[]{"include","exclude","pinned"}) {
            var values=AxFolderFilterCodec.exportFilter(folder(),id->null);
            var peer=new JSONObject().put("type","user").put("id",Long.toString(FIRST));
            if(field.equals("pinned")) {
                values.getJSONObject("rules").getJSONArray("include").put(peer);
                values.getJSONArray("pinned").put(peer);
            }else values.getJSONObject("rules").getJSONArray(field).put(peer);
            try{AxFolderFilterCodec.importFilter(1000,values);fail();}catch(JSONException expected){}
        }
    }
}
