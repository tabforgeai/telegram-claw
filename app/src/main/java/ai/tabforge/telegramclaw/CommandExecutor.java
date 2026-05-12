package ai.tabforge.telegramclaw;

import android.content.Context;
import android.util.Log;

import ai.tabforge.telegramclaw.tool.AudioManagerTool;
import ai.tabforge.telegramclaw.tool.DeviceContextTool;
import ai.tabforge.telegramclaw.tool.NotificationSenderTool;

/**
 * Routes an incoming ClawCommand to the appropriate tool handler after checking
 * that the device owner has explicitly enabled that tool in {@link PermissionManifest}.
 *
 * <p>Analogy: like a switchboard operator who first checks an access list before
 * connecting a call — every command that arrives from the relay server passes through
 * this class. The operator checks the fuse box ({@link PermissionManifest}): if the
 * requested circuit is live, the call is connected (tool executed); if the fuse is out,
 * the call is silently dropped and logged as denied. No tool ever runs without the
 * device owner's explicit prior consent.</p>
 *
 * <p>System messages (tool names prefixed with {@code __}) bypass the permission check —
 * they are internal signals from the relay server (e.g. freeze alerts), not user-facing
 * tools, and are always handled regardless of PermissionManifest state.</p>
 *
 * <p>Tool implementations are stubs in Day 13. Each will be filled in as development
 * progresses:
 * <ul>
 *   <li>Day 15: {@code audio_manager}</li>
 *   <li>Day 16+: remaining tools</li>
 * </ul>
 * </p>
 *
 * <p>Called by: {@link ClawMessagingService#onMessageReceived} on every incoming FCM message.</p>
 */
public class CommandExecutor {

    private static final String TAG = "CommandExecutor";

    private final Context context;
    private final PermissionManifest permissionManifest;
    private final AuditLogger auditLogger;

    /**
     * Constructs a CommandExecutor wired to the app's PermissionManifest and AuditLogger.
     *
     * <p>Analogy: like a switchboard operator sitting down at their console — they need
     * the access list ({@link PermissionManifest}), the logbook ({@link AuditLogger}),
     * and the physical context (Android APIs) to do their job. All three are acquired here
     * once so {@link #execute} can focus purely on routing.</p>
     *
     * <p>Called by: {@link ClawMessagingService} once in {@code onCreate()}.</p>
     *
     * @param context  service or application context; used for PermissionManifest, AuditLogger,
     *                 and from Day 15, for accessing Android system services during tool execution
     */
    public CommandExecutor(Context context) {
        this.context = context;
        this.permissionManifest = new PermissionManifest(context);
        this.auditLogger = new AuditLogger(context);
    }

    /**
     * Checks the PermissionManifest and routes the command to the appropriate tool handler.
     *
     * <p>Analogy: like the switchboard operator's main action — look up the caller's access
     * level, then either connect the call or play a "not authorized" tone. System messages
     * (starting with {@code __}) skip the access list and go directly to their handler,
     * as they are internal relay-to-device signals, not commands from Person A.</p>
     *
     * <p>Called by: {@link ClawMessagingService#onMessageReceived}, on the Firebase SDK
     * background thread. Must complete within 20 seconds; long-running work will be moved
     * to a separate thread when tool implementations are added in Day 15+.</p>
     *
     * @param tool       the tool name from the FCM payload, e.g. {@code "audio_manager"}
     * @param paramsJson tool parameters as a JSON string, e.g. {@code {"stream":"RING","level":100}}
     * @param chatId     Telegram chat ID of Person A, needed to send the result reply
     */
    public void execute(String tool, String paramsJson, long chatId) {
        if (tool.startsWith("__")) {
            handleSystemMessage(tool, paramsJson);
            return;
        }

        if (!permissionManifest.isEnabled(tool)) {
            Log.w(TAG, "[DENIED] Tool '" + tool + "' is not enabled in PermissionManifest — command dropped.");
            auditLogger.log(AuditLogger.Status.DENIED, tool, chatId, "Tool not enabled in PermissionManifest");
            return;
        }

        Log.i(TAG, "[EXECUTE] tool=" + tool + " | params=" + paramsJson + " | chatId=" + chatId);

        switch (tool) {
            case "audio_manager":
                executeAudioManager(paramsJson, chatId);
                break;
            case "get_device_context":
                executeGetDeviceContext(paramsJson, chatId);
                break;
            case "media_control":
                executeMediaControl(paramsJson, chatId);
                break;
            case "notification_sender":
                executeNotificationSender(paramsJson, chatId);
                break;
            case "location_fetcher":
                executeLocationFetcher(paramsJson, chatId);
                break;
            case "camera_capture":
                executeCameraCapture(paramsJson, chatId);
                break;
            default:
                Log.w(TAG, "[UNKNOWN] Unrecognized tool: '" + tool + "' — ignoring.");
        }
    }

