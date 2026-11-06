package one.axgram.core.folders;

import org.telegram.messenger.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class AxFolderTransport {
    public static final class Snapshot {
        public final long revision;
        private final byte[] bytes;
        private Snapshot(long revision, byte[] bytes) { this.revision = revision; this.bytes = bytes.clone(); }
        public byte[] envelope() { return bytes.clone(); }
    }
    public static class HttpFailure extends IOException {
        public final int status;
        private HttpFailure(int status) { super("Sync HTTP " + status); this.status = status; }
    }
    public static final class Conflict extends HttpFailure {
        public final long currentRevision;
        private Conflict(long revision) { super(409); currentRevision = revision; }
    }
    private final URL endpoint;
    private final String token;

    public AxFolderTransport(URL endpoint, String token) {
        this(endpoint, token, false);
    }

    public static AxFolderTransport loopbackLab(URL endpoint, String token) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug build required");
        if (!"http".equals(endpoint.getProtocol()) || !("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()))) throw new IllegalArgumentException("Literal loopback required");
        return new AxFolderTransport(endpoint, token, true);
    }

    private AxFolderTransport(URL endpoint, String token, boolean lab) {
        boolean local = "http".equals(endpoint.getProtocol()) && ("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()));
        if (!("https".equals(endpoint.getProtocol()) || lab && local) || endpoint.getHost().isEmpty()
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null
                || !endpoint.getPath().endsWith("/v1/snapshot") || token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Invalid sync endpoint or credential");
        this.endpoint = endpoint; this.token = token;
    }

    private HttpURLConnection connect(String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
        connection.setRequestMethod(method); connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept-Encoding", "identity"); connection.setUseCaches(false);
        return connection;
    }

    private long revision(HttpURLConnection connection) throws IOException {
        String tag = connection.getHeaderField("ETag");
        if (tag == null || !tag.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new IOException("Invalid sync revision");
        try { return Long.parseLong(tag.substring(1, tag.length() - 1)); }
        catch (NumberFormatException error) { throw new IOException("Invalid sync revision"); }
    }

    public Snapshot read() throws IOException {
        HttpURLConnection connection = connect("GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new HttpFailure(status);
            long revision = revision(connection);
            if (connection.getContentLength() > 1024 * 1024) throw new IOException("Sync response too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
            try (InputStream input = connection.getInputStream()) {
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > 1024 * 1024) throw new IOException("Sync response too large");
                    bytes.write(buffer, 0, read);
                }
            }
            byte[] envelope = bytes.toByteArray();
            if (revision == 0) {
                if (envelope.length != 0) throw new IOException("Unexpected initial snapshot");
            } else {
                if (envelope.length < 44) throw new IOException("Incomplete sync envelope");
                String expected = connection.getHeaderField("X-Content-SHA256");
                if (expected == null || !expected.equals(digest(envelope))) throw new IOException("Sync digest mismatch");
            }
            return new Snapshot(revision, envelope);
        } finally { connection.disconnect(); }
    }

    public long write(long expectedRevision, byte[] envelope) throws IOException {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE || envelope.length < 44 || envelope.length > 1024 * 1024) throw new IllegalArgumentException("Invalid sync write");
        byte[] body = envelope.clone();
        HttpURLConnection connection = connect("PUT");
        try {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("If-Match", "\"" + expectedRevision + "\""); connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status == 409) throw new Conflict(revision(connection));
            if (status != 204) throw new HttpFailure(status);
            long saved = revision(connection);
            if (saved != expectedRevision + 1) throw new IOException("Unexpected sync write revision");
            return saved;
        } finally { connection.disconnect(); }
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : hash) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable"); }
    }
}
