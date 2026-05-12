package ai.tabforge.telegramclaw.tool;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import ai.tabforge.telegramclaw.R;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implements the {@code notification_sender} tool — displays a message on the device screen
 * as a toast, status bar notification, or high-priority heads-up notification.
 *
 * <p>Analogy: like leaving a sticky note on someone's door — the message appears on their
 * device without requiring any action from them. The intrusiveness level is chosen by
 * Person A via {@code display_mode}: TOAST for a gentle nudge, NOTIFICATION for a persistent
 * message they can read later, FULLSCREEN for an urgent alert that demands immediate attention.</p>
 *
 * <p>Params (from FCM payload, as defined by the relay server tool schema):
 * <ul>
 *   <li>{@code message}      — the text to display on the device</li>
 *   <li>{@code display_mode} — {@code "TOAST"}, {@code "NOTIFICATION"}, or {@code "FULLSCREEN"}</li>
 *   <li>{@code sender_name}  — Person A's display name, shown in the notification header</li>
 * </ul>
 * </p>
 *
 * <p>On Android 13+ (API 33), {@code POST_NOTIFICATIONS} runtime permission is required
 * for NOTIFICATION and FULLSCREEN modes. If not granted, both fall back to TOAST so the
 * message is never silently dropped.</p>
 *
 * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeNotificationSender}.</p>
 */
public class NotificationSenderTool {

    private static final String TAG = "NotificationSenderTool";
    private static final String CHANNEL_ID   = "claw_messages_channel";
    private static final String CHANNEL_NAME = "Claw Messages";
    private static final int    NOTIFICATION_ID = 100;

    private final Context context;
    private final NotificationManager notificationManager;

    /**
     * Creates a NotificationSenderTool and ensures the message notification channel exists.
     *
     * <p>The channel is created with {@code IMPORTANCE_HIGH} so that FULLSCREEN and NOTIFICATION
     * messages appear as heads-up banners by default. The channel is only created once;
     * subsequent calls with the same channel ID are no-ops.</p>
     *
     * @param context  service or application context
     */
    public NotificationSenderTool(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createMessageChannel();
    }

    /**
     * Parses the FCM params and displays the message using the requested display mode.
     *
     * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeNotificationSender},
     * on the Firebase SDK background thread. Toast and notification are dispatched to the
     * main thread internally.</p>
     *
     * @param paramsJson  JSON string from FCM payload
     * @return  human-readable result for the AuditLogger
     * @throws IllegalArgumentException  if paramsJson is null or unparseable
     */
    public String execute(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            throw new IllegalArgumentException("notification_sender received null or empty params.");
        }

        try {
            JSONObject params = new JSONObject(paramsJson);
            String message     = params.optString("message", "(no message)");
            String displayMode = params.optString("display_mode", "TOAST").toUpperCase();
            String senderName  = params.optString("sender_name", "Someone");

            switch (displayMode) {
                case "NOTIFICATION":
                    return sendNotification(message, senderName, false);
                case "FULLSCREEN":
                    return sendNotification(message, senderName, true);
                case "TOAST":
                default:
                    return sendToast(message);
            }

        } catch (JSONException e) {
            throw new IllegalArgumentException("Failed to parse notification_sender params: " + e.getMessage(), e);
        }
    }

    /**
     * Shows a short-lived Toast on the main thread.
     *
     * <p>Toast does not require any runtime permission and works on all API levels.
     * It is the fallback mode when {@code POST_NOTIFICATIONS} is not granted.</p>
     *
     * @param message  text to display
     * @return  result description
     */
    private String sendToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());

        Log.i(TAG, "[notification_sender] Toast shown: \"" + message + "\"");
        return "Toast displayed: \"" + message + "\"";
    }

    /**
     * Posts a status bar notification, optionally with maximum priority for a heads-up display.
     *
     * <p>On Android 13+, checks {@code POST_NOTIFICATIONS} permission first and falls back to
     * Toast if not granted. {@code highPriority=true} (FULLSCREEN mode) sets
     * {@code PRIORITY_MAX} and {@code CATEGORY_CALL} to request a persistent heads-up banner.</p>
     *
     * @param message      text body of the notification
     * @param senderName   shown as the notification title (who sent the message)
     * @param highPriority {@code true} for FULLSCREEN mode (heads-up banner), {@code false} for standard
     * @return  result description
     */
    private String sendNotification(String message, String senderName, boolean highPriority) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[notification_sender] POST_NOTIFICATIONS not granted — falling back to Toast.");
                return sendToast(message) + " (fell back from NOTIFICATION — grant POST_NOTIFICATIONS in app settings)";
            }
        }

        int priority = highPriority
                ? NotificationCompat.PRIORITY_MAX
                : NotificationCompat.PRIORITY_DEFAULT;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(senderName)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(priority)
                .setAutoCancel(true);

        if (highPriority) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL);
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build());

        String mode = highPriority ? "FULLSCREEN" : "NOTIFICATION";
        Log.i(TAG, "[notification_sender] " + mode + " posted from " + senderName + ": \"" + message + "\"");
        return mode + " notification posted from " + senderName + ": \"" + message + "\"";
    }

    /**
     * Creates the notification channel for Claw messages.
     *
     * <p>{@code IMPORTANCE_HIGH} enables heads-up banners for incoming messages.
     * Creating the same channel ID again is a no-op, so this is safe to call in the constructor.</p>
     */
    private void createMessageChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Messages sent to this device via Telegram Claw.");
        notificationManager.createNotificationChannel(channel);
    }
}
