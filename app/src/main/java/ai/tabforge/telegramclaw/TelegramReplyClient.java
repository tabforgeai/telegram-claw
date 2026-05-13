package ai.tabforge.telegramclaw;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends tool execution results back to Person A via the Telegram Bot API.
 *
 * <p>Analogy: like a delivery service that returns a response envelope to the original sender —
 * after the Android device executes a command, this client calls Telegram's
 * {@code sendMessage} endpoint directly with Person A's {@code chatId} and the result text,
 * closing the response loop that FCM alone cannot complete. Without this, query tools like
 * {@code get_device_context} would execute silently and Person A would never see the answer.</p>
 *
 * <p>The bot token is stored in {@link android.content.SharedPreferences} and entered by the
 * device owner in {@link MainActivity}. If no token is configured, replies are skipped with a
 * log warning rather than crashing — the app remains functional, just without the reply loop.</p>
 *
 * <p>All HTTP calls are made synchronously on the calling thread. Because
 * {@link CommandExecutor#execute} is always invoked from the Firebase SDK background thread
 * (never the main thread), no additional thread management is needed here.</p>
 *
 * <p>Called by: {@link CommandExecutor} after each successful tool execution.</p>
 */
public class TelegramReplyClient {

    private static final String TAG       = "TelegramReplyClient";
    private static final String PREFS     = "claw_bot_config";
    private static final String KEY_TOKEN = "bot_token";
    private static final int    TIMEOUT   = 10_000;

    private final Context context;

    /**
     * Creates a TelegramReplyClient bound to the given context.
     *
     * @param context  service or application context for SharedPreferences access
     */
    public TelegramReplyClient(Context context) {
        this.context = context;
    }

    /**
     * Sends a text reply to Person A via the Telegram Bot API {@code sendMessage} endpoint.
     *
     * <p>Analogy: like mailing a letter back — constructs the JSON payload, opens an HTTPS
     * connection to Telegram's servers, and posts the message. If the bot token has not yet
     * been configured by the device owner, the call is skipped and a warning is logged.
     * Network errors are caught and logged; they do not propagate to the caller.</p>
     *
     * <p>Must be called on a background thread (not main thread) to avoid
     * {@link android.os.NetworkOnMainThreadException}. The Firebase SDK background thread
     * used by {@link CommandExecutor} satisfies this requirement.</p>
     *
     * @param chatId  Telegram chat ID of Person A (sourced from the original FCM payload)
     * @param text    the result text to send, e.g. the device state or a confirmation message
     */
    public void sendReply(long chatId, String text) {
        String token = loadBotToken(context);
        if (token.isBlank()) {
            Log.w(TAG, "Bot token not configured — skipping reply to chatId=" + chatId);
            return;
        }

        try {
            String apiUrl = "https://api.telegram.org/bot" + token + "/sendMessage";

            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Reply sent to chatId=" + chatId + ": \"" + text + "\"");
            } else {
                Log.w(TAG, "Telegram API returned HTTP " + code + " for chatId=" + chatId);
            }
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Failed to send reply to chatId=" + chatId + ": " + e.getMessage());
        }
    }

    /**
     * Saves the bot token to SharedPreferences, trimming leading/trailing whitespace.
     *
     * <p>Called by {@link MainActivity} when the device owner taps Save Token. Trimming
     * prevents silent failures caused by a trailing newline or space when copy-pasting
     * from @BotFather.</p>
     *
     * @param context  any valid Context
     * @param token    the full bot token from @BotFather, e.g. {@code 123456789:ABCdef...}
     */
    public static void saveBotToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_TOKEN, token.trim())
               .apply();
    }

    /**
     * Loads the bot token from SharedPreferences.
     *
     * <p>Called by {@link MainActivity} to pre-fill the token field on startup, and by
     * {@link #sendReply} before every HTTP call.</p>
     *
     * @param context  any valid Context
     * @return  the saved token, or an empty string if not yet configured
     */
    public static String loadBotToken(Context context) {
        String token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                              .getString(KEY_TOKEN, "");
        return token != null ? token : "";
    }
}
