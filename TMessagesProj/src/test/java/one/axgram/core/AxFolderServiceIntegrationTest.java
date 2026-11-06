package one.axgram.core;

import one.axgram.core.folders.AxFolderLocalStore;
import one.axgram.core.folders.AxFolderReplica;
import one.axgram.core.folders.AxFolderSync;
import one.axgram.core.folders.AxFolderTransport;
import one.axgram.core.folders.AxFolderSqlStore;
import one.axgram.core.folders.AxFolderAuthClient;
import org.telegram.messenger.MessagesController;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderServiceIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String X = "10000000-0000-0000-0000-000000000001";
    private static final String Y = "10000000-0000-0000-0000-000000000002";

    private AxFolderLocalStore device(String uuid, String title) throws Exception {
        AxFolderLocalStore store = new AxFolderLocalStore(temporary.newFolder(), 12345);
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 1000; filter.name = title; filter.flags = 31; filter.color = -1;
        store.save(store.load().capture(Collections.singletonList(new AxFolderReplica.LocalFolder(uuid, filter)), id -> null));
        return store;
    }

    private AxFolderAuthClient.Credential login(AxFolderAuthClient client, URL base, String label, boolean nativeConfirmation) throws Exception {
        AxFolderAuthClient.Flow flow = nativeConfirmation ? client.startNative(12345, label) : client.start(12345, label);
        assertNull(client.poll(flow));
        String state = null;
        for (String pair : flow.authorizeUrl.getQuery().split("&")) {
            String[] item = pair.split("=", 2);
            if (item[0].equals("state")) state = URLDecoder.decode(item[1], "UTF-8");
        }
        assertNotNull(state);
        String accepted = base + "/v1/auth/callback?state=" + URLEncoder.encode(state, "UTF-8") + "&code=lab-code";
        if (nativeConfirmation) {
            assertEquals("tg://resolve?domain=oauth&startapp=" + "L".repeat(24), flow.confirmationUrl);
            client.completeNative(flow, accepted);
            try { client.completeNative(flow, accepted); fail("Native code replay accepted"); }
            catch (AxFolderAuthClient.LoginRejected expected) { }
        } else {
            HttpURLConnection callback = (HttpURLConnection) new URL(accepted).openConnection();
            try { assertEquals(200, callback.getResponseCode()); }
            finally { callback.disconnect(); }
        }
        AxFolderAuthClient.Credential credential = client.poll(flow);
        assertNotNull(credential); client.acknowledge(flow);
        return credential;
    }

    @Test public void nativeReplicasExchangeEncryptedFoldersThroughThePythonService() throws Exception {
        String endpoint = System.getenv("AX_FOLDER_LAB_ENDPOINT"), workspace = System.getenv("AX_FOLDER_LAB_WORKSPACE");
        String firstToken = System.getenv("AX_FOLDER_LAB_FIRST"), secondToken = System.getenv("AX_FOLDER_LAB_SECOND");
        String otherToken = System.getenv("AX_FOLDER_LAB_OTHER");
        assumeNotNull(endpoint, workspace, firstToken, secondToken, otherToken);
        URL url = new URL(endpoint);
        URL base = new URL(endpoint.substring(0, endpoint.length() - "/v1/snapshot".length()));
        AxFolderAuthClient auth = AxFolderAuthClient.loopbackLab(base);
        AxFolderAuthClient.Credential firstLogin = login(auth, base, "Phone lab", true), secondLogin = login(auth, base, "PC lab", false);
        assertEquals(workspace, firstLogin.workspace); assertEquals(workspace, secondLogin.workspace);
        assertArrayEquals(firstLogin.encryptionKey(), secondLogin.encryptionKey());
        AxFolderTransport first = AxFolderTransport.loopbackLab(url, firstLogin.token()), second = AxFolderTransport.loopbackLab(url, secondLogin.token());
        AxFolderLocalStore b = device(Y, "Друзья");
        AxFolderSqlStoreTest.LabDatabase nativeDatabase = new AxFolderSqlStoreTest.LabDatabase();
        nativeDatabase.execute("INSERT INTO dialog_filter_neko VALUES(1000,1,0,31,?,NULL,-1,NULL,0)", "Работа 🛠");
        nativeDatabase.execute("INSERT INTO ax_folder_identity VALUES(12345,1000,?)", X);
        AxFolderSqlStore a = new AxFolderSqlStore(nativeDatabase, 12345, () -> 12345, id -> null);
        byte[] key = firstLogin.encryptionKey();
        try (AxFolderSqlStoreTest.LabDatabase closedWithTest = nativeDatabase;
             AxFolderAuthClient.Credential closeFirst = firstLogin; AxFolderAuthClient.Credential closeSecond = secondLogin;
             AxFolderSync desktopShape = new AxFolderSync(a, first, workspace, 12345, key);
             AxFolderSync androidShape = new AxFolderSync(b, second, workspace, 12345, key)) {
            assertEquals(0, first.read().revision);
            assertEquals(1, desktopShape.run().revision);
            assertEquals(2, androidShape.run().revision);
            assertEquals(2, desktopShape.run().revision);
            assertArrayEquals(a.load().document.encode(), b.load().document.encode());
            assertEquals(2, a.load().nativeFolders().size());
            assertEquals(3, a.nativeSnapshot().size());
            assertEquals(1000, a.load().localFolders().stream().filter(f -> X.equals(f.uuid)).findFirst().get().filter.id);
            assertEquals(1000, b.load().localFolders().stream().filter(f -> Y.equals(f.uuid)).findFirst().get().filter.id);

            List<AxFolderReplica.LocalFolder> surviving = new ArrayList<>();
            for (AxFolderReplica.LocalFolder folder : a.load().localFolders()) if (!X.equals(folder.uuid)) surviving.add(folder);
            AxFolderReplica previous = a.load();
            assertTrue(a.compareAndSave(previous, previous.capture(surviving, id -> null)));
            List<AxFolderReplica.LocalFolder> offline = b.load().localFolders();
            for (AxFolderReplica.LocalFolder folder : offline) if (X.equals(folder.uuid)) folder.filter.name = "Правка без сети";
            b.save(b.load().capture(offline, id -> null));
            desktopShape.run(); androidShape.run(); desktopShape.run();
            assertEquals(1, a.load().nativeFolders().size());
            assertEquals(2, a.nativeSnapshot().size());
            assertTrue(b.load().document.folder(X).has("deleted"));
            assertArrayEquals(a.load().document.encode(), b.load().document.encode());
            long settled = first.read().revision;
            assertEquals(settled, androidShape.run().revision);
            assertEquals(settled, desktopShape.run().revision);
            assertEquals(0, AxFolderTransport.loopbackLab(url, otherToken).read().revision);
            assertFalse(new String(first.read().envelope(), java.nio.charset.StandardCharsets.UTF_8).contains("Друзья"));
            byte[] before = b.load().encode();
            try (AxFolderSync wrongKey = new AxFolderSync(b, second, workspace, 12345, new byte[32])) {
                try { wrongKey.run(); fail(); } catch (java.security.GeneralSecurityException expected) { }
            }
            assertArrayEquals(before, b.load().encode());
        }
    }
}
