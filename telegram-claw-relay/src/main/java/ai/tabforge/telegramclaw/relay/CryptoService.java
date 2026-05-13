package ai.tabforge.telegramclaw.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts FCM command payloads using hybrid RSA-OAEP + AES-256-GCM encryption —
 * Protocol 2 (End-to-End Encryption) on the relay side.
 *
 * <p>Analogy: like a courier who seals each envelope with a combination lock (AES-GCM)
 * and then locks the combination code itself inside a box that only the recipient can open
 * (RSA-OAEP). Even if the courier is intercepted, neither the envelope contents nor the
 * combination are readable without the recipient's private key.</p>
 *
 * <p>Encryption scheme:
 * <ol>
 *   <li>Generate a fresh AES-256 session key and 96-bit GCM IV for each message</li>
 *   <li>Encrypt the command JSON with AES-256-GCM (authenticated encryption)</li>
 *   <li>Encrypt the AES session key with the device's RSA-2048 public key (OAEP)</li>
 *   <li>Send three Base64 fields: {@code encKey}, {@code payload}, {@code iv}</li>
 * </ol>
 * </p>
 *
 * <p>OAEP parameters match Android Keystore's supported combination: SHA-256 as the main
 * hash, SHA-1 for MGF1. This is required for the Android-side decryption to succeed.</p>
 *
 * <p>Called by: {@link CommandDispatcher#send} and {@link CommandDispatcher#sendFreezeAlert}
 * when a device public key is configured.</p>
 */
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private final PublicKey publicKey;

    /**
     * Loads the device's RSA public key from a Base64-encoded DER string.
     *
     * <p>The Base64 string is the device's exported public key displayed in the Claw app
     * (MainActivity → Device Public Key section). It is set as the {@code DEVICE_PUBLIC_KEY}
     * environment variable on the relay server.</p>
     *
     * @param publicKeyBase64  Base64-encoded X.509 DER public key (no line breaks)
     * @throws Exception  if the key string is malformed or not a valid RSA public key
     */
    public CryptoService(String publicKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        log.info("CryptoService initialized — E2E encryption enabled.");
    }

    /**
     * Encrypts a plaintext JSON command string and returns the three fields to put in the FCM message.
     *
     * <p>A fresh AES session key and GCM IV are generated for every call — no session key reuse.
     * The 128-bit GCM authentication tag (appended to the ciphertext by {@code AES/GCM/NoPadding})
     * ensures the Android side detects any tampering before processing the command.</p>
     *
     * @param plaintext  the command JSON, e.g. {@code {"tool":"audio_manager","params":"...","chatId":"..."}}
     * @return  map with keys {@code encKey}, {@code payload}, {@code iv} — each Base64-encoded
     * @throws Exception  if encryption fails (should not happen with a valid public key)
     */
    public Map<String, String> encrypt(String plaintext) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey aesKey = kg.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] encryptedPayload = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec);
        byte[] encryptedKey = rsaCipher.doFinal(aesKey.getEncoded());

        return Map.of(
                "encKey",  Base64.getEncoder().encodeToString(encryptedKey),
                "payload", Base64.getEncoder().encodeToString(encryptedPayload),
                "iv",      Base64.getEncoder().encodeToString(iv)
        );
    }
}
