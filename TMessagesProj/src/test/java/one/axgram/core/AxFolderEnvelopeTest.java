package one.axgram.core;

import one.axgram.core.folders.AxFolderDocument;
import one.axgram.core.folders.AxFolderEnvelope;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderEnvelopeTest {
    private JSONObject fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/axgram-folder-sync-v1.json")) {
            assertNotNull(input);
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject("crypto");
        }
    }

    private byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    @Test public void opensIndependentReferenceEnvelope() throws Exception {
        JSONObject test = fixture();
        AxFolderDocument opened = AxFolderEnvelope.open(hex(test.getString("envelopeHex")), hex(test.getString("keyHex")), test.getString("workspace"), 12345);
        assertArrayEquals(test.getString("documentWire").getBytes(StandardCharsets.UTF_8), opened.encode());
    }

    @Test public void corruptionWrongKeyWorkspaceAndAccountAreRejected() throws Exception {
        JSONObject test = fixture(); byte[] envelope = hex(test.getString("envelopeHex")), key = hex(test.getString("keyHex"));
        String workspace = test.getString("workspace");
        for (int offset : new int[]{0, 5, 17, envelope.length - 1}) {
            byte[] changed = envelope.clone(); changed[offset] ^= 1;
            try { AxFolderEnvelope.open(changed, key, workspace, 12345); fail(); }
            catch (GeneralSecurityException expected) { }
        }
        byte[] wrong = key.clone(); wrong[0] ^= 1;
        try { AxFolderEnvelope.open(envelope, wrong, workspace, 12345); fail(); } catch (GeneralSecurityException expected) { }
        try { AxFolderEnvelope.open(envelope, key, "00000000-0000-0000-0000-000000000002", 12345); fail(); } catch (GeneralSecurityException expected) { }
        try { AxFolderEnvelope.open(envelope, key, workspace, 54321); fail(); } catch (GeneralSecurityException expected) { }
    }

    @Test public void repeatedEncryptionUsesDifferentNoncesAndBothCopiesOpen() throws Exception {
        JSONObject test = fixture(); byte[] key = hex(test.getString("keyHex")); String workspace = test.getString("workspace");
        AxFolderDocument doc = AxFolderDocument.parse(test.getString("documentWire").getBytes(StandardCharsets.UTF_8), 12345);
        byte[] first = AxFolderEnvelope.seal(doc, key, workspace), second = AxFolderEnvelope.seal(doc, key, workspace);
        assertFalse(Arrays.equals(first, second));
        assertEquals(doc.encode().length + 33, first.length);
        assertArrayEquals(doc.encode(), AxFolderEnvelope.open(first, key, workspace, 12345).encode());
        assertArrayEquals(doc.encode(), AxFolderEnvelope.open(second, key, workspace, 12345).encode());
    }

    @Test public void invalidContextAndTruncatedEnvelopeAreRejected() throws Exception {
        JSONObject test = fixture(); byte[] key = hex(test.getString("keyHex")); String workspace = test.getString("workspace");
        try { AxFolderEnvelope.seal(AxFolderDocument.empty(12345), new byte[31], workspace); fail(); } catch (GeneralSecurityException expected) { }
        try { AxFolderEnvelope.open(new byte[10], key, workspace, 12345); fail(); } catch (GeneralSecurityException expected) { }
        try { AxFolderEnvelope.open(hex(test.getString("envelopeHex")), key, "not-a-uuid", 12345); fail(); } catch (GeneralSecurityException expected) { }
    }
}
