package one.axgram.core;

import one.axgram.core.folders.AxFolderTransport;
import one.axgram.core.folders.AxFolderEnvelope;
import one.axgram.core.folders.AxFolderDocument;
import one.axgram.core.folders.AxFolderReplica;
import one.axgram.core.folders.AxFolderLocalStore;
import one.axgram.core.folders.AxFolderSync;
import org.telegram.messenger.MessagesController;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderTransportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SPACE = "00000000-0000-0000-0000-000000000001";
    private static final String X = "10000000-0000-0000-0000-000000000001";
    private static final String Y = "10000000-0000-0000-0000-000000000002";
    private static final String TOKEN = "test_credential_012345678901234567890123456789";
    private ServerSocket server;
    private ExecutorService executor;
    private URL endpoint;
    private volatile String mode = "normal";
    private volatile byte[] snapshot = new byte[0];
    private volatile long revision;
    private volatile byte[] competingSnapshot;
    private volatile Runnable duringRead;
    private final AtomicInteger leaked = new AtomicInteger();

    @Before public void start() throws Exception {
        server = new ServerSocket(); server.bind(new InetSocketAddress("127.0.0.1", 0));
        endpoint = new URL("http://127.0.0.1:" + server.getLocalPort() + "/v1/snapshot");
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    String[] request = line(input).split(" ", 3);
                    TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    String header;
                    while (!(header = line(input)).isEmpty()) {
                        int colon = header.indexOf(':');
                        headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
                    }
                    int length = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
                    byte[] incoming = input.readNBytes(length), body = new byte[0];
                    TreeMap<String, String> response = new TreeMap<>(); int status = 200;
                    if (request[1].equals("/leak")) { leaked.incrementAndGet(); }
                    else if (!("Bearer " + TOKEN).equals(headers.get("Authorization")) || mode.equals("unauthorized")) status = 401;
                    else if (mode.equals("redirect")) {
                        status = 302; response.put("Location", endpoint.toString().replace("/v1/snapshot", "/leak"));
                    } else if (request[0].equals("PUT")) {
                        if (competingSnapshot != null) { snapshot = competingSnapshot; competingSnapshot = null; revision++; }
                        if (!("\"" + revision + "\"").equals(headers.get("If-Match"))) status = 409;
                        else if (!"application/octet-stream".equals(headers.get("Content-Type"))) status = 415;
                        else { snapshot = incoming; revision++; status = 204; }
                        response.put("ETag", "\"" + revision + "\"");
                        if (mode.equals("lostWriteReply")) { mode = "normal"; continue; }
                    } else {
                        if (duringRead != null) { Runnable update = duringRead; duringRead = null; update.run(); }
                        body = mode.equals("large") ? new byte[1024 * 1024 + 1] : snapshot;
                        response.put("ETag", mode.equals("badRevision") ? "\"01\"" : "\"" + revision + "\"");
                        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
                        StringBuilder hex = new StringBuilder();
                        for (byte value : digest) hex.append(String.format("%02x", value & 255));
                        response.put("X-Content-SHA256", mode.equals("badDigest") ? "wrong" : hex.toString());
                    }
                    StringBuilder wire = new StringBuilder("HTTP/1.1 " + status + " Test\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n");
                    for (var entry : response.entrySet()) wire.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                    wire.append("\r\n");
                    socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.US_ASCII));
                    try { socket.getOutputStream().write(body); } catch (IOException disconnected) { }
                } catch (Exception closed) { if (server.isClosed()) break; }
            }
        });
    }

    private static String line(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1) {
            if (value == 10) return bytes.toString("US-ASCII").trim();
            if (bytes.size() > 16384) throw new IOException("Test header too large");
            bytes.write(value);
        }
        throw new IOException("Incomplete test request");
    }

    @After public void stop() throws IOException {
        if (server != null) server.close();
        if (executor != null) executor.shutdownNow();
    }

    @Test public void encryptedSnapshotTravelsOverHttpAndStaleWriteConflicts() throws Exception {
        AxFolderTransport first = AxFolderTransport.loopbackLab(endpoint, TOKEN), second = AxFolderTransport.loopbackLab(endpoint, TOKEN);
        assertEquals(0, first.read().revision);
        byte[] key = new byte[32]; String space = "00000000-0000-0000-0000-000000000001";
        AxFolderDocument doc = AxFolderDocument.empty(12345); byte[] sealed = AxFolderEnvelope.seal(doc, key, space);
        assertEquals(1, first.write(0, sealed));
        AxFolderTransport.Snapshot received = second.read();
        assertEquals(1, received.revision);
        assertArrayEquals(doc.encode(), AxFolderEnvelope.open(received.envelope(), key, space, 12345).encode());
        try { second.write(0, sealed); fail(); } catch (AxFolderTransport.Conflict conflict) { assertEquals(1, conflict.currentRevision); }
        assertArrayEquals(sealed, first.read().envelope());
    }

    @Test public void redirectsDoNotForwardCredentials() throws Exception {
        mode = "redirect";
        try { AxFolderTransport.loopbackLab(endpoint, TOKEN).read(); fail(); }
        catch (AxFolderTransport.HttpFailure error) { assertEquals(302, error.status); }
        assertEquals(0, leaked.get());
    }

    @Test public void malformedRevisionOversizedBodyAndWrongDigestAreRejected() throws Exception {
        AxFolderTransport transport = AxFolderTransport.loopbackLab(endpoint, TOKEN);
        byte[] sealed = AxFolderEnvelope.seal(AxFolderDocument.empty(12345), new byte[32], "00000000-0000-0000-0000-000000000001");
        transport.write(0, sealed);
        for (String failure : new String[]{"badRevision", "large", "badDigest"}) {
            mode = failure;
            try { transport.read(); fail(failure); } catch (IOException expected) { }
        }
    }

    @Test public void unauthorizedResponseIsNotTreatedAsAnEmptyAccount() throws Exception {
        mode = "unauthorized";
        try { AxFolderTransport.loopbackLab(endpoint, TOKEN).read(); fail(); }
        catch (AxFolderTransport.HttpFailure error) { assertEquals(401, error.status); }
    }

    @Test public void productionClientRequiresHttpsAndLabRequiresLiteralLoopback() throws Exception {
        try { new AxFolderTransport(endpoint, TOKEN); fail(); } catch (IllegalArgumentException expected) { }
        try { AxFolderTransport.loopbackLab(new URL("http://example.com/v1/snapshot"), TOKEN); fail(); } catch (IllegalArgumentException expected) { }
        try { new AxFolderTransport(new URL("https://example.com/v1/snapshot?token=x"), TOKEN); fail(); } catch (IllegalArgumentException expected) { }
        try { new AxFolderTransport(new URL("https://example.com/v1/snapshot"), "bad\ncredential"); fail(); } catch (IllegalArgumentException expected) { }
    }

    private AxFolderReplica.LocalFolder folder(String uuid, int id, String title) {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = id; filter.name = title; filter.flags = 31; filter.color = -1;
        return new AxFolderReplica.LocalFolder(uuid, filter);
    }

    private AxFolderLocalStore store(List<AxFolderReplica.LocalFolder> folders) throws Exception {
        AxFolderLocalStore store = new AxFolderLocalStore(temporary.newFolder(), 12345);
        store.save(store.load().capture(folders, id -> null));
        return store;
    }

    private AxFolderSync sync(AxFolderLocalStore store) {
        return new AxFolderSync(store, AxFolderTransport.loopbackLab(endpoint, TOKEN), SPACE, 12345, new byte[32]);
    }

    @Test public void twoOfflineDevicesConvergeWithoutRepeatedUploads() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "Windows")));
        AxFolderLocalStore b = store(Collections.singletonList(folder(Y, 1000, "Android")));
        try (AxFolderSync first = sync(a); AxFolderSync second = sync(b)) {
            assertEquals(1, first.run().revision);
            assertEquals(2, second.run().revision);
            assertFalse(first.run().pendingLocalChanges);
            assertEquals(2, revision);
            assertArrayEquals(a.load().document.encode(), b.load().document.encode());
            assertEquals(2, a.load().nativeFolders().size());
            assertEquals(2, first.run().revision);
            assertEquals(2, second.run().revision);
        }
    }

    @Test public void conflictingUploadReloadsAndKeepsBothDevicesFolders() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "First")));
        AxFolderLocalStore b = store(Collections.singletonList(folder(Y, 1000, "Concurrent")));
        competingSnapshot = AxFolderEnvelope.seal(b.load().document, new byte[32], SPACE);
        try (AxFolderSync engine = sync(a)) {
            assertEquals(2, engine.run().revision);
            assertEquals(2, a.load().nativeFolders().size());
            AxFolderDocument remote = AxFolderEnvelope.open(snapshot, new byte[32], SPACE, 12345);
            assertArrayEquals(a.load().document.encode(), remote.encode());
        }
    }

    @Test public void acknowledgedServerWriteWithLostReplyCanBeRetriedWithoutAnotherRevision() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "Saved")));
        try (AxFolderSync engine = sync(a)) {
            mode = "lostWriteReply";
            try { engine.run(); fail(); } catch (IOException expected) { }
            assertEquals(1, revision);
            assertEquals(1, engine.run().revision);
            assertEquals(1, revision);
            assertEquals("Saved", a.load().nativeFolders().get(0).name);
        }
    }

    @Test public void changesMadeWhileDownloadingAreRecapturedInsteadOfOverwritten() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "Before")));
        duringRead = () -> {
            try { a.save(a.load().capture(Collections.singletonList(folder(X, 1000, "After")), id -> null)); }
            catch (Exception error) { throw new AssertionError(error); }
        };
        try (AxFolderSync engine = sync(a)) {
            assertEquals(1, engine.run().revision);
            assertEquals("After", a.load().nativeFolders().get(0).name);
            AxFolderReplica received = AxFolderReplica.fresh(12345).merge(AxFolderEnvelope.open(snapshot, new byte[32], SPACE, 12345));
            assertEquals("After", received.nativeFolders().get(0).name);
        }
    }

    @Test public void deletionWinsAgainstAnOfflineRename() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "Original")));
        AxFolderLocalStore b = store(Collections.emptyList());
        try (AxFolderSync first = sync(a); AxFolderSync second = sync(b)) {
            first.run(); second.run();
            a.save(a.load().capture(Collections.emptyList(), id -> null));
            b.save(b.load().capture(Collections.singletonList(folder(X, 1000, "Offline rename")), id -> null));
            first.run(); second.run(); first.run();
            assertTrue(a.load().nativeFolders().isEmpty());
            assertTrue(b.load().nativeFolders().isEmpty());
            assertTrue(b.load().document.folder(X).has("deleted"));
            assertArrayEquals(a.load().document.encode(), b.load().document.encode());
        }
    }

    @Test public void wrongKeyOrUnauthorizedResponsePreservesLocalState() throws Exception {
        AxFolderLocalStore a = store(Collections.singletonList(folder(X, 1000, "Private")));
        byte[] before = a.load().encode(), wrong = new byte[32]; wrong[0] = 1;
        snapshot = AxFolderEnvelope.seal(AxFolderDocument.empty(12345), wrong, SPACE); revision = 1;
        try (AxFolderSync engine = sync(a)) {
            try { engine.run(); fail(); } catch (java.security.GeneralSecurityException expected) { }
            assertArrayEquals(before, a.load().encode());
            mode = "unauthorized";
            try { engine.run(); fail(); } catch (AxFolderTransport.HttpFailure expected) { assertEquals(401, expected.status); }
            assertArrayEquals(before, a.load().encode());
            engine.close();
            try { engine.run(); fail(); } catch (IllegalStateException expected) { }
        }
    }
}
