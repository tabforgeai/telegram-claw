package ai.tabforge.telegramclaw.tool;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implements the {@code media_control} tool — controls whatever media player is
 * currently active on the device: play, pause, skip, or open a Spotify/YouTube URL.
 *
 * <p>Analogy: like a remote control for someone else's TV — you decide what plays without
 * touching their device. The TV (Android media player) responds to the same key signals it
 * would receive from physical hardware buttons; this tool sends those signals programmatically
 * via {@link AudioManager#dispatchMediaKeyEvent}.</p>
 *
 * <p>Params (from FCM payload):
 * <ul>
 *   <li>{@code action} — {@code "PLAY"}, {@code "PAUSE"}, {@code "SKIP"}, or {@code "OPEN_URL"}</li>
 *   <li>{@code url}    — Spotify or YouTube URL; required when {@code action} is {@code "OPEN_URL"}</li>
 * </ul>
 * </p>
 *
 * <p>PLAY, PAUSE, and SKIP work with any active media session (Spotify, YouTube Music,
 * podcast apps, etc.) — they dispatch standard Android media key events that the system
 * routes to whichever app currently holds the audio focus. No special permission is required.</p>
 *
 * <p>OPEN_URL launches an Intent with {@code ACTION_VIEW} — Android presents the URL to the
 * appropriate installed app (Spotify for spotify:// links, YouTube for youtu.be links, etc.).
 * {@code FLAG_ACTIVITY_NEW_TASK} is required because the call originates from a Service.</p>
 *
 * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeMediaControl}.</p>
 */
public class MediaControlTool {

    private static final String TAG = "MediaControlTool";

    private final Context context;

    /**
     * Creates a MediaControlTool bound to the given context.
     *
     * @param context  service or application context for AudioManager and Intent dispatch
     */
    public MediaControlTool(Context context) {
        this.context = context;
    }

    /**
     * Parses the FCM params and performs the requested media action.
     *
     * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeMediaControl},
     * on the Firebase SDK background thread.</p>
     *
     * @param paramsJson  JSON string from FCM payload
     * @return  human-readable result for the AuditLogger and callback reply
     * @throws IllegalArgumentException  if params are null, unparseable, or action is unknown
     */
    public String execute(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            throw new IllegalArgumentException("media_control received null or empty params.");
        }

        try {
            JSONObject params = new JSONObject(paramsJson);
            String action = params.optString("action", "").toUpperCase();
            String url    = params.optString("url", "");

            switch (action) {
                case "PLAY":
                    return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "PLAY");
                case "PAUSE":
                    return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "PAUSE");
                case "SKIP":
                    return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "SKIP");
                case "OPEN_URL":
                    if (url.isBlank()) {
                        throw new IllegalArgumentException("OPEN_URL requires a non-empty 'url' parameter.");
                    }
                    return openUrl(url);
                default:
                    throw new IllegalArgumentException("Unknown media_control action: \"" + action + "\"");
            }

        } catch (JSONException e) {
            throw new IllegalArgumentException("Failed to parse media_control params: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a media key DOWN + UP event pair to the active media session.
     *
     * <p>Analogy: like pressing and releasing a physical media button — Android routes the
     * key pair to whichever app currently holds audio focus (Spotify, YouTube Music, etc.).
     * Both DOWN and UP are required; a DOWN without UP is treated as a long-press by some apps.</p>
     *
     * @param keyCode     Android key code, e.g. {@link KeyEvent#KEYCODE_MEDIA_PLAY}
     * @param actionName  human-readable name for logging and the return value
     * @return  result description
     */
    private String dispatchMediaKey(int keyCode, String actionName) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   keyCode));
        Log.i(TAG, "[media_control] " + actionName + " dispatched to active media session.");
        return actionName + " sent to active media player.";
    }

    /**
     * Opens a Spotify or YouTube URL in the appropriate installed app.
     *
     * <p>Analogy: like handing someone a concert ticket — the system (Android) looks at
     * the ticket (URL) and routes it to the right venue (Spotify for spotify:// links,
     * YouTube for youtu.be links). If no matching app is installed, the system opens a browser.</p>
     *
     * <p>{@link Intent#FLAG_ACTIVITY_NEW_TASK} is required because this call originates
     * from a Service, not an Activity.</p>
     *
     * @param url  the URL to open, e.g. a Spotify track link or YouTube video URL
     * @return  result description including the URL that was opened
     */
    private String openUrl(String url) {
        String resolved = toSpotifyUri(url);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(resolved));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "[media_control] OPEN_URL launched: " + resolved);
            return "Opened URL: " + resolved;
        } catch (ActivityNotFoundException e) {
            // Spotify not installed — fall back to YouTube search if we have a query
            if (resolved.startsWith("spotify:search:")) {
                String query = resolved.substring("spotify:search:".length())
                        .replace(" ", "+");
                String ytUrl = "https://www.youtube.com/results?search_query=" + query;
                Log.w(TAG, "[media_control] Spotify not installed, falling back to YouTube: " + ytUrl);
                return openUrl(ytUrl);
            }
            Log.w(TAG, "[media_control] No app found for: " + resolved);
            return "Could not open: no app installed to handle this URL. " +
                    "Try asking to play on YouTube instead.";
        }
    }

    // Converts open.spotify.com HTTPS URLs to spotify: URI scheme so the Spotify app
    // handles them directly instead of falling back to a browser.
    // e.g. https://open.spotify.com/track/ABC → spotify:track:ABC
    private static String toSpotifyUri(String url) {
        if (!url.startsWith("https://open.spotify.com/")) return url;
        String path = url.substring("https://open.spotify.com/".length());
        int q = path.indexOf('?');
        if (q != -1) path = path.substring(0, q);
        return "spotify:" + path.replace('/', ':');
    }
}
