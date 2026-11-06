package one.axgram.core.folders;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

public final class AxFolderCredentialStore {
    public interface Protection {
        byte[] seal(byte[] plain, byte[] context) throws Exception;
        byte[] open(byte[] sealed, byte[] context) throws Exception;
    }

    private static final Object LOCK = new Object();
    private final long account;
    private final File file, backup;
    private final byte[] binding;
    private final Protection protection;

    public static AxFolderCredentialStore android(Context context, URL endpoint, long account) throws IOException {
        Context application = context.getApplicationContext();
        return new AxFolderCredentialStore(new File(application.getNoBackupFilesDir(), "ax_folder_login"), endpoint, account,
                new DeviceProtection(application, account));
    }

    public AxFolderCredentialStore(File root, URL endpoint, long account, Protection protection) throws IOException {
        if (account <= 0 || root == null || protection == null) throw new IllegalArgumentException("Login storage required");
        new AxFolderAuthClient(endpoint);
        this.account = account; this.protection = protection;
        binding = ("AxGramFolders/login/v1\n" + endpoint.toExternalForm() + "\n" + account).getBytes(StandardCharsets.UTF_8);
        String digest;
        try {
            StringBuilder text = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(binding)) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            digest = text.toString();
        } catch (Exception error) { throw new IOException("Login storage unavailable", error); }
        file = new File(root, digest + ".bin"); backup = new File(file.getPath() + ".bak");
    }

    public AxFolderAuthClient.Credential load() throws IOException, JSONException {
        synchronized (LOCK) {
            recover();
            if (!file.exists()) return null;
            byte[] plain = null;
            try (FileInputStream input = new FileInputStream(file)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > 8192) throw new IOException("Saved login too large");
                    bytes.write(buffer, 0, read);
                }
                plain = protection.open(bytes.toByteArray(), binding);
                return AxFolderAuthClient.Credential.decode(plain, account);
            } catch (JSONException error) { throw error; }
            catch (Exception error) { throw new IOException("Cannot unlock saved login", error); }
            finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
        }
    }

    public void save(AxFolderAuthClient.Credential credential) throws IOException, JSONException {
        synchronized (LOCK) {
            if (credential.account != account) throw new IllegalArgumentException("Wrong login account");
            byte[] plain = credential.encode(), sealed;
            try { sealed = protection.seal(plain, binding); }
            catch (Exception error) { throw new IOException("Cannot protect login", error); }
            finally { Arrays.fill(plain, (byte) 0); }
            if (sealed == null || sealed.length > 8192 || sealed.length == 0) throw new IOException("Invalid protected login");
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create login directory");
            recover();
            if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot preserve saved login");
            try {
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(sealed); output.getFD().sync(); }
                if (backup.exists() && !backup.delete()) throw new IOException("Cannot commit saved login");
            } catch (IOException error) {
                if (file.exists() && !file.delete()) throw new IOException("Cannot discard incomplete login", error);
                if (backup.exists() && !backup.renameTo(file)) throw new IOException("Cannot restore saved login", error);
                throw error;
            }
        }
    }

    public void clear() throws IOException {
        synchronized (LOCK) {
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot remove saved login backup");
            if (file.exists() && !file.delete()) throw new IOException("Cannot remove saved login");
        }
    }

    private void recover() throws IOException {
        if (!backup.exists()) return;
        if (file.exists() && !file.delete()) throw new IOException("Cannot discard incomplete login");
        if (!backup.renameTo(file)) throw new IOException("Cannot recover saved login");
    }

    private static final class DeviceProtection implements Protection {
        private static final byte[] HEADER = {65, 88, 70, 67, 1};
        private final Context context;
        private final String alias;

        DeviceProtection(Context context, long account) { this.context = context; alias = "axgram.folder.login.v1." + account; }

        private KeyStore keys(boolean create) throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (!store.containsAlias(alias)) {
                if (!create) throw new IOException("Device login key missing");
                Calendar start = Calendar.getInstance(), end = Calendar.getInstance(); end.add(Calendar.YEAR, 30);
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
                generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(alias).setKeySize(2048)
                        .setSubject(new X500Principal("CN=AxGram Folder Login")).setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.getTime()).setEndDate(end.getTime()).build());
                generator.generateKeyPair();
            }
            return store;
        }

        @Override public byte[] seal(byte[] plain, byte[] context) throws Exception {
            KeyStore store = keys(true);
            byte[] secret = new byte[32], nonce = new byte[12]; SecureRandom random = new SecureRandom();
            random.nextBytes(secret); random.nextBytes(nonce);
            try {
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(alias).getPublicKey());
                byte[] wrapped = rsa.doFinal(secret);
                if (wrapped.length != 256) throw new IOException("Unexpected device key size");
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
                aes.updateAAD(context); aes.updateAAD(HEADER); aes.updateAAD(wrapped);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.write(HEADER); output.write(wrapped); output.write(nonce); output.write(aes.doFinal(plain));
                return output.toByteArray();
            } finally { Arrays.fill(secret, (byte) 0); }
        }

        @Override public byte[] open(byte[] sealed, byte[] context) throws Exception {
            if (sealed.length < 289 || !Arrays.equals(HEADER, Arrays.copyOf(sealed, 5))) throw new IOException("Invalid protected login");
            KeyStore store = keys(false);
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.DECRYPT_MODE, store.getKey(alias, null));
            byte[] secret = rsa.doFinal(Arrays.copyOfRange(sealed, 5, 261));
            try {
                if (secret.length != 32) throw new IOException("Invalid device key");
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 261, 273)));
                aes.updateAAD(context); aes.updateAAD(HEADER); aes.updateAAD(Arrays.copyOfRange(sealed, 5, 261));
                return aes.doFinal(sealed, 273, sealed.length - 273);
            } finally { Arrays.fill(secret, (byte) 0); }
        }
    }
}
