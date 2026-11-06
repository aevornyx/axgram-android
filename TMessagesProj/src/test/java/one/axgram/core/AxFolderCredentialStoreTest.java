package one.axgram.core.folders;

import android.util.Base64;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class AxFolderCredentialStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final URL ENDPOINT;
    static { try { ENDPOINT = new URL("https://example.com/sync"); } catch (Exception error) { throw new ExceptionInInitializerError(error); } }

    private static final class Protection implements AxFolderCredentialStore.Protection {
        private final byte[] key = new byte[32];
        boolean fail;
        Protection() { new SecureRandom().nextBytes(key); }
        @Override public byte[] seal(byte[] plain, byte[] context) throws Exception {
            if (fail) throw new IOException("Unavailable");
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(context);
            byte[] encrypted = cipher.doFinal(plain), result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length); return result;
        }
        @Override public byte[] open(byte[] sealed, byte[] context) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(sealed, 12))); cipher.updateAAD(context);
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        }
    }

    private AxFolderAuthClient.Credential credential(long account) throws Exception {
        return AxFolderAuthClient.Credential.decode(new JSONObject().put("account", Long.toString(account))
                .put("workspace", "00000000-0000-0000-0000-000000000001").put("device", "00000000-0000-0000-0000-000000000002")
                .put("token", TOKEN).put("key", Base64.encodeToString(new byte[32], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))
                .toString().getBytes(StandardCharsets.UTF_8), account);
    }

    @Test public void restartRestoresEncryptedLoginAndClearDoesNotReturnIt() throws Exception {
        File root = temporary.newFolder(); Protection protection = new Protection();
        AxFolderCredentialStore store = new AxFolderCredentialStore(root, ENDPOINT, 12345, protection);
        assertNull(store.load());
        try (AxFolderAuthClient.Credential credential = credential(12345)) { store.save(credential); }
        assertEquals(1, root.listFiles().length);
        assertFalse(new String(Files.readAllBytes(root.listFiles()[0].toPath()), StandardCharsets.ISO_8859_1).contains(TOKEN));
        try (AxFolderAuthClient.Credential restored = new AxFolderCredentialStore(root, ENDPOINT, 12345, protection).load()) {
            assertEquals(TOKEN, restored.token()); assertArrayEquals(new byte[32], restored.encryptionKey());
        }
        store.clear(); assertNull(store.load());
    }

    @Test public void tamperWrongDeviceAndCopiedAccountOrEndpointFailClosed() throws Exception {
        File root = temporary.newFolder(); Protection protection = new Protection();
        AxFolderCredentialStore store = new AxFolderCredentialStore(root, ENDPOINT, 12345, protection);
        try (AxFolderAuthClient.Credential credential = credential(12345)) { store.save(credential); }
        File source = root.listFiles()[0]; byte[] sealed = Files.readAllBytes(source.toPath());
        try { new AxFolderCredentialStore(root, ENDPOINT, 12345, new Protection()).load(); fail(); } catch (IOException expected) { }
        URL[] endpoints = {ENDPOINT, new URL("https://other.example/sync")};
        long[] accounts = {54321, 12345};
        for (int i = 0; i < endpoints.length; i++) {
            File isolated = temporary.newFolder(); AxFolderCredentialStore other = new AxFolderCredentialStore(isolated, endpoints[i], accounts[i], protection);
            try (AxFolderAuthClient.Credential credential = credential(accounts[i])) { other.save(credential); }
            Files.write(isolated.listFiles()[0].toPath(), sealed);
            try { other.load(); fail(); } catch (IOException expected) { }
        }
        sealed[sealed.length - 1] ^= 1; Files.write(source.toPath(), sealed);
        try { store.load(); fail(); } catch (IOException expected) { }
        assertArrayEquals(sealed, Files.readAllBytes(source.toPath()));
    }

    @Test public void interruptedReplacementRecoversBackupAndProtectionFailureKeepsOldLogin() throws Exception {
        File root = temporary.newFolder(); Protection protection = new Protection();
        AxFolderCredentialStore store = new AxFolderCredentialStore(root, ENDPOINT, 12345, protection);
        try (AxFolderAuthClient.Credential credential = credential(12345)) { store.save(credential); }
        File source = root.listFiles()[0]; byte[] original = Files.readAllBytes(source.toPath());
        assertTrue(source.renameTo(new File(source.getPath() + ".bak"))); Files.write(source.toPath(), new byte[]{1, 2});
        try (AxFolderAuthClient.Credential restored = store.load()) { assertEquals(TOKEN, restored.token()); }
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
        protection.fail = true;
        try (AxFolderAuthClient.Credential credential = credential(12345)) {
            try { store.save(credential); fail(); } catch (IOException expected) { }
        }
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
        try (AxFolderAuthClient.Credential other = credential(54321)) {
            try { store.save(other); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
}
