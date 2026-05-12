package ai.tabforge.telegramclaw;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Setup and monitoring screen for the device owner.
 *
 * <p>Analogy: like a control panel in a server room — this activity is not what end users
 * interact with (Person A uses Telegram), but what the device owner uses to configure Claw
 * and verify it is working correctly. Three sections:
 * <ol>
 *   <li><b>FCM Device Token</b> — displays the token to copy into the relay server config</li>
 *   <li><b>Permissions</b> — per-tool on/off toggles (Protocol 3: Permission Manifest)</li>
 *   <li><b>Audit Log</b> — every executed or denied command (Protocol 5: Transparent Audit Log)</li>
 * </ol>
 * </p>
 *
 * <p>The ForegroundService is started here as well, so opening the app is sufficient to
 * ensure Claw is running during development. On production devices, {@link BootReceiver}
 * handles auto-start without requiring the owner to open the app after reboot.</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TelegramClaw";

    private PermissionManifest permissionManifest;
    private AuditLogger auditLogger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startForegroundService(new Intent(this, ClawForegroundService.class));

        permissionManifest = new PermissionManifest(this);
        auditLogger = new AuditLogger(this);

        setupTokenSection();
        setupPermissionToggles();
        setupAuditLogSection();
    }

    /**
     * Fetches the FCM registration token and displays it for the device owner to copy.
     *
     * <p>The token must be set as {@code FCM_DEVICE_TOKEN} on the relay server so that
     * the relay knows where to send FCM commands. If the token refreshes ({@link ClawMessagingService#onNewToken}),
     * the relay server env var must be updated manually until Phase 3 auto-registration is added.</p>
     */
    private void setupTokenSection() {
        TextView tokenText = findViewById(R.id.token_text);

        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "FCM token fetch failed", task.getException());
                        tokenText.setText("Error: " + task.getException().getMessage());
                        return;
                    }
                    String token = task.getResult();
                    Log.d(TAG, "FCM Token: " + token);
                    tokenText.setText(token);
                });
    }

    /**
     * Wires the six permission toggles to {@link PermissionManifest}.
     *
     * <p>Each toggle reflects the current stored state on activity start.
     * Changes take effect immediately and persist across reboots — no Save button needed.</p>
     */
    private void setupPermissionToggles() {
        wireSwitch(R.id.switch_audio_manager,       "audio_manager");
        wireSwitch(R.id.switch_get_device_context,  "get_device_context");
        wireSwitch(R.id.switch_media_control,       "media_control");
        wireSwitch(R.id.switch_notification_sender, "notification_sender");
        wireSwitch(R.id.switch_location_fetcher,    "location_fetcher");
        wireSwitch(R.id.switch_camera_capture,      "camera_capture");
    }

    /**
     * Wires a single SwitchCompat toggle to a tool entry in {@link PermissionManifest}.
     *
     * @param switchId   resource ID of the SwitchCompat view
     * @param toolName   tool identifier to read/write in PermissionManifest
     */
    private void wireSwitch(int switchId, String toolName) {
        SwitchCompat toggle = findViewById(switchId);
        toggle.setChecked(permissionManifest.isEnabled(toolName));
        toggle.setOnCheckedChangeListener((btn, isChecked) ->
                permissionManifest.setEnabled(toolName, isChecked));
    }

    /**
     * Sets up the Audit Log section: populates the log on start and wires the Refresh button.
     *
     * <p>Entries are shown newest-first so the device owner sees the most recent activity
     * without scrolling. The Refresh button re-reads the log file, which may have grown
     * since the activity was opened (commands can arrive via FCM while the screen is on).</p>
     */
    private void setupAuditLogSection() {
        TextView logText = findViewById(R.id.audit_log_text);
        Button refreshBtn = findViewById(R.id.btn_refresh_log);

        Runnable refresh = () -> {
            String entries = auditLogger.readNewestFirst();
            logText.setText(entries.isEmpty() ? "No entries yet." : entries);
        };

        refresh.run();
        refreshBtn.setOnClickListener(v -> refresh.run());
    }
}
