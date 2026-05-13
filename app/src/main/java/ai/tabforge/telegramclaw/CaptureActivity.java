package ai.tabforge.telegramclaw;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * Invisible background Activity that takes a single photo using CameraX.
 *
 * <p>Analogy: like a security camera that activates only when triggered — this Activity
 * has no visible UI (no preview, no shutter button). It opens, takes one photo, saves it
 * to the app's cache directory, notifies {@link CaptureGate}, and closes. The device
 * owner sees a brief flash of the app's background color, then the Activity dismisses itself.</p>
 *
 * <p>CameraX requires a {@link androidx.lifecycle.LifecycleOwner}, which is why photo capture
 * happens in an Activity rather than directly in a Service. {@link AppCompatActivity} provides
 * the lifecycle that CameraX binds its {@link ImageCapture} use case to.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link ai.tabforge.telegramclaw.tool.CameraCaptureTool} registers in {@link CaptureGate}
 *       and launches this Activity via {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK}</li>
 *   <li>Activity immediately starts the camera and takes one shot</li>
 *   <li>On success: saves JPEG to cache dir, calls {@link CaptureGate#resolve} with file path</li>
 *   <li>On failure: calls {@link CaptureGate#resolve} with {@code null}</li>
 *   <li>Activity finishes — CameraCaptureTool unblocks and returns the File to CommandExecutor</li>
 * </ol>
 * </p>
 */
public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_CAMERA  = "camera";
    public static final String EXTRA_FLASH   = "flash";

    private static final String TAG = "CaptureActivity";

    private long chatId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatId = getIntent().getLongExtra(EXTRA_CHAT_ID, 0L);
        String cameraParam = getIntent().getStringExtra(EXTRA_CAMERA);
        boolean flash = getIntent().getBooleanExtra(EXTRA_FLASH, false);

        CameraSelector selector = "FRONT".equals(cameraParam)
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        ImageCapture imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flash ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                .build();

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                provider.bindToLifecycle(this, selector, imageCapture);

                File outputFile = new File(getCacheDir(),
                        "claw_capture_" + System.currentTimeMillis() + ".jpg");
                ImageCapture.OutputFileOptions options =
                        new ImageCapture.OutputFileOptions.Builder(outputFile).build();

                imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                        new ImageCapture.OnImageSavedCallback() {
                            @Override
                            public void onImageSaved(ImageCapture.OutputFileResults results) {
                                Log.i(TAG, "[camera_capture] Saved: " + outputFile.getAbsolutePath());
                                provider.unbindAll();
                                CaptureGate.resolve(chatId, outputFile.getAbsolutePath());
                                finish();
                            }

                            @Override
                            public void onError(ImageCaptureException exception) {
                                Log.e(TAG, "[camera_capture] Capture error: " + exception.getMessage());
                                provider.unbindAll();
                                CaptureGate.resolve(chatId, null);
                                finish();
                            }
                        });

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "[camera_capture] Provider failed: " + e.getMessage());
                CaptureGate.resolve(chatId, null);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Safety net: if Activity is killed before resolving, unblock the waiting thread
        CaptureGate.resolve(chatId, null);
    }
}
