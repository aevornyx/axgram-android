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
public class AxFolderConcurrentMergeTest {
    @Test public void threeOfflineDevicesConvergeForEveryDeliveryOrderAgainstIndependentReference() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/axgram-folder-sync-conflicts-v1.json")) {
            assertNotNull(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            fixture = output.toByteArray();
        }
        JSONArray cases = new JSONObject(new String(fixture, StandardCharsets.UTF_8)).getJSONArray("cases");
        for (int n = 0; n < cases.length(); n++) {
            JSONObject test = cases.getJSONObject(n);
            String name = test.getString("name");
            byte[] expected = test.getString("expectedWire").getBytes(StandardCharsets.UTF_8);
            AxFolderDocument[] docs = new AxFolderDocument[3];
            for (int i = 0; i < 3; i++) docs[i] = AxFolderDocument.parse(AxFolderDocument.canonical(
                    test.getJSONArray("replicas").getJSONObject(i)).getBytes(StandardCharsets.UTF_8), 12345);
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
                if (a == b) continue;
                int c = 3 - a - b;
                AxFolderDocument merged = docs[a].merge(docs[b]).merge(docs[c]);
                assertArrayEquals(name, expected, merged.encode());
                assertArrayEquals(name, expected, docs[a].merge(docs[b].merge(docs[c])).encode());
                assertArrayEquals(name, expected, merged.merge(docs[c]).merge(docs[b]).merge(docs[a]).encode());
                assertArrayEquals(name, expected, AxFolderDocument.parse(merged.encode(), 12345).encode());
                JSONArray order = test.getJSONArray("visibleOrder");
                assertEquals(name, order.length(), merged.visibleOrder().size());
                for (int i = 0; i < order.length(); i++) assertEquals(name, order.getString(i), merged.visibleOrder().get(i));
            }
        }
    }
}
