package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Sends a structured ClawCommand to an Android Claw device via Firebase Cloud Messaging (FCM).
 *
 * <p>Analogy: like a radio dispatcher at a taxi company — the dispatcher (this class) receives
 * a job order (ClawCommand) from the office, formats it into a transmission, and broadcasts it
 * over the radio (FCM) to the specific driver (Android device) identified by their call sign
 * (device token). The driver's radio (ForegroundService in Phase 2) picks it up and acts on it.</p>
 *
 * <p>The command is sent as an FCM <em>data message</em>, not a notification. Data messages
 * are always delivered to the app's handler, even when the device is in background or idle —
 * critical for a remote control use case where the device owner may not be actively using their phone.</p>
 *
 * <p>FCM message payload keys:
 * <ul>
 *   <li>{@code tool}    — the tool name (e.g. "audio_manager")</li>
 *   <li>{@code params}  — tool parameters as a JSON string</li>
 *   <li>{@code chatId}  — Telegram chat ID of the sender, so the device can route the reply</li>
 * </ul>
 * </p>
 *
 * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after IntentParser returns a ClawCommand.</p>
 */
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String deviceToken;

    /**
     * Initializes the Firebase Admin SDK and stores the target device FCM token.
     *
     * <p>Analogy: like plugging a radio transmitter into a power outlet and programming its
     * frequency — this constructor does the one-time hardware setup so that {@link #send}
     * can focus purely on the transmission itself. Firebase initialization is guarded against
     * double-init so the server can be restarted in tests without IllegalStateException.</p>
     *
     * <p>Called by: {@link Main#main} once at startup, before the HTTP server starts accepting
     * Telegram updates. If initialization fails (bad credentials path, malformed JSON),
     * an IOException is thrown and the server logs the error without crashing.</p>
     *
     * @param credentialsPath  absolute path to the Firebase service account JSON file
     *                         (downloaded from Firebase Console → Project Settings → Service accounts)
     * @param deviceToken      FCM registration token of the target Android device;
     *                         in Phase 2 this will come from a device registry,
     *                         for Day 5-6 smoke test it is provided via FCM_DEVICE_TOKEN env var
     * @throws IOException  if the credentials file cannot be read or is not valid JSON
     */
    public CommandDispatcher(String credentialsPath, String deviceToken) throws IOException {
        this.deviceToken = deviceToken;

        if (FirebaseApp.getApps().isEmpty()) {
            try (FileInputStream serviceAccount = new FileInputStream(credentialsPath)) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                FirebaseApp.initializeApp(options);
            }
            log.info("Firebase Admin SDK initialized from: {}", credentialsPath);
        }
    }

    /**
     * Packages a ClawCommand into an FCM data message and delivers it to the registered device.
     *
     * <p>Analogy: like a courier who takes a pre-filled package (ClawCommand), seals it,
     * writes the recipient's address (device token) on the label, and hands it to the
     * delivery service (Firebase). Firebase guarantees delivery even if the device is
     * temporarily offline, queuing the message until reconnection.</p>
     *
     * <p>On success, Firebase returns a unique message ID which we log for traceability.
     * On failure (invalid token, network error, auth problem), a {@link DispatchException}
     * is thrown — the caller logs it and continues, so one failed dispatch does not
     * crash the server or block future messages.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after a ClawCommand is parsed.</p>
     *
     * @param command  the structured command to deliver; must have a non-null toolName and parameters
     * @throws DispatchException  if FCM rejects the message or the network is unavailable
     */
    public void send(ClawCommand command) throws DispatchException {
        try {
            String paramsJson = mapper.writeValueAsString(command.getParameters());

            Message message = Message.builder()
                    .putData("tool", command.getToolName())
                    .putData("params", paramsJson)
                    .putData("chatId", String.valueOf(command.getSenderChatId()))
                    .setToken(deviceToken)
                    .build();

            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("[FCM] Dispatched '{}' → device. Message ID: {}", command.getToolName(), messageId);

        } catch (Exception e) {
            throw new DispatchException(
                    "FCM dispatch failed for tool '" + command.getToolName() + "': " + e.getMessage(), e);
        }
    }
}
