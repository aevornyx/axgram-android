package one.axgram.core;

import one.axgram.core.folders.AxFolderDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderDocumentTest {
    private static final String DEVICE = "00000000-0000-0000-0000-000000000001";

    private JSONObject vectors() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/axgram-folder-sync-v1.json")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }

    private AxFolderDocument read(JSONObject value) throws Exception {
        return AxFolderDocument.parse(AxFolderDocument.canonical(value).getBytes(StandardCharsets.UTF_8), 12345);
    }

    @Test public void sharedVectorsConvergeInBothDirectionsAndRemainIdempotent() throws Exception {
        JSONArray cases = vectors().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject test = cases.getJSONObject(i);
            String name = test.getString("name");
            AxFolderDocument a = read(test.getJSONObject("a")), b = read(test.getJSONObject("b"));
            byte[] expected = test.getString("expectedWire").getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(name, expected, read(test.getJSONObject("expected")).encode());
            AxFolderDocument merged = a.merge(b);
            assertArrayEquals(name, expected, merged.encode());
            assertArrayEquals(name, expected, b.merge(a).encode());
            assertArrayEquals(name, expected, merged.merge(a).merge(b).encode());
            assertArrayEquals(name, a.encode(), a.merge(a).encode());
            JSONArray order = test.getJSONArray("visibleOrder");
            assertEquals(name, order.length(), merged.visibleOrder().size());
            for (int j = 0; j < order.length(); j++) assertEquals(name, order.getString(j), merged.visibleOrder().get(j));
        }
    }

    @Test public void sharedInvalidDocumentsAreRejectedWithoutChangingOriginal() throws Exception {
        JSONArray invalid = vectors().getJSONArray("invalid");
        for (int i = 0; i < invalid.length(); i++) {
            JSONObject test = invalid.getJSONObject(i);
            try { read(test.getJSONObject("document")); fail(test.getString("name")); }
            catch (org.json.JSONException expected) { }
        }
    }

    @Test public void mergingThreeReplicasIsAssociative() throws Exception {
        JSONArray cases = vectors().getJSONArray("cases");
        AxFolderDocument initial = read(cases.getJSONObject(1).getJSONObject("a"));
        for (int i = 0; i < cases.length(); i++) {
            AxFolderDocument a = read(cases.getJSONObject(i).getJSONObject("a"));
            AxFolderDocument b = read(cases.getJSONObject(i).getJSONObject("b"));
            assertArrayEquals(a.merge(b).merge(initial).encode(), a.merge(b.merge(initial)).encode());
        }
    }

    @Test public void localEditsUseMaximumReceivedClockAndAreDefensivelyCopied() throws Exception {
        JSONObject test = vectors().getJSONArray("cases").getJSONObject(4);
        AxFolderDocument doc = read(test.getJSONObject("a"));
        String id = test.getJSONObject("a").getJSONArray("folders").getJSONObject(0).getString("id");
        JSONObject title = new JSONObject().put("text", "Changed").put("entities", new JSONArray()).put("noAnimate", false);
        AxFolderDocument edited = doc.edit(id, new JSONObject().put("title", title), DEVICE);
        title.put("text", "Mutated");
        assertEquals(doc.maxClock() + 1, edited.maxClock());
        assertEquals("Changed", edited.folder(id).getJSONObject("fields").getJSONObject("title").getJSONObject("value").getString("text"));
        edited.folder(id).put("deleted", true);
        assertFalse(edited.folder(id).has("deleted"));
        AxFolderDocument deleted = edited.delete(id, DEVICE);
        assertTrue(deleted.merge(edited).visibleOrder().isEmpty());
        try { deleted.edit(id, new JSONObject().put("title", title), DEVICE); fail(); }
        catch (org.json.JSONException expected) { }
    }

    @Test public void wrongAccountTrailingDataAndNoncanonicalWireAreRejected() throws Exception {
        byte[] valid = AxFolderDocument.empty(12345).encode();
        for (byte[] bytes : new byte[][]{
                (new String(valid, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8),
                (new String(valid, StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8),
                "{account:'12345',folders:[],v:1}".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0xc0, (byte) 0xaf}}) {
            try { AxFolderDocument.parse(bytes, 12345); fail(); }
            catch (org.json.JSONException expected) { }
        }
        try { AxFolderDocument.parse(valid, 54321); fail(); }
        catch (org.json.JSONException expected) { }
    }
}
