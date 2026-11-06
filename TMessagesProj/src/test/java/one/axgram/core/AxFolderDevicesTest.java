package one.axgram.core.folders;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderDevicesTest {
    private static final String CURRENT = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";
    private JSONObject device(String id) throws Exception {
        return new JSONObject().put("id",id).put("label","AxGram Windows").put("expires",1800000000L);
    }
    private JSONObject list() throws Exception {
        return new JSONObject().put("currentDevice",CURRENT).put("canManage",true)
                .put("devices",new JSONArray().put(device(CURRENT)).put(device(OTHER)));
    }
    private void rejected(JSONObject value) throws Exception {
        try { AxFolderAuthClient.parseDevices(value,CURRENT); fail("Malformed devices accepted"); }
        catch (IOException | org.json.JSONException expected) { }
    }
    @Test public void validListMarksCurrentDeviceAndIsImmutable() throws Exception {
        var devices=AxFolderAuthClient.parseDevices(list(),CURRENT);
        assertEquals(CURRENT,devices.current); assertTrue(devices.canManage); assertEquals(2,devices.devices.size());
        try { devices.devices.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void rejectsWrongAccountContextMissingCurrentAndRepeatedIds() throws Exception {
        rejected(list().put("currentDevice",OTHER));
        rejected(list().put("devices",new JSONArray().put(device(OTHER))));
        rejected(list().put("devices",new JSONArray().put(device(CURRENT)).put(device(CURRENT))));
        rejected(list().put("devices",new JSONArray()));
    }
    @Test public void rejectsUnexpectedCredentialFieldsAndUnboundedLists() throws Exception {
        rejected(list().put("token","secret"));
        rejected(list().put("devices",new JSONArray().put(device(CURRENT).put("token_hash","secret"))));
        var array=new JSONArray(); for(int i=0;i<33;i++)array.put(device(CURRENT));
        rejected(list().put("devices",array));
    }
    @Test public void rejectsInvalidLabelsExpiryAndPermissionTypes() throws Exception {
        rejected(list().put("canManage","true"));
        for(Object label:new Object[]{"", "bad\nlabel", 42, JSONObject.NULL, new String(new char[81]).replace('\0','a')})
            rejected(list().put("devices",new JSONArray().put(device(CURRENT).put("label",label))));
        for(Object expiry:new Object[]{0,-1,1.25,"1800000000",Long.MAX_VALUE})
            rejected(list().put("devices",new JSONArray().put(device(CURRENT).put("expires",expiry))));
    }
    @Test public void invalidRevokeTargetNeverReachesNetwork() throws Exception {
        try(var credential=AxFolderAuthClient.Credential.decode(new JSONObject().put("account","12345").put("workspace",CURRENT)
                .put("device",CURRENT).put("token",new String(new char[43]).replace('\0','a')).put("key",new String(new char[43]).replace('\0','A'))
                .toString().getBytes(StandardCharsets.UTF_8),12345)) {
            var client=AxFolderAuthClient.loopbackLab(new java.net.URL("http://127.0.0.1:9"));
            for(String target:new String[]{"../snapshot",OTHER+"?x=1","", "not-a-uuid"}) {
                try { client.revokeDevice(credential,target); fail(); } catch(IOException expected) { assertFalse(expected.getMessage().contains("connect")); }
            }
        }
    }
}
