package one.axgram.core;

import one.axgram.core.folders.AxFolderNativeAuth;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.net.URL;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderNativeAuthTest {
    private static final String STATE = "s".repeat(43);
    private static final String LINK = "tg://oauth?token=" + "t".repeat(64);
    private final URL callback;
    public AxFolderNativeAuthTest() throws Exception { callback = new URL("https://example.com/axgram-sync/v1/auth/callback"); }

    @Test public void onlyCanonicalTelegramConfirmationLinksAreAccepted() throws Exception {
        assertEquals(LINK, AxFolderNativeAuth.confirmation(LINK));
        assertEquals(LINK + ".signed%2Fvalue%3D", AxFolderNativeAuth.confirmation(LINK + ".signed%2Fvalue%3D"));
        for (String invalid : new String[] {"https://example.com", LINK + "#fragment", LINK + "&x=y", LINK.replace("tg:", "javascript:"),
                LINK.replace("oauth?", "oauth/path?"), "tg://oauth?token=short", LINK.replace("oauth?", "user@oauth?"), LINK + "\n"}) {
            try { AxFolderNativeAuth.confirmation(invalid); fail("Invalid link accepted"); } catch (AxFolderNativeAuth.Invalid expected) { }
        }
    }

    @Test public void realResolveOauthLinksAreKeptVerbatimAndRemainRestrictedToOauth() throws Exception {
        String link = "tg://resolve?domain=oauth&startapp=" + "x".repeat(24);
        assertEquals(link, AxFolderNativeAuth.confirmation(link));
        String reversed = "tg://resolve?startapp=" + "x".repeat(24) + "&domain=oauth";
        assertEquals(reversed, AxFolderNativeAuth.confirmation(reversed));
        for (String invalid : new String[] {link.replace("domain=oauth", "domain=other"), link + "&extra=1",
                link + "&domain=oauth", link + "&startapp=duplicate", link + "#fragment",
                link.replace("resolve?", "resolve/path?"), link.replace("resolve?", "user@resolve?"),
                link.replace("resolve?", "resolve:443?"), "tg://resolve?domain=oauth&startapp=short",
                "tg://resolve?domain=oauth&startapp=" + "%FF".repeat(24)}) {
            try { AxFolderNativeAuth.confirmation(invalid); fail("Untrusted resolve link accepted"); }
            catch (AxFolderNativeAuth.Invalid expected) { }
        }
    }

    @Test public void callbackCodeIsDecodedOnceAndKeptOutOfAnyNavigation() throws Exception {
        JSONObject body = AxFolderNativeAuth.callback(callback, STATE, callback + "?state=" + STATE + "&code=one%2Btime%2Fcode%3D");
        assertEquals(2, body.length()); assertEquals(STATE, body.getString("state")); assertEquals("one+time/code=", body.getString("code"));
    }

    @Test public void registeredNativeCallbacksAreBoundToTheInstalledApplication() throws Exception {
        assertEquals("https://app2044118-login.tg.dev", AxFolderNativeAuth.registeredRedirect("one.axgram.android"));
        String stage = AxFolderNativeAuth.registeredRedirect("one.axgram.android.stage1");
        assertEquals("https://app2342619292-login.tg.dev", stage);
        assertEquals("native-code", AxFolderNativeAuth.callback(new URL(stage), STATE, stage + "?state=" + STATE + "&code=native-code").getString("code"));
        for (String other : new String[] {"https://app2044118-login.tg.dev", stage + "/", "https://app2342619292-login.tg.dev.attacker.invalid"}) {
            try { AxFolderNativeAuth.callback(new URL(stage), STATE, other + "?state=" + STATE + "&code=code"); fail("Wrong application callback accepted"); }
            catch (AxFolderNativeAuth.Invalid expected) { }
        }
        try { AxFolderNativeAuth.registeredRedirect("unregistered.application"); fail(); }
        catch (AxFolderNativeAuth.Invalid expected) { }
    }

    @Test public void nativeSdkCodeOnlyReplyUsesTheBoundFlowWithoutRelaxingBrowserState() throws Exception {
        String app = "one.axgram.android.stage1", redirect = AxFolderNativeAuth.registeredRedirect(app);
        JSONObject result = AxFolderNativeAuth.nativeCallback(app, STATE, redirect + "?code=native%2Bcode");
        assertEquals(STATE, result.getString("state")); assertEquals("native+code", result.getString("code"));
        for (String invalid : new String[] {redirect + "?code=code&state=" + "x".repeat(43), redirect + "?code=a&code=b",
                redirect + "?code=a&extra=b", redirect + "?code=a#fragment", "https://app2044118-login.tg.dev?code=code",
                callback + "?code=code", redirect + "?code="}) {
            try { AxFolderNativeAuth.nativeCallback(app, STATE, invalid); fail("Unbound native result accepted"); }
            catch (AxFolderNativeAuth.Invalid expected) { }
        }
        try { AxFolderNativeAuth.callback(callback, STATE, callback + "?code=code"); fail("Browser state became optional"); }
        catch (AxFolderNativeAuth.Invalid expected) { }
    }

    @Test public void callbackMustMatchTheExactServiceAndFlow() throws Exception {
        String valid = callback + "?state=" + STATE + "&code=code";
        for (String invalid : new String[] {valid.replace("example.com", "attacker.invalid"), valid.replace("https:", "http:"),
                valid.replace("example.com", "example.com.attacker.invalid"), valid.replace("example.com", "user@example.com"),
                valid.replace("/callback?", "/callback/extra?"), valid + "#fragment", valid.replace(STATE, "x".repeat(43)),
                valid.replace("example.com", "example.com:444"), "tg://oauth?token=code"}) {
            try { AxFolderNativeAuth.callback(callback, STATE, invalid); fail("Unbound callback accepted"); }
            catch (AxFolderNativeAuth.Invalid expected) { }
        }
    }

    @Test public void duplicateExtraMissingAndMalformedParametersCannotReachTheServer() throws Exception {
        String prefix = callback + "?state=" + STATE;
        for (String invalid : new String[] {prefix, prefix + "&code=", prefix + "&code=code&code=other", prefix + "&%73tate=" + STATE + "&code=code",
                prefix + "&code=code&extra=1", prefix + "&code=%0A", prefix + "&code=%FF", prefix + "&code=%XY", prefix + "&code=code&",
                prefix + "&code=" + "a".repeat(4097)}) {
            try { AxFolderNativeAuth.callback(callback, STATE, invalid); fail("Invalid parameters accepted"); }
            catch (AxFolderNativeAuth.Invalid expected) { }
        }
    }

    @Test public void denialIsRecognizedOnlyForTheBoundFlow() throws Exception {
        try { AxFolderNativeAuth.callback(callback, STATE, callback + "?state=" + STATE + "&error=access_denied&error_description=Declined"); fail(); }
        catch (AxFolderNativeAuth.Denied expected) { }
        try { AxFolderNativeAuth.callback(callback, STATE, callback + "?state=" + "x".repeat(43) + "&error=access_denied"); fail(); }
        catch (AxFolderNativeAuth.Invalid expected) { }
    }

    @Test public void promptCannotSwitchAccountBotOrRequestPhoneAndMessagePermissions() throws Exception {
        AxFolderNativeAuth.prompt(123456, "123456", false, false, 0, 42);
        AxFolderNativeAuth.prompt(123456, "123456", false, false, 42, 42);
        for (int scenario = 0; scenario < 5; scenario++) {
            try {
                AxFolderNativeAuth.prompt(scenario == 0 ? 0 : scenario == 1 ? 999 : 123456, "123456", scenario == 2, scenario == 3, scenario == 4 ? 99 : 0, 42);
                fail("Unexpected permissions accepted");
            } catch (AxFolderNativeAuth.Invalid expected) { }
        }
    }
}
