package one.axgram.core.folders;

import org.json.JSONException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AxFolderEnvelope {
    private static final byte[] HEADER = {'A', 'X', 'F', 'S', 1};
    private static final SecureRandom RANDOM = new SecureRandom();
    private AxFolderEnvelope() { }

    private static byte[] aad(String workspace, long account, byte[] key) throws GeneralSecurityException {
        try {
            if (account <= 0 || key.length != 32 || !workspace.equals(UUID.fromString(workspace).toString())) throw new GeneralSecurityException("Invalid encryption context");
        } catch (IllegalArgumentException error) { throw new GeneralSecurityException("Invalid encryption context"); }
        return ("AxGramFolders/v1/" + workspace + "/" + account).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] seal(AxFolderDocument document, byte[] key, String workspace) throws GeneralSecurityException, JSONException {
        byte[] context = aad(workspace, document.account(), key);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context);
        byte[] ciphertext = cipher.doFinal(document.encode());
        byte[] result = new byte[HEADER.length + nonce.length + ciphertext.length];
        System.arraycopy(HEADER, 0, result, 0, HEADER.length);
        System.arraycopy(nonce, 0, result, HEADER.length, nonce.length);
        System.arraycopy(ciphertext, 0, result, HEADER.length + nonce.length, ciphertext.length);
        return result;
    }

    public static AxFolderDocument open(byte[] envelope, byte[] key, String workspace, long account) throws GeneralSecurityException, JSONException {
        byte[] context = aad(workspace, account, key);
        if (envelope.length < 33 || envelope.length > 1024 * 1024
                || !Arrays.equals(HEADER, Arrays.copyOf(envelope, HEADER.length))) throw new GeneralSecurityException("Invalid envelope");
        byte[] nonce = Arrays.copyOfRange(envelope, HEADER.length, HEADER.length + 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context);
        return AxFolderDocument.parse(cipher.doFinal(envelope, HEADER.length + 12, envelope.length - HEADER.length - 12), account);
    }
}
