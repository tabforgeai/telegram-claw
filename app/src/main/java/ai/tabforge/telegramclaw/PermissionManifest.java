package ai.tabforge.telegramclaw;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Per-tool permission store that enforces Protocol 3: every tool is off by default,
 * and the device owner explicitly enables each one.
 *
 * <p>Analogy: like a fuse box in a house — each circuit (tool) has its own fuse (permission).
 * The box ships with all fuses pulled out (disabled). The homeowner (device owner) must
 * physically insert each fuse they want active. A command that arrives for a circuit whose
 * fuse is out is silently dropped by {@link CommandExecutor}, just as an unpowered circuit
 * carries no current regardless of what switch is flipped upstream.</p>
 *
 * <p>State is persisted in {@link SharedPreferences} under the name {@code "claw_permissions"},
 * so permissions survive app restarts and device reboots without requiring the owner to
 * re-configure after every reboot.</p>
 *
 * <p>The six controllable tools, all disabled by default:
 * <ul>
 *   <li>{@code get_device_context}  — battery, screen state, motion, ambient light</li>
 *   <li>{@code media_control}       — play, pause, skip, open Spotify/YouTube</li>
 *   <li>{@code audio_manager}       — set volume or force a loud alert ping</li>
 *   <li>{@code notification_sender} — show toast, notification, or fullscreen alert</li>
 *   <li>{@code location_fetcher}    — get GPS coordinates (always requires confirmation)</li>
 *   <li>{@code camera_capture}      — take a photo (always requires confirmation)</li>
 * </ul>
 * </p>
 *
 * <p>Used by: {@link CommandExecutor}, which checks {@link #isEnabled} before executing
 * any tool. The settings UI (Day 14) will call {@link #setEnabled} to let the device owner
 * configure the fuse box.</p>
 */
public class PermissionManifest {

    private static final String TAG = "PermissionManifest";
    private static final String PREFS_NAME = "claw_permissions";
    private static final String KEY_PREFIX = "perm_";

    private final SharedPreferences prefs;

    /**
     * Creates a PermissionManifest backed by the app's private SharedPreferences.
     *
     * <p>Analogy: like opening the fuse box cover — this gives access to the current
     * state of all fuses without changing any of them. The underlying SharedPreferences
     * file is created on first access and persisted across reboots automatically.</p>
     *
     * <p>Called by: {@link CommandExecutor} constructor.</p>
     *
     * @param context  application or service context used to access SharedPreferences
     */
    public PermissionManifest(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns whether the given tool is enabled by the device owner.
     *
     * <p>Analogy: like checking whether a circuit's fuse is inserted — a quick binary
     * check with no side effects. Returns {@code false} by default for any tool name
     * not explicitly enabled, including unknown tools.</p>
     *
     * <p>Called by: {@link CommandExecutor#execute} before routing any command.</p>
     *
     * @param toolName  the tool identifier, e.g. {@code "audio_manager"}
     * @return {@code true} if the device owner has explicitly enabled this tool;
     *         {@code false} by default
     */
    public boolean isEnabled(String toolName) {
        return prefs.getBoolean(KEY_PREFIX + toolName, false);
    }

    /**
     * Enables or disables a tool on behalf of the device owner.
     *
     * <p>Analogy: like inserting or pulling a fuse — the change takes effect immediately
     * and persists across reboots. Applied asynchronously via {@code apply()} so it
     * never blocks the calling thread.</p>
     *
     * <p>Called by: the settings UI (Day 14 MainActivity), which will show a toggle
     * for each of the six tools.</p>
     *
     * @param toolName  the tool identifier to enable or disable
     * @param enabled   {@code true} to allow execution, {@code false} to block it
     */
    public void setEnabled(String toolName, boolean enabled) {
        prefs.edit().putBoolean(KEY_PREFIX + toolName, enabled).apply();
        Log.i(TAG, "Tool '" + toolName + "' " + (enabled ? "enabled" : "disabled") + " by device owner.");
    }

    /**
     * Disables every tool atomically. Called by {@link KillSwitchReceiver} on kill-switch activation.
     * The device owner must re-enable tools manually via MainActivity to resume operation.
     */
    public void disableAll() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String tool : new String[]{
                "audio_manager", "get_device_context", "media_control",
                "notification_sender", "location_fetcher", "camera_capture",
                "app_launcher"}) {
            editor.putBoolean(KEY_PREFIX + tool, false);
        }
        editor.apply();
        Log.w(TAG, "All tools disabled by kill switch.");
    }
}
