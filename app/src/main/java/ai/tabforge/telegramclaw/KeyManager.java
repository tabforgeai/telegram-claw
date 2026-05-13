package ai.tabforge.telegramclaw;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages the device's RSA-2048 key pair in the Android Keystore for end-to-end encryption
 * of FCM commands — Protocol 2 (End-to-End Encryption).
 *
 * <p>Analogy: like a safe deposit box at a bank — the Keystore is the bank vault, the private
 * key never leaves it, and only the Android Keystore hardware (or TEE) can perform decryption
 * operations using it. The relay server only ever sees the public key and the ciphertext;
 * it cannot decrypt the payload even if compromised.</p>
 *
 * <p>Encryption scheme: hybrid RSA-OAEP + AES-256-GCM.
 * <ul>
 *   <li>RSA is used to wrap a fresh AES-256 session key per message</li>
 *   <li>AES-256-GCM encrypts the actual command payload</li>
 *   <li>The 128-bit GCM tag provides both confidentiality and integrity</li>
 * </ul>
 * This avoids the RSA payload size limit (~214 bytes for RSA-2048 OAEP) while still
 * keeping the private key safely inside the Android Keystore hardware.</p>
 *
 * <p>OAEP parameters: SHA-256 as the main hash, SHA-1 for MGF1 — this is the combination
 * that Android Keystore reliably supports for interoperability with standard Java crypto
 * on the relay side.</p>
 *
 * <p>Called by: {@link MainActivity} (key generation + public key display) and
 * {@link ClawMessagingService} (decryption of incoming FCM commands).</p>
 */
public class KeyManager {

    private static final String TAG   = "KeyManager";
    private static final String ALIAS = "claw_rsa_key";

    /**
     * Generates an RSA-2048 key pair in the Android Keystore if one does not already exist.
     *
     * <p>Analogy: like cutting a key for the first time — only happens once. If the key
     * already exists in the Keystore, this is a no-op. The private key is created with
     * {@code PURPOSE_DECRYPT} only, so the Keystore will refuse to use it for signing.</p>
     *
     * <p>Called by: {@link MainActivity#onCreate} on every app start. Safe to call repeatedly.</p>
     */
    public static void ensureKeyPair() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) return;

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .build());
            kpg.generateKeyPair();
            Log.i(TAG, "RSA-2048 key pair generated in Android Keystore.");
        } catch (Exception e) {
            Log.e(TAG, "Key pair generation failed: " + e.getMessage());
        }
    }

    /**
     * Returns the device's RSA public key as a Base64-encoded DER string, for display and
     * export to the relay server's {@code DEVICE_PUBLIC_KEY} environment variable.
     *
     * @return  Base64 public key, or empty string if the key pair has not been generated yet
     */
    public static String getPublicKeyBase64() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Certificate cert = ks.getCertificate(ALIAS);
            if (cert == null) return "";
            return Base64.encodeToString(cert.getPublicKey().getEncoded(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read public key: " + e.getMessage());
            return "";
        }
    }

    /**
     * Decrypts a hybrid-encrypted FCM command payload.
     *
     * <p>Step 1 — RSA-OAEP: decrypts the AES session key using the private key from Keystore.
     * Step 2 — AES-256-GCM: decrypts the actual payload using the recovered session key.
     * The GCM authentication tag (128 bits) is verified as part of step 2 — any tampering
     * with the ciphertext will cause {@code AEADBadTagException} before any data is returned.</p>
     *
     * <p>Called by: {@link ClawMessagingService#onMessageReceived} for encrypted FCM messages.</p>
     *
     * @param encKeyB64   Base64-encoded RSA-encrypted AES session key
     * @param payloadB64  Base64-encoded AES-GCM ciphertext (payload + 16-byte GCM tag)
     * @param ivB64       Base64-encoded 12-byte GCM initialization vector
     * @return  decrypted plaintext JSON, e.g. {@code {"tool":"audio_manager","params":"...","chatId":"..."}}
     * @throws Exception  if decryption fails due to wrong key, corrupted ciphertext, or missing Keystore entry
     */
    public static String decrypt(String encKeyB64, String payloadB64, String ivB64)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        PrivateKey privateKey = (PrivateKey) ks.getKey(ALIAS, null);
        if (privateKey == null) {
            throw new IllegalStateException("RSA private key not found — call ensureKeyPair() first.");
        }

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(Base64.decode(encKeyB64, Base64.NO_WRAP));

        SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] plaintext = aesCipher.doFinal(Base64.decode(payloadB64, Base64.NO_WRAP));

        return new String(plaintext, StandardCharsets.UTF_8);
    }
}
