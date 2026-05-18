package ai.tabforge.telegramclaw;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.io.File;

import ai.tabforge.telegramclaw.tool.AppLauncherTool;
import ai.tabforge.telegramclaw.tool.AudioManagerTool;
import ai.tabforge.telegramclaw.tool.CameraCaptureTool;
import ai.tabforge.telegramclaw.tool.DeviceContextTool;
import ai.tabforge.telegramclaw.tool.LocationFetcherTool;
import ai.tabforge.telegramclaw.tool.MediaControlTool;
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
    private final TelegramReplyClient telegramReplyClient;

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
        this.telegramReplyClient = new TelegramReplyClient(context);
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
            case "app_launcher":
                executeAppLauncher(paramsJson, chatId);
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
            telegramReplyClient.sendCallback(chatId, "audio_manager", result);
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
            telegramReplyClient.sendCallback(chatId, "get_device_context", result);
        } catch (Exception e) {
            Log.e(TAG, "[get_device_context] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "get_device_context", chatId, e.getMessage());
        }
    }

    private void executeMediaControl(String paramsJson, long chatId) {
        try {
            String result = new MediaControlTool(context).execute(paramsJson);
            Log.i(TAG, "[media_control] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "media_control", chatId, result);
            telegramReplyClient.sendCallback(chatId, "media_control", result);
        } catch (Exception e) {
            Log.e(TAG, "[media_control] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "media_control", chatId, e.getMessage());
        }
    }

    private void executeNotificationSender(String paramsJson, long chatId) {
        try {
            String result = new NotificationSenderTool(context).execute(paramsJson);
            Log.i(TAG, "[notification_sender] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "notification_sender", chatId, result);
            telegramReplyClient.sendCallback(chatId, "notification_sender", result);
        } catch (Exception e) {
            Log.e(TAG, "[notification_sender] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "notification_sender", chatId, e.getMessage());
        }
    }

    private void executeLocationFetcher(String paramsJson, long chatId) {
        boolean approved = requestConfirmation("location_fetcher", chatId);
        if (!approved) {
            String reason = "Access denied by device owner.";
            auditLogger.log(AuditLogger.Status.DENIED, "location_fetcher", chatId, reason);
            telegramReplyClient.sendCallback(chatId, "location_fetcher", reason);
            return;
        }
        try {
            String result = new LocationFetcherTool(context).execute();
            Log.i(TAG, "[location_fetcher] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "location_fetcher", chatId, result);
            telegramReplyClient.sendCallback(chatId, "location_fetcher", result);
        } catch (Exception e) {
            Log.e(TAG, "[location_fetcher] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "location_fetcher", chatId, e.getMessage());
        }
    }

    /**
     * Shows a confirmation dialog to the device owner and blocks until they respond or
     * the 60-second auto-deny timer fires.
     *
     * <p>Analogy: like a doorbell that the delivery person rings — this method rings the bell
     * (launches ConfirmationActivity) and then stands at the door waiting (blocks on the future).
     * The device owner either opens the door (returns true) or ignores it until the auto-deny
     * kicks in (returns false after 65 seconds).</p>
     *
     * @param toolName  human-readable tool name shown in the dialog
     * @param chatId    used to match the dialog response to this specific request
     * @return  {@code true} if the owner tapped Allow; {@code false} for Deny or timeout
     */
    private boolean requestConfirmation(String toolName, long chatId) {
        CompletableFuture<Boolean> future = ConfirmationGate.register(chatId);

        Intent intent = new Intent(context, ConfirmationActivity.class);
        intent.putExtra(ConfirmationActivity.EXTRA_TOOL_NAME, toolName);
        intent.putExtra(ConfirmationActivity.EXTRA_CHAT_ID, chatId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        try {
            // 65s timeout > 60s dialog timeout — ensures the future always completes
            return future.get(65, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "[confirmation] Wait failed for " + toolName + ": " + e.getMessage());
            ConfirmationGate.resolve(chatId, false);
            return false;
        }
    }

    private void executeCameraCapture(String paramsJson, long chatId) {
        boolean approved = requestConfirmation("camera_capture", chatId);
        if (!approved) {
            String reason = "Access denied by device owner.";
            auditLogger.log(AuditLogger.Status.DENIED, "camera_capture", chatId, reason);
            telegramReplyClient.sendCallback(chatId, "camera_capture", reason);
            return;
        }
        try {
            File photo = new CameraCaptureTool(context).execute(paramsJson, chatId);
            if (photo == null) {
                String err = "Photo capture failed — camera may be unavailable or in use.";
                auditLogger.log(AuditLogger.Status.ERROR, "camera_capture", chatId, err);
                telegramReplyClient.sendReply(chatId, err);
                return;
            }
            auditLogger.log(AuditLogger.Status.SUCCESS, "camera_capture", chatId,
                    "Photo captured: " + photo.getName());
            telegramReplyClient.sendPhoto(chatId, photo);
            photo.delete();
        } catch (Exception e) {
            Log.e(TAG, "[camera_capture] Failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "camera_capture", chatId, e.getMessage());
        }
    }

    private void executeAppLauncher(String paramsJson, long chatId) {
        try {
            String result = new AppLauncherTool(context).execute(paramsJson);
            Log.i(TAG, "[app_launcher] " + result);
            auditLogger.log(AuditLogger.Status.SUCCESS, "app_launcher", chatId, result);
            telegramReplyClient.sendCallback(chatId, "app_launcher", result);
        } catch (Exception e) {
            Log.e(TAG, "[app_launcher] Execution failed: " + e.getMessage());
            auditLogger.log(AuditLogger.Status.ERROR, "app_launcher", chatId, e.getMessage());
        }
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
