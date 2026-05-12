package ai.tabforge.telegramclaw;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * FCM listener that receives data messages dispatched by the Telegram Claw relay server.
 *
 * <p>Analogy: like a radio operator at a military base — this service sits on a dedicated
 * frequency (Firebase Cloud Messaging) 24/7, waiting for incoming transmissions. When a message
 * arrives from the relay server (the command center), the operator reads it, logs the contents,
 * and hands it off to the appropriate department for execution. In Day 13, that department
 * will be {@code CommandExecutor}; for now the operator logs and holds.</p>
 *
 * <p>FCM delivers messages to this service even when the device is idle or the screen is off,
 * which is the core capability that makes Telegram Claw work as a remote control system.
 * The relay server sends <em>data messages</em> (not notification messages), so delivery always
 * routes here — never to the system notification tray directly.</p>
 *
 * <p>Expected FCM data payload keys (set by {@code CommandDispatcher} in the relay server):
 * <ul>
 *   <li>{@code tool}    — tool name, e.g. {@code "audio_manager"} or {@code "__system_freeze"}</li>
 *   <li>{@code params}  — tool parameters as a JSON string, e.g. {@code {"stream":"RING","level":100}}</li>
 *   <li>{@code chatId}  — Telegram chat ID of Person A, so the reply can be routed back</li>
 * </ul>
 * </p>
 *
 * <p>Registered in AndroidManifest.xml with the {@code com.google.firebase.MESSAGING_EVENT}
 * intent filter, which tells the Firebase SDK to deliver all incoming FCM messages here.</p>
 */
public class ClawMessagingService extends FirebaseMessagingService {

    private static final String TAG = "ClawMessagingService";

    private CommandExecutor commandExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        commandExecutor = new CommandExecutor(this);
    }

    /**
     * Called by the Firebase SDK when a data message arrives from the relay server.
     *
     * <p>Analogy: like a dispatcher answering the radio — the moment a transmission comes in
     * (FCM data message), this method fires. The dispatcher reads who sent it and what was said
     * (tool name, parameters, chat ID), logs it for the audit trail, and prepares to route it.
     * In Day 13, routing means passing the command to {@code CommandExecutor} for execution.</p>
     *
     * <p>This method runs on a background thread provided by the Firebase SDK. It has approximately
     * 20 seconds to complete before the OS considers it timed out. All work here must be fast;
     * long-running execution will move to {@code CommandExecutor} in Day 13.</p>
     *
     * <p>Called by: Firebase SDK on every incoming FCM data message.</p>
     *
     * @param message  the incoming FCM message; {@code getData()} contains the relay server payload
     */
    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();

        String tool   = data.get("tool");
        String params = data.get("params");
        String chatId = data.get("chatId");

        if (tool == null || tool.isBlank()) {
            Log.w(TAG, "[FCM] Received message with no tool field — ignoring.");
            return;
        }

        Log.i(TAG, "[FCM] Command received | tool=" + tool
                + " | params=" + params
                + " | chatId=" + chatId);

        long chatIdLong = 0L;
        if (chatId != null) {
            try {
                chatIdLong = Long.parseLong(chatId);
            } catch (NumberFormatException e) {
                Log.w(TAG, "[FCM] Invalid chatId value: " + chatId);
            }
        }

        commandExecutor.execute(tool, params, chatIdLong);
    }

    /**
     * Called by the Firebase SDK when the FCM registration token is refreshed.
     *
     * <p>Analogy: like a phone number changing — the device's FCM address (token) occasionally
     * rotates for security reasons. When it does, the relay server's stored token becomes invalid
     * and FCM messages stop arriving. This method logs the new token so the device owner can
     * update {@code FCM_DEVICE_TOKEN} in the relay server environment variables.</p>
     *
     * <p>In Phase 3, this will trigger an automatic re-registration: the new token will be
     * sent to the relay server via an out-of-band channel without any manual step required.</p>
     *
     * <p>Called by: Firebase SDK when the token rotates (rare — typically on app reinstall
     * or after extended inactivity).</p>
     *
     * @param token  the new FCM registration token for this device
     */
    @Override
    public void onNewToken(String token) {
        Log.i(TAG, "[FCM] Token refreshed: " + token);
        // TODO Phase 3: send new token to relay server automatically
    }
}
