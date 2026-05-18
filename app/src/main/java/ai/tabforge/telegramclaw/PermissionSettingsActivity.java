package ai.tabforge.telegramclaw;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

/**
 * Dedicated screen for managing per-tool permissions — Protocol 3 (Permission Manifest).
 *
 * <p>Analogy: like a proper circuit-breaker panel with labeled switches and safety notes —
 * each tool has its own row showing what it does and whether it is active, instead of
 * a list of bare unlabeled switches. The "Disable All" button acts as a master breaker
 * that can cut everything in one tap (same effect as the SMS kill switch, but manual).</p>
 *
 * <p>This Activity is opened from {@link MainActivity} via "Manage Permissions".
 * The SharedPreferences listener keeps the switches in sync if {@link KillSwitchReceiver}
 * fires while this screen is visible.</p>
 */
public class PermissionSettingsActivity extends AppCompatActivity {

    private static final String[][] TOOLS = {
            {"audio_manager",       R.id.switch_audio_manager       + ""},
            {"get_device_context",  R.id.switch_get_device_context  + ""},
            {"media_control",       R.id.switch_media_control       + ""},
            {"notification_sender", R.id.switch_notification_sender + ""},
            {"location_fetcher",    R.id.switch_location_fetcher    + ""},
            {"camera_capture",      R.id.switch_camera_capture      + ""},
    };

    private PermissionManifest permissionManifest;
    private SharedPreferences.OnSharedPreferenceChangeListener permChangeListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_settings);

        permissionManifest = new PermissionManifest(this);

        Button disableAllBtn = findViewById(R.id.btn_disable_all);
        disableAllBtn.setOnClickListener(v -> {
            permissionManifest.disableAll();
            refreshAllSwitches();
            Toast.makeText(this, "All tools disabled.", Toast.LENGTH_SHORT).show();
        });

        wireSwitch(R.id.switch_audio_manager,       "audio_manager");
        wireSwitch(R.id.switch_get_device_context,  "get_device_context");
        wireSwitch(R.id.switch_media_control,       "media_control");
        wireSwitch(R.id.switch_notification_sender, "notification_sender");
        wireSwitch(R.id.switch_location_fetcher,    "location_fetcher");
        wireSwitch(R.id.switch_camera_capture,      "camera_capture");
        wireSwitch(R.id.switch_app_launcher,        "app_launcher");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllSwitches();
        permChangeListener = (prefs, key) -> refreshAllSwitches();
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

    private void wireSwitch(int switchId, String toolName) {
        SwitchCompat toggle = findViewById(switchId);
        toggle.setChecked(permissionManifest.isEnabled(toolName));
        toggle.setOnCheckedChangeListener((btn, isChecked) ->
                permissionManifest.setEnabled(toolName, isChecked));
    }

    private void refreshAllSwitches() {
        refreshSwitch(R.id.switch_audio_manager,       "audio_manager");
        refreshSwitch(R.id.switch_get_device_context,  "get_device_context");
        refreshSwitch(R.id.switch_media_control,       "media_control");
        refreshSwitch(R.id.switch_notification_sender, "notification_sender");
        refreshSwitch(R.id.switch_location_fetcher,    "location_fetcher");
        refreshSwitch(R.id.switch_camera_capture,      "camera_capture");
        refreshSwitch(R.id.switch_app_launcher,        "app_launcher");
    }

    private void refreshSwitch(int switchId, String toolName) {
        SwitchCompat toggle = findViewById(switchId);
        if (toggle != null) toggle.setChecked(permissionManifest.isEnabled(toolName));
    }
}
