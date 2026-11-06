package one.axgram.core.folders;

import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AxFolderAuthClient {
    public static final class Device {
        public final String id, label;
        public final long expires;
        private Device(String id, String label, long expires) { this.id = id; this.label = label; this.expires = expires; }
    }
    public static final class Devices {
        public final String current;
        public final boolean canManage;
        public final List<Device> devices;
        private Devices(String current, boolean canManage, List<Device> devices) {
            this.current = current; this.canManage = canManage; this.devices = Collections.unmodifiableList(devices);
        }
    }
    public static final class LoginRejected extends IOException {
        public final int status;
        private LoginRejected(String message) { this(message, 0); }
        private LoginRejected(String message, int status) { super(message); this.status = status; }
    }
    public static final class Flow {
        public final URL authorizeUrl;
        public final int pollIntervalSeconds;
        public final long account;
        public final String confirmationUrl, clientId;
        private final String state;
        private final String id, token;
        private final String origin;
        private final long expiresAt;
        private Flow(URL authorizeUrl, int interval, long account, String id, String token, String origin, long expiresAt, String confirmationUrl, String clientId, String state) {
            this.authorizeUrl = authorizeUrl; pollIntervalSeconds = interval; this.account = account;
            this.id = id; this.token = token; this.origin = origin; this.expiresAt = expiresAt;
            this.confirmationUrl = confirmationUrl; this.clientId = clientId; this.state = state;
        }
    }
    public static final class Credential implements AutoCloseable {
        public final long account;
        public final String workspace, device;
        private final String token;
        private final byte[] key;
        private boolean closed;
        private Credential(long account, String workspace, String device, String token, byte[] key) {
            this.account = account; this.workspace = workspace; this.device = device; this.token = token; this.key = key.clone();
        }
        public synchronized String token() { if (closed) throw new IllegalStateException("Credential closed"); return token; }
        public synchronized byte[] encryptionKey() { if (closed) throw new IllegalStateException("Credential closed"); return key.clone(); }
        @Override public synchronized void close() { Arrays.fill(key, (byte) 0); closed = true; }

        synchronized byte[] encode() throws JSONException {
            if (closed) throw new IllegalStateException("Credential closed");
            return new JSONObject().put("account", Long.toString(account)).put("workspace", workspace).put("device", device)
                    .put("token", token).put("key", Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))
                    .toString().getBytes(StandardCharsets.UTF_8);
        }

        static Credential decode(byte[] bytes, long account) throws IOException, JSONException {
            if (account <= 0 || bytes.length > 4096) throw new IOException("Invalid saved login");
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONObject object = new JSONObject(json);
            if (object.length() != 5 || !Long.toString(account).equals(object.getString("account"))) throw new IOException("Wrong saved account");
            String encoded = object.getString("key");
            if (!encoded.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid saved key");
            byte[] key = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            try {
                if (key.length != 32 || !encoded.equals(Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))) throw new IOException("Invalid saved key");
                return new Credential(account, uuid(object.getString("workspace")), uuid(object.getString("device")), AxFolderAuthClient.token(object.getString("token")), key);
            } finally { Arrays.fill(key, (byte) 0); }
        }
    }
    private final URL base;
    private final boolean laboratory;

    public AxFolderAuthClient(URL base) { this(base, false); }
    public static AxFolderAuthClient loopbackLab(URL base) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug build required");
        if (!"http".equals(base.getProtocol()) || !("127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost()))) throw new IllegalArgumentException("Literal loopback required");
        return new AxFolderAuthClient(base, true);
    }
    private AxFolderAuthClient(URL base, boolean lab) {
        boolean loopback = "http".equals(base.getProtocol()) && ("127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost()));
        if (!("https".equals(base.getProtocol()) || lab && loopback) || base.getHost().isEmpty() || base.getUserInfo() != null
                || base.getQuery() != null || base.getRef() != null || base.getPath().endsWith("/")) throw new IllegalArgumentException("Invalid login endpoint");
        this.base = base; laboratory = lab;
    }

    public Flow start(long account, String label) throws IOException, JSONException {
        return start(account, label, false);
    }

    public Flow startNative(long account, String label) throws IOException, JSONException {
        return start(account, label, true);
    }

    private Flow start(long account, String label, boolean nativeConfirmation) throws IOException, JSONException {
        if (account <= 0 || label == null || label.trim().isEmpty() || label.length() > 80 || label.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid login request");
        JSONObject body = new JSONObject().put("account", Long.toString(account)).put("device", label);
        if (nativeConfirmation && !laboratory) body.put("application", BuildConfig.APPLICATION_ID);
        JSONObject result = request(nativeConfirmation ? "/v1/auth/native/start" : "/v1/auth/start", "POST", null, body);
        String id = uuid(result.getString("requestId")), token = token(result.getString("pollToken"));
        URL authorize = new URL(result.getString("authorizeUrl"));
        if (!"https".equals(authorize.getProtocol()) || !"oauth.telegram.org".equals(authorize.getHost()) || !"/auth".equals(authorize.getPath())
                || authorize.getUserInfo() != null || authorize.getRef() != null || authorize.getPort() != -1) throw new IOException("Invalid authorization URL");
        validateAuthorization(authorize, nativeConfirmation);
        HashMap<String, String> parameters = AxFolderNativeAuth.query(authorize.getQuery());
        String nativeUrl = nativeConfirmation ? AxFolderNativeAuth.confirmation(result.getString("confirmationUrl")) : null;
        int lifetime = integer(result, "expiresIn", 1, 300), interval = integer(result, "pollInterval", 1, 30);
        return new Flow(authorize, interval, account, id, token, base.toExternalForm(), SystemClock.elapsedRealtime() + lifetime * 1000L,
                nativeUrl, parameters.get("client_id"), parameters.get("state"));
    }

    public void completeNative(Flow flow, String accepted) throws IOException, JSONException {
        checkFlow(flow);
        if (flow.confirmationUrl == null) throw new LoginRejected("Native confirmation required");
        String redirect = AxFolderNativeAuth.query(flow.authorizeUrl.getQuery()).get("redirect_uri");
        JSONObject body = laboratory ? AxFolderNativeAuth.callback(new URL(redirect), flow.state, accepted)
                : AxFolderNativeAuth.nativeCallback(BuildConfig.APPLICATION_ID, flow.state, accepted);
        if (!request("/v1/auth/native/complete/" + flow.id, "POST", flow.token, body).getString("status").equals("confirmed")) throw new IOException("Confirmation failed");
    }

    public Credential poll(Flow flow) throws IOException, JSONException {
        checkFlow(flow);
        JSONObject result = request("/v1/auth/status/" + flow.id, "GET", flow.token, null);
        String status = result.getString("status");
        if (status.equals("pending")) return null;
        if (status.equals("error")) throw new LoginRejected("Telegram login was not completed");
        if (!status.equals("ready") || !Long.toString(flow.account).equals(result.getString("account"))) throw new IOException("Wrong login account");
        String encoded = result.getString("encryptionKey");
        if (!encoded.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid workspace key");
        byte[] key = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
            if (key.length != 32 || !encoded.equals(Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))) throw new IOException("Invalid workspace key");
            return new Credential(flow.account, uuid(result.getString("workspace")), uuid(result.getString("device")), token(result.getString("token")), key);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public void acknowledge(Flow flow) throws IOException, JSONException {
        checkFlow(flow);
        if (!request("/v1/auth/ack/" + flow.id, "POST", flow.token, null).getString("status").equals("acknowledged")) throw new IOException("Login acknowledgement failed");
    }

    public Devices devices(Credential credential) throws IOException, JSONException {
        return parseDevices(request("/v1/devices", "GET", credential.token(), null), credential.device);
    }

    public static Devices parseDevices(JSONObject object, String expectedDevice) throws IOException, JSONException {
        String current = uuid(object.getString("currentDevice"));
        Object canManage = object.get("canManage");
        var array = object.getJSONArray("devices");
        if (object.length() != 3 || !current.equals(expectedDevice) || !(canManage instanceof Boolean) || array.length() < 1 || array.length() > 32) throw new IOException("Invalid devices list");
        var devices = new ArrayList<Device>();
        var seen = new HashSet<String>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String id = uuid(item.getString("id")), label = item.getString("label");
            Object timestamp = item.get("expires");
            if (item.length() != 3 || !(item.get("label") instanceof String) || !seen.add(id) || label.trim().isEmpty() || label.length() > 80
                    || label.chars().anyMatch(c -> c < 32 || c == 127) || !(timestamp instanceof Number)) throw new IOException("Invalid device");
            long expires = ((Number) timestamp).longValue();
            if (expires < 1 || expires > 4102444800L || ((Number) timestamp).doubleValue() != expires) throw new IOException("Invalid device expiry");
            devices.add(new Device(id, label, expires));
        }
        if (!seen.contains(current)) throw new IOException("Current device is missing");
        return new Devices(current, (Boolean) canManage, devices);
    }

    public void revokeDevice(Credential credential, String device) throws IOException, JSONException {
        JSONObject result = request("/v1/devices/" + uuid(device) + "/revoke", "POST", credential.token(), new JSONObject());
        if (result.length() != 1 || !"revoked".equals(result.getString("status"))) throw new IOException("Device revocation failed");
    }

    private void checkFlow(Flow flow) throws IOException {
        if (flow == null || !base.toExternalForm().equals(flow.origin) || SystemClock.elapsedRealtime() >= flow.expiresAt) throw new LoginRejected("Login request expired");
    }

    private void validateAuthorization(URL authorize, boolean nativeConfirmation) throws IOException {
        if (authorize.getQuery() == null) throw new IOException("Invalid authorization parameters");
        HashMap<String, String> parameters = new HashMap<>();
        try {
            for (String part : authorize.getQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) throw new IOException("Invalid authorization parameters");
                String name = URLDecoder.decode(pair[0], "UTF-8"), value = URLDecoder.decode(pair[1], "UTF-8");
                if (parameters.containsKey(name)) throw new IOException("Repeated authorization parameter");
                parameters.put(name, value);
            }
        } catch (IllegalArgumentException error) { throw new IOException("Invalid authorization encoding"); }
        if (!parameters.keySet().equals(new HashSet<>(Arrays.asList("client_id", "redirect_uri", "response_type", "scope", "state", "nonce", "code_challenge", "code_challenge_method")))
                || !"openid profile".equals(parameters.get("scope")) || !"code".equals(parameters.get("response_type"))
                || !"S256".equals(parameters.get("code_challenge_method")) || !parameters.get("client_id").matches("[1-9][0-9]{0,31}")
                || !parameters.get("state").matches("[A-Za-z0-9_-]{43}") || !parameters.get("nonce").matches("[A-Za-z0-9_-]{43}")
                || !parameters.get("code_challenge").matches("[A-Za-z0-9_-]{43}")
                || !laboratory && !(nativeConfirmation ? AxFolderNativeAuth.registeredRedirect(BuildConfig.APPLICATION_ID)
                    : base.toExternalForm() + "/v1/auth/callback").equals(parameters.get("redirect_uri"))) throw new IOException("Unexpected authorization parameters");
    }

    private JSONObject request(String path, String method, String bearer, JSONObject body) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base.toExternalForm() + path).openConnection();
        connection.setRequestMethod(method); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(15000); connection.setRequestProperty("Accept-Encoding", "identity");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (body != null || method.equals("POST")) {
                byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(data); }
            }
            int status = connection.getResponseCode();
            if (status >= 400 && status < 500 && status != 408 && status != 429) throw new LoginRejected("Login HTTP " + status, status);
            if (status != 200) throw new IOException("Login HTTP " + status);
            if (connection.getContentLength() > 16384) throw new IOException("Login response too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > 16384) throw new IOException("Login response too large");
                    bytes.write(buffer, 0, read);
                }
            }
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return new JSONObject(json);
        } finally { connection.disconnect(); }
    }

    private static String uuid(String value) throws IOException {
        try { if (value.equals(UUID.fromString(value).toString())) return value; }
        catch (IllegalArgumentException error) { }
        throw new IOException("Invalid login identifier");
    }
    private static String token(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("Invalid login credential");
        return value;
    }
    private static int integer(JSONObject object, String key, int minimum, int maximum) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IOException("Invalid login duration");
        long result = ((Number) value).longValue();
        if (((Number) value).doubleValue() != result || result < minimum || result > maximum) throw new IOException("Invalid login duration");
        return (int) result;
    }
}
