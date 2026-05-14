package ai.tabforge.telegramclaw;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Generates one-time pairing PINs that let a new Telegram user self-authorize at runtime.
 *
 * <p>Analogy: like a bank issuing a one-time activation code for a new card — the device
 * owner generates the code here, shares it out-of-band with the intended person, and that
 * person types it once in Telegram to unlock access. The code is useless after 10 minutes
 * or after first use, whichever comes first.</p>
 *
 * <p>Protocol 6 (Out-of-Band Pairing): PIN is generated on device, registered with the
 * relay server via POST /pair, then shared with the new user through any side channel
 * (WhatsApp, in person, etc.). No clipboard or QR scanning required.</p>
 *
 * <p>Called by: {@link MainActivity} when the device owner taps "Generate PIN".</p>
 */
public class PairingManager {

    private static final String TAG = "PairingManager";
    private static final String PREFS = "claw_pairing";
    private static final String KEY_PIN = "active_pin";
    private static final String KEY_EXPIRY = "pin_expiry";
    private static final long PIN_TTL_MS = 10 * 60 * 1000L;

    // No O/0 or I/1 — visually ambiguous characters excluded
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PIN_LENGTH = 6;

    private final Context context;

    public PairingManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Generates a fresh 6-character PIN, stores it locally with a 10-minute expiry, and
     * registers it with the relay server in a background thread.
     *
     * <p>Returns the PIN immediately so the UI can display it without waiting for the
     * network POST to complete. If the POST fails, the PIN is still shown locally —
     * the device owner can tap Generate again to retry.</p>
     *
     * @param relayUrl  the relay server base URL (e.g. "https://abc123.ngrok.io")
     * @return  the generated 6-character PIN
     */
    public String generateAndRegisterPin(String relayUrl) {
        String pin = generatePin();
        long expiry = System.currentTimeMillis() + PIN_TTL_MS;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PIN, pin).putLong(KEY_EXPIRY, expiry).apply();

        new Thread(() -> {
            try {
                postPinToRelay(relayUrl, pin);
                Log.i(TAG, "PIN " + pin + " registered with relay.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to register PIN with relay: " + e.getMessage());
            }
        }).start();

        return pin;
    }

    /**
     * Returns the currently active PIN and its expiry, or null if no PIN is active or
     * the stored PIN has already expired.
     *
     * @return  an {@link ActivePin} with the PIN string and remaining time, or null
     */
    public ActivePin getActivePin() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pin = prefs.getString(KEY_PIN, null);
        long expiry = prefs.getLong(KEY_EXPIRY, 0L);
        if (pin == null || System.currentTimeMillis() > expiry) return null;
        return new ActivePin(pin, expiry);
    }

    private String generatePin() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PIN_LENGTH);
        for (int i = 0; i < PIN_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private void postPinToRelay(String relayUrl, String pin) throws Exception {
        String endpoint = relayUrl.replaceAll("/$", "") + "/pair";
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        byte[] body = ("pin=" + pin).getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("Relay returned HTTP " + code);
        }
    }

    /**
     * Holds a valid PIN and its absolute expiry timestamp.
     */
    public static class ActivePin {
        public final String pin;
        public final long expiryMs;

        ActivePin(String pin, long expiryMs) {
            this.pin = pin;
            this.expiryMs = expiryMs;
        }

        public long remainingMs() {
            return expiryMs - System.currentTimeMillis();
        }
    }
}