    // -------------------------------------------------------------------------
    // Tool stubs — implemented in Day 15+
    // -------------------------------------------------------------------------

    private void executeAudioManager(String paramsJson, long chatId) {
        try {
            String result = new AudioManagerTool(context).execute(paramsJson);
            Log.i(TAG, "[audio_manager] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "audio_manager", chatId, result);
        } catch (Exception e) {
            Log.e(TAG, "[audio_manager] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "audio_manager", chatId, e.getMessage());
        }
    }

    private void executeGetDeviceContext(String paramsJson, long chatId) {
        try {
            String result = new DeviceContextTool(context).execute();
            Log.i(TAG, "[get_device_context] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "get_device_context", chatId, result);
        } catch (Exception e) {
            Log.e(TAG, "[get_device_context] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "get_device_context", chatId, e.getMessage());
        }
    }

    private void executeMediaControl(String paramsJson, long chatId) {
        Log.i(TAG, "[STUB] media_control — Day 16.");
        // TODO Day 16: MediaSession or Intent to control active media player
    }

    private void executeNotificationSender(String paramsJson, long chatId) {
        try {
            String result = new NotificationSenderTool(context).execute(paramsJson);
            Log.i(TAG, "[notification_sender] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "notification_sender", chatId, result);
        } catch (Exception e) {
            Log.e(TAG, "[notification_sender] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "notification_sender", chatId, e.getMessage());
        }
    }

    private void executeLocationFetcher(String paramsJson, long chatId) {
        Log.i(TAG, "[STUB] location_fetcher — Day 16.");
        // TODO Day 16: show confirmation dialog (Protocol 1), then FusedLocationProviderClient
    }

    private void executeCameraCapture(String paramsJson, long chatId) {
        Log.i(TAG, "[STUB] camera_capture — Day 16.");
        // TODO Day 16: show confirmation dialog (Protocol 1), then CameraX capture + Telegram upload
    }

    // -------------------------------------------------------------------------
    // System message handler
    // -------------------------------------------------------------------------

    /**
     * Handles internal relay-to-device system messages that bypass PermissionManifest.
     *
     * <p>Analogy: like an emergency broadcast that overrides normal radio programming —
     * system messages are not user-facing commands but signals from the relay server itself.
     * Currently handles {@code __system_freeze} (rate limit alert); future system messages
     * will follow the same pattern.</p>
     *
     * @param tool       the system message type, e.g. {@code "__system_freeze"}
     * @param paramsJson additional data from the relay server (e.g. frozen sender name)
     */
    private void handleSystemMessage(String tool, String paramsJson) {
        switch (tool) {
            case "__system_freeze":
                Log.w(TAG, "[SYSTEM] Freeze alert received from relay server. Params: " + paramsJson);
                auditLogger.log(AuditLogger.Status.SUCCESS, "__system_freeze", 0L,
                        "Sender auto-frozen by relay rate limiter");
                // TODO Day 16: show fullscreen alert to device owner
                break;
            default:
                Log.w(TAG, "[SYSTEM] Unknown system message: '" + tool + "' — ignoring.");
        }
    }
}
