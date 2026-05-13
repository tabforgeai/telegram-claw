package ai.tabforge.telegramclaw.tool;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import ai.tabforge.telegramclaw.CaptureActivity;
import ai.tabforge.telegramclaw.CaptureGate;

/**
 * Implements the {@code camera_capture} tool — takes a photo with the device camera
 * and returns the saved JPEG file to {@link ai.tabforge.telegramclaw.CommandExecutor}
 * for upload to Telegram.
 *
 * <p>Analogy: like a remote shutter release cable on a film camera — this tool triggers
 * the shutter (launches {@link CaptureActivity}) and waits for the film to be developed
 * (blocks until {@link CaptureGate} delivers the file path). The result is a JPEG saved
 * in the app's cache directory, ready to be uploaded.</p>
 *
 * <p>Photo capture requires a CameraX {@link androidx.lifecycle.LifecycleOwner}, which
 * is why this tool delegates to {@link CaptureActivity} rather than running the camera
 * directly. The background thread blocks on a {@link CompletableFuture} while the Activity
 * works on the main thread — the same pattern used by {@link ai.tabforge.telegramclaw.ConfirmationGate}
 * for confirmation dialogs.</p>
 *
 * <p>Always requires prior confirmation from the device owner — called only after
 * {@link ai.tabforge.telegramclaw.CommandExecutor#requestConfirmation} returns {@code true}.</p>
 *
 * <p>Params (from FCM payload):
 * <ul>
 *   <li>{@code camera} — {@code "FRONT"} or {@code "BACK"} (default: {@code "BACK"})</li>
 *   <li>{@code flash}  — {@code true} or {@code false} (default: {@code false})</li>
 * </ul>
 * </p>
 */
public class CameraCaptureTool {

    private static final String TAG        = "CameraCaptureTool";
    private static final long   TIMEOUT_S  = 20L;

    private final Context context;

    public CameraCaptureTool(Context context) {
        this.context = context;
    }

    /**
     * Launches {@link CaptureActivity}, waits for the photo, and returns the saved JPEG file.
     *
     * @param paramsJson  JSON string with optional {@code camera} and {@code flash} fields
     * @param chatId      used as the key in {@link CaptureGate} to match this request
     * @return  the saved JPEG {@link File}, or {@code null} if capture failed or timed out
     */
    public File execute(String paramsJson, long chatId) {
        String camera = "BACK";
        boolean flash = false;

        try {
            JSONObject params = new JSONObject(paramsJson != null ? paramsJson : "{}");
            camera = params.optString("camera", "BACK").toUpperCase();
            flash  = params.optBoolean("flash", false);
        } catch (JSONException e) {
            Log.w(TAG, "Could not parse params, using defaults: " + e.getMessage());
        }

        CompletableFuture<String> future = CaptureGate.register(chatId);

        Intent intent = new Intent(context, CaptureActivity.class);
        intent.putExtra(CaptureActivity.EXTRA_CHAT_ID, chatId);
        intent.putExtra(CaptureActivity.EXTRA_CAMERA, camera);
        intent.putExtra(CaptureActivity.EXTRA_FLASH, flash);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        try {
            String filePath = future.get(TIMEOUT_S, TimeUnit.SECONDS);
            if (filePath == null) {
                Log.e(TAG, "[camera_capture] CaptureActivity returned null — camera error.");
                return null;
            }
            File file = new File(filePath);
            return file.exists() ? file : null;
        } catch (Exception e) {
            Log.e(TAG, "[camera_capture] Wait timed out or interrupted: " + e.getMessage());
            CaptureGate.resolve(chatId, null);
            return null;
        }
    }
}
