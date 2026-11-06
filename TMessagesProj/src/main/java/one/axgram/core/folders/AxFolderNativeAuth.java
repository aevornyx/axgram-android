package one.axgram.core.folders;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;

public final class AxFolderNativeAuth {
    public static final class Denied extends IOException { private Denied() { super("Confirmation declined"); } }
    public static final class Invalid extends IOException { private Invalid(String message) { super(message); } }
    private AxFolderNativeAuth() { }

    public static String registeredRedirect(String applicationId) throws IOException {
        if ("one.axgram.android".equals(applicationId)) return "https://app2044118-login.tg.dev";
        if ("one.axgram.android.stage1".equals(applicationId)) return "https://app2342619292-login.tg.dev";
        throw new Invalid("Unregistered native application");
    }

    public static JSONObject nativeCallback(String applicationId, String state, String accepted) throws IOException, JSONException {
        URL expected = new URL(registeredRedirect(applicationId));
        try {
            if (accepted == null || accepted.length() > 8192) throw new Invalid("Invalid native confirmation result");
            URI uri = new URI(accepted);
            HashMap<String, String> parameters = query(uri.getRawQuery());
            // Telegram Native SDK callbacks return code without echoing state.
            // The MTProto completion belongs to this active flow; PKCE, nonce,
            // poll secret and verified account still bind the server exchange.
            if (!parameters.containsKey("state")) accepted += "&state=" + state;
            return callback(expected, state, accepted);
        } catch (java.net.URISyntaxException error) { throw new Invalid("Invalid native confirmation result"); }
    }

    public static String confirmation(String value) throws IOException {
        if (value == null || value.length() > 8192 || !(value.startsWith("tg://oauth?") || value.startsWith("tg://resolve?"))
                || value.chars().anyMatch(c -> c < 33 || c > 126)) throw new Invalid("Invalid native confirmation");
        try {
            URI uri = new URI(value);
            HashMap<String, String> values = query(uri.getRawQuery());
            boolean direct = "oauth".equals(uri.getRawAuthority()) && values.size() == 1 && values.containsKey("token");
            boolean resolved = "resolve".equals(uri.getRawAuthority()) && values.size() == 2
                    && "oauth".equals(values.get("domain")) && values.containsKey("startapp");
            String token = values.get(direct ? "token" : "startapp");
            if (!(direct || resolved) || !"".equals(uri.getRawPath()) || uri.getRawFragment() != null
                    || token == null || token.length() < 16 || token.length() > 4096
                    || token.chars().anyMatch(c -> c < 33 || c > 126)) throw new Invalid("Invalid native confirmation");
            return value;
        } catch (java.net.URISyntaxException error) { throw new Invalid("Invalid native confirmation"); }
    }

    public static HashMap<String, String> query(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > 8192) throw new Invalid("Invalid confirmation parameters");
        HashMap<String, String> values = new HashMap<>();
        try {
            for (String field : raw.split("&", -1)) {
                String[] pair = field.split("=", 2);
                if (pair.length != 2) throw new Invalid("Invalid confirmation parameter");
                String key = URLDecoder.decode(pair[0], "UTF-8"), value = URLDecoder.decode(pair[1], "UTF-8");
                if (values.put(key, value) != null) throw new Invalid("Duplicate confirmation parameter");
            }
        } catch (IllegalArgumentException error) { throw new Invalid("Invalid confirmation encoding"); }
        return values;
    }

    public static JSONObject callback(URL expected, String state, String accepted) throws IOException, JSONException {
        if (accepted == null || accepted.length() > 8192 || state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw new Invalid("Invalid confirmation result");
        try {
            URI uri = new URI(accepted), target = expected.toURI();
            if (!target.getScheme().equals(uri.getScheme()) || !target.getRawAuthority().equals(uri.getRawAuthority())
                    || !target.getRawPath().equals(uri.getRawPath()) || uri.getRawFragment() != null || uri.getUserInfo() != null) throw new Invalid("Unexpected confirmation destination");
            HashMap<String, String> values = query(uri.getRawQuery());
            if (!state.equals(values.get("state"))) throw new Invalid("Wrong confirmation state");
            if (values.containsKey("error") && (values.size() == 2 || values.size() == 3 && values.containsKey("error_description"))) throw new Denied();
            String code = values.get("code");
            if (values.size() != 2 || code == null || code.isEmpty() || code.length() > 4096
                    || code.chars().anyMatch(c -> c < 33 || c > 126)) throw new Invalid("Invalid confirmation code");
            return new JSONObject().put("state", state).put("code", code);
        } catch (java.net.URISyntaxException error) { throw new Invalid("Invalid confirmation destination"); }
    }

    public static void prompt(long bot, String clientId, boolean messages, boolean phone, long hint, long account) throws IOException {
        if (bot <= 0 || !Long.toString(bot).equals(clientId) || messages || phone || hint != 0 && hint != account) throw new Invalid("Unexpected confirmation permissions or account");
    }
}
