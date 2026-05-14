package ai.tabforge.telegramclaw;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private PairingManager pairingManager;
    private CountDownTimer pairingTimer;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener permChangeListener;

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionManifest == null) return;
        refreshAllSwitches();
        // Live listener — fires immediately when KillSwitchReceiver changes permissions
        // in the same process (e.g., SMS arrives while MainActivity is visible).
        permChangeListener = (prefs, key) -> refreshPermissionSummary();
        getSharedPreferences("claw_permissions", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(permChangeListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (permChangeListener != null) {
            getSharedPreferences("claw_permissions", MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(permChangeListener);
        }
    }

    private void refreshAllSwitches() {
        refreshPermissionSummary();
    }

    private void refreshPermissionSummary() {
        String[] tools = {"audio_manager", "get_device_context", "media_control",
                          "notification_sender", "location_fetcher", "camera_capture"};
        int count = 0;
        for (String tool : tools) {
            if (permissionManifest.isEnabled(tool)) count++;
        }
        TextView summary = findViewById(R.id.permission_summary);
        if (summary != null) summary.setText(count + " of 6 tools enabled");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startForegroundService(new Intent(this, ClawForegroundService.class));

        permissionManifest = new PermissionManifest(this);
        auditLogger = new AuditLogger(this);

        KeyManager.ensureKeyPair();
        requestNotificationPermission();
        requestLocationPermission();
        requestCameraPermission();
        requestSmsPermission();
        setupTokenSection();
        setupPublicKeySection();
        setupBotTokenSection();
        setupPairingSection();
        setupKillSwitchSection();
        setupPermissionSummarySection();
        setupAuditLogSection();
    }

    /**
     * Requests POST_NOTIFICATIONS runtime permission on Android 13+.
     *
     * <p>Required for notification_sender tool to post notifications. On Android 12 and below,
     * this permission is automatically granted at install time.</p>
     */
    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA}, 3);
        }
    }

    private void requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECEIVE_SMS}, 4);
        }
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 2);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
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
     * Wires the Bot Token EditText and Save button to {@link TelegramReplyClient}.
     *
     * <p>Pre-fills the field with any previously saved token so the device owner can
     * see and update it. Tapping Save persists the token immediately; subsequent tool
     * executions will use it to reply back to Person A via Telegram.</p>
     */
    private void setupPublicKeySection() {
        TextView keyText = findViewById(R.id.public_key_text);
        String key = KeyManager.getPublicKeyBase64();
        keyText.setText(key.isEmpty() ? "Key not yet generated." : key);
    }

    private void setupBotTokenSection() {
        EditText editToken    = findViewById(R.id.edit_bot_token);
        EditText editRelayUrl = findViewById(R.id.edit_relay_url);
        Button   saveBtn      = findViewById(R.id.btn_save_bot_token);

        editToken.setText(TelegramReplyClient.loadBotToken(this));
        editRelayUrl.setText(TelegramReplyClient.loadRelayUrl(this));

        saveBtn.setOnClickListener(v -> {
            TelegramReplyClient.saveBotToken(this, editToken.getText().toString().trim());
            TelegramReplyClient.saveRelayUrl(this, editRelayUrl.getText().toString().trim());
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Wires the Kill Switch section to {@link KillSwitchReceiver}.
     * Pre-fills the phrase field with the stored value (or the default) so the device
     * owner can see what's currently active without having to remember it.
     */
    private void setupKillSwitchSection() {
        EditText editPhrase = findViewById(R.id.edit_kill_phrase);
        Button saveBtn = findViewById(R.id.btn_save_kill_phrase);

        editPhrase.setText(KillSwitchReceiver.loadKillPhrase(this));

        saveBtn.setOnClickListener(v -> {
            String phrase = editPhrase.getText().toString().trim();
            if (phrase.isEmpty()) {
                Toast.makeText(this, "Kill phrase cannot be empty.", Toast.LENGTH_SHORT).show();
                return;
            }
            KillSwitchReceiver.saveKillPhrase(this, phrase);
            Toast.makeText(this, "Kill phrase saved.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Sets up the Pairing section: shows any active PIN with a live countdown, or
     * a prompt when none is active. "Generate PIN" creates a new PIN, stores it locally,
     * and POSTs it to the relay /pair endpoint in a background thread.
     */
    private void setupPairingSection() {
        pairingManager = new PairingManager(this);
        TextView pinText = findViewById(R.id.pairing_pin_text);
        Button generateBtn = findViewById(R.id.btn_generate_pin);

        PairingManager.ActivePin existing = pairingManager.getActivePin();
        if (existing != null) {
            startPinCountdown(pinText, existing.pin, existing.remainingMs());
        }

        generateBtn.setOnClickListener(v -> {
            String relayUrl = TelegramReplyClient.loadRelayUrl(this);
            if (relayUrl.isEmpty()) {
                Toast.makeText(this, "Set Relay URL first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pairingTimer != null) pairingTimer.cancel();
            String pin = pairingManager.generateAndRegisterPin(relayUrl);
            startPinCountdown(pinText, pin, 10 * 60 * 1000L);
        });
    }

    private void startPinCountdown(TextView pinText, String pin, long remainingMs) {
        if (pairingTimer != null) pairingTimer.cancel();
        pairingTimer = new CountDownTimer(remainingMs, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long mins = millisUntilFinished / 60000;
                long secs = (millisUntilFinished % 60000) / 1000;
                pinText.setText(String.format("%s  (%d:%02d remaining)", pin, mins, secs));
            }
            @Override
            public void onFinish() {
                pinText.setText("PIN expired — tap Generate to create a new one.");
            }
        }.start();
    }

    /**
     * Wires the six permission toggles to {@link PermissionManifest}.
     *
     * <p>Each toggle reflects the current stored state on activity start.
     * Changes take effect immediately and persist across reboots — no Save button needed.</p>
     */
    private void setupPermissionSummarySection() {
        refreshPermissionSummary();
        findViewById(R.id.btn_manage_permissions).setOnClickListener(v ->
                startActivity(new Intent(this, PermissionSettingsActivity.class)));
    }

    /**
     * Sets up the Audit Log section: populates the log on start and wires the Refresh button.
     *
     * <p>Entries are shown newest-first so the device owner sees the most recent activity
     * without scrolling. The Refresh button re-reads the log file, which may have grown
     * since the activity was opened (commands can arrive via FCM while the screen is on).</p>
     */
    private void setupAuditLogSection() {
        auditLogger.pruneOlderThan(30);

        TextView logText  = findViewById(R.id.audit_log_text);
        Button refreshBtn = findViewById(R.id.btn_refresh_log);
        Button clearBtn   = findViewById(R.id.btn_clear_log);
        Button toggleBtn  = findViewById(R.id.btn_toggle_log);

        Runnable refresh = () -> {
            String entries = auditLogger.readNewestFirst();
            logText.setText(entries.isEmpty() ? "No entries yet." : entries);
        };

        toggleBtn.setOnClickListener(v -> {
            boolean visible = logText.getVisibility() == android.view.View.VISIBLE;
            logText.setVisibility(visible ? android.view.View.GONE : android.view.View.VISIBLE);
            toggleBtn.setText(visible ? "Show Log ▼" : "Hide Log ▲");
            if (!visible) refresh.run(); // refresh content when expanding
        });

        refreshBtn.setOnClickListener(v -> refresh.run());

        clearBtn.setOnClickListener(v -> {
            auditLogger.clearLog();
            logText.setText("No entries yet.");
            Toast.makeText(this, "Audit log cleared.", Toast.LENGTH_SHORT).show();
        });
    }
}
