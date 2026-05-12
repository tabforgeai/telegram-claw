package ai.tabforge.telegramclaw.tool;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.media.Ringtone;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implements the {@code audio_manager} tool — sets device volume or forces a loud alert ping
 * even when the device is on silent.
 *
 * <p>Analogy: like a fire alarm pull station — in normal circumstances a building respects
 * quiet hours, but the pull station bypasses all rules in an emergency. {@code force_ping=true}
 * is that pull station: it overrides Do Not Disturb and silent mode, then plays the loudest
 * possible system alarm so the device owner is guaranteed to hear it.</p>
 *
 * <p>Handles three audio streams as defined by the relay server's tool schema:
 * <ul>
 *   <li>{@code RING}  — ringer and notification volume ({@link AudioManager#STREAM_RING})</li>
 *   <li>{@code MEDIA} — music and video volume ({@link AudioManager#STREAM_MUSIC})</li>
 *   <li>{@code ALARM} — alarm volume ({@link AudioManager#STREAM_ALARM})</li>
 * </ul>
 * </p>
 *
 * <p>Force ping requires {@code ACCESS_NOTIFICATION_POLICY} to override Do Not Disturb.
 * If the permission is not granted, volume is still set and the alert is attempted,
 * but ringer mode may not change if DND is active.</p>
 *
 * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeAudioManager}.</p>
 */
public class AudioManagerTool {

    private static final String TAG = "AudioManagerTool";
    private static final int FORCE_PING_DURATION_MS = 4000;

    private final Context context;
    private final AudioManager audioManager;

    /**
     * Creates an AudioManagerTool bound to the given context.
     *
     * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor} on each audio_manager command.</p>
     *
     * @param context  service or application context for accessing AudioManager
     */
    public AudioManagerTool(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Parses the FCM params JSON and executes the requested audio action.
     *
     * <p>Analogy: like a sound engineer reading a stage rider — they parse the band's
     * requirements (stream, level, ping) and then configure the mixing desk accordingly.
     * Invalid or missing fields fall back to safe defaults rather than crashing.</p>
     *
     * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeAudioManager}.</p>
     *
     * @param paramsJson  JSON string from FCM payload, e.g. {@code {"stream":"RING","level":100,"force_ping":true}}
     * @return  human-readable result description for the AuditLogger and future Telegram reply
     * @throws IllegalArgumentException  if paramsJson is null or unparseable
     */
    public String execute(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            throw new IllegalArgumentException("audio_manager received null or empty params.");
        }

        try {
            JSONObject params = new JSONObject(paramsJson);
            String stream   = params.optString("stream", "RING");
            int    level    = params.optInt("level", 50);
            boolean forcePing = params.optBoolean("force_ping", false);

            int streamType = parseStreamType(stream);

            if (forcePing) {
                return executeForcePing(streamType, level, stream);
            } else {
                return executeSetVolume(streamType, level, stream);
            }

        } catch (JSONException e) {
            throw new IllegalArgumentException("Failed to parse audio_manager params: " + e.getMessage(), e);
        }
    }

    /**
     * Sets the volume of the requested stream to the given percentage level.
     *
     * <p>Converts the 0–100 percentage to Android's stream-specific index scale.
     * For example, if STREAM_RING has a max index of 7 and level=100, index is set to 7.</p>
     *
     * @param streamType  Android stream constant (e.g. {@link AudioManager#STREAM_RING})
     * @param level       volume percentage, 0–100
     * @param streamName  human-readable stream name for the result message
     * @return  result description, e.g. {@code "RING volume set to 80%."}
     */
    private String executeSetVolume(int streamType, int level, String streamName) {
        int maxIndex    = audioManager.getStreamMaxVolume(streamType);
        int targetIndex = Math.round(level / 100f * maxIndex);

        audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI);

        Log.i(TAG, "[audio_manager] " + streamName + " volume set to "
                + level + "% (index " + targetIndex + "/" + maxIndex + ").");
        return streamName + " volume set to " + level + "%.";
    }

    /**
     * Overrides silent/DND mode, sets volume to the requested level, and plays a loud alert.
     *
     * <p>Analogy: like a fire alarm that ignores the "mute all" policy — the ping forces
     * the device to make sound regardless of the user's current audio profile. The sequence:
     * <ol>
     *   <li>Check DND override permission; set ringer mode to NORMAL if granted</li>
     *   <li>Set the stream to the requested volume level</li>
     *   <li>Play the system alarm ringtone for {@value #FORCE_PING_DURATION_MS} ms, then stop</li>
     * </ol>
     * </p>
     *
     * @param streamType  Android stream constant for the volume step
     * @param level       volume percentage to set before the ping
     * @param streamName  human-readable stream name for the result message
     * @return  result description for the audit log
     */
    private String executeForcePing(int streamType, int level, String streamName) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm.isNotificationPolicyAccessGranted()) {
            audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            Log.i(TAG, "[audio_manager] Ringer mode set to NORMAL (DND overridden).");
        } else {
            Log.w(TAG, "[audio_manager] ACCESS_NOTIFICATION_POLICY not granted — cannot override DND.");
        }

        executeSetVolume(streamType, level, streamName);

        playAlertSound();

        Log.i(TAG, "[audio_manager] Force ping executed.");
        return "Force ping sent. " + streamName + " volume set to " + level + "%.";
    }

    /**
     * Plays the default alarm ringtone for {@value #FORCE_PING_DURATION_MS} ms, then stops.
     *
     * <p>Falls back to the default ringtone if no alarm ringtone is configured.
     * Errors are logged but never thrown — the ping best-effort is acceptable since
     * volume was already set, which provides partial functionality even if sound fails.</p>
     */
    private void playAlertSound() {
        try {
            Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            Ringtone ringtone = RingtoneManager.getRingtone(context, alertUri);
            if (ringtone == null) {
                Log.w(TAG, "[audio_manager] No ringtone available for force ping.");
                return;
            }

            ringtone.play();
            new Handler(Looper.getMainLooper()).postDelayed(ringtone::stop, FORCE_PING_DURATION_MS);

        } catch (Exception e) {
            Log.w(TAG, "[audio_manager] Could not play alert sound: " + e.getMessage());
        }
    }

    /**
     * Maps the relay server's stream name to an Android AudioManager stream constant.
     *
     * <p>The relay server tool schema defines three stream names: RING, MEDIA, ALARM.
     * Unrecognized values default to STREAM_RING as the safest fallback.</p>
     *
     * @param stream  stream name from FCM params, e.g. {@code "MEDIA"}
     * @return  Android stream constant, e.g. {@link AudioManager#STREAM_MUSIC}
     */
    private int parseStreamType(String stream) {
        switch (stream.toUpperCase()) {
            case "MEDIA": return AudioManager.STREAM_MUSIC;
            case "ALARM": return AudioManager.STREAM_ALARM;
            case "RING":
            default:      return AudioManager.STREAM_RING;
        }
    }
}
