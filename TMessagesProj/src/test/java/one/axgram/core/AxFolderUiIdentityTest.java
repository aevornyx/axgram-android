package one.axgram.core;

import one.axgram.core.folders.*;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=android.app.Application.class)
public class AxFolderUiIdentityTest {
    private MessagesController.DialogFilter folder(int id, String title) {
        var filter=new MessagesController.DialogFilter();filter.id=id;filter.axSyncUuid=UUID.randomUUID().toString();
        filter.name=title;filter.flags=MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;return filter;
    }
    @Test public void missingUiUuidRecoversThePersistedIdentityAfterReload() throws Exception {
        var original=folder(1000,"QA");
        var state=AxFolderReplica.fresh(42).captureNative(Arrays.asList(original),dialog->null);
        var loaded=state.nativeFolders().get(0);loaded.axSyncUuid=null;
        assertEquals(1,AxFolderUiIdentity.restoreMissing(state,Arrays.asList(loaded),dialog->null));
        assertEquals(original.axSyncUuid,loaded.axSyncUuid);
        assertArrayEquals(state.encode(),state.captureNative(Arrays.asList(loaded),dialog->null).encode());
    }
    @Test public void reusedIdOrChangedSettingsCannotInheritAnOldIdentity() throws Exception {
        var original=folder(1000,"Original");
        var state=AxFolderReplica.fresh(42).captureNative(Arrays.asList(original),dialog->null);
        var replaced=folder(1000,"Different");String replacement=replaced.axSyncUuid;
        assertEquals(0,AxFolderUiIdentity.restoreMissing(state,Arrays.asList(replaced),dialog->null));
        assertEquals(replacement,replaced.axSyncUuid);
        replaced.axSyncUuid=null;
        try{AxFolderUiIdentity.restoreMissing(state,Arrays.asList(replaced),dialog->null);fail();}catch(JSONException expected){}
        assertNull(replaced.axSyncUuid);
    }
    @Test public void validationFailureDoesNotPartiallyRebindOtherFolders() throws Exception {
        var state=AxFolderReplica.fresh(42).captureNative(Arrays.asList(folder(1000,"First"),folder(1001,"Second")),dialog->null);
        var loaded=new ArrayList<>(state.nativeFolders());for(var f:loaded)f.axSyncUuid=null;
        loaded.get(1).color=7;
        try{AxFolderUiIdentity.restoreMissing(state,loaded,dialog->null);fail();}catch(JSONException expected){}
        for(var f:loaded)assertNull(f.axSyncUuid);
    }
}
