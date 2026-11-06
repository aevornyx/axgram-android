package one.axgram.core;

import one.axgram.core.folders.AxFolderAuthClient;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderAuthClientTest {
    private static final String SECRET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private String authorization(String scope) throws Exception {
        return "https://oauth.telegram.org/auth?client_id=123456&redirect_uri=" + URLEncoder.encode("https://example.com/v1/auth/callback", "UTF-8")
                + "&response_type=code&scope=" + URLEncoder.encode(scope, "UTF-8") + "&state=" + SECRET + "&nonce=" + SECRET
                + "&code_challenge=" + SECRET + "&code_challenge_method=S256";
    }

    private AxFolderAuthClient.Flow start(String authorization) throws Exception {
        return start(authorization, 200);
    }

    private AxFolderAuthClient.Flow start(String authorization, int status) throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0)); server.setSoTimeout(5000);
            byte[] body = new JSONObject().put("requestId", "00000000-0000-0000-0000-000000000001")
                    .put("pollToken", SECRET).put("authorizeUrl", authorization).put("expiresIn", 300).put("pollInterval", 2)
                    .toString().getBytes(StandardCharsets.UTF_8);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> worker = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    line(input); String header; int length = 0;
                    while (!(header = line(input)).isEmpty()) {
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                    }
                    assertTrue(length > 0 && length < 4096); input.readNBytes(length);
                    String headers = "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body);
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            try {
                return AxFolderAuthClient.loopbackLab(new URL("http://127.0.0.1:" + server.getLocalPort())).start(12345, "Test phone");
            } finally {
                worker.get(6, TimeUnit.SECONDS); executor.shutdownNow();
            }
        }
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

    @Test public void browserOnlyReceivesTheExpectedTelegramAuthorizationRequest() throws Exception {
        String valid = authorization("openid profile");
        assertEquals("oauth.telegram.org", start(valid).authorizeUrl.getHost());
        for (String invalid : new String[]{authorization("openid profile phone"), valid + "&scope=openid", valid.replace("oauth.telegram.org", "attacker.invalid"), valid.replace("S256", "plain"), valid.replace("nonce=" + SECRET, "nonce=short")}) {
            try { start(invalid); fail("Unsafe login URL accepted"); }
            catch (IOException expected) { }
        }
    }

    @Test public void productionRequiresHttpsAndDebugBypassCannotTargetARemoteService() throws Exception {
        try { new AxFolderAuthClient(new URL("http://example.com/sync")); fail(); } catch (IllegalArgumentException expected) { }
        try { AxFolderAuthClient.loopbackLab(new URL("https://example.com/sync")); fail(); } catch (IllegalArgumentException expected) { }
        try { new AxFolderAuthClient(new URL("https://example.com/sync?token=secret")); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectedLoginIsTerminalButRateLimitAndServerFailureCanBeRetried() throws Exception {
        for (int status : new int[] {400, 401, 403, 404, 410, 408, 429, 500, 503}) {
            try { start(authorization("openid profile"), status); fail("HTTP failure accepted"); }
            catch (IOException error) { assertEquals(status < 500 && status != 408 && status != 429, error instanceof AxFolderAuthClient.LoginRejected); }
        }
    }

    @Test public void flowFromAnotherServiceIsRejectedBeforeAnyPollRequest() throws Exception {
        AxFolderAuthClient.Flow flow = start(authorization("openid profile"));
        try { new AxFolderAuthClient(new URL("https://example.com")).poll(flow); fail(); }
        catch (AxFolderAuthClient.LoginRejected expected) { }
    }
}
