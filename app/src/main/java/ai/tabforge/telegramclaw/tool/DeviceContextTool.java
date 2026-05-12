package ai.tabforge.telegramclaw.tool;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implements the {@code get_device_context} tool — reads passive device sensors and
 * returns a human-readable snapshot of the device's current state.
 *
 * <p>Analogy: like a doctor checking a patient's vitals before making a diagnosis —
 * this tool reads all passive sensors (no permissions required) and assembles them into
 * a description that Claude can use to answer "Is he sleeping?", "Is she okay?", or any
 * question about what the device's current state implies about the person holding it.</p>
 *
 * <p>No parameters required by the relay server — the tool returns a fixed set of readings:
 * <ul>
 *   <li>Battery level and charging status</li>
 *   <li>Screen state (on or off)</li>
 *   <li>Sound profile (SILENT / VIBRATE / NORMAL) and ringer volume</li>
 *   <li>Ambient light level (dark / dim / bright) from the light sensor</li>
 *   <li>Device motion state (stationary / moving) from the accelerometer</li>
 * </ul>
 * </p>
 *
 * <p>Sensor readings (light, accelerometer) use a 1-second timeout: if no reading arrives
 * in time (sensor unavailable or device in deep sleep), the field is marked as "unavailable".</p>
 *
 * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeGetDeviceContext}.</p>
 */
public class DeviceContextTool {

    private static final String TAG = "DeviceContextTool";
    private static final long SENSOR_TIMEOUT_MS = 1000L;

    private final Context context;

    /**
     * Creates a DeviceContextTool bound to the given context.
     *
     * @param context  service or application context for accessing system services and sensors
     */
    public DeviceContextTool(Context context) {
        this.context = context;
    }

    /**
     * Reads all passive sensors and returns a human-readable device state description.
     *
     * <p>Analogy: like a nurse reading a patient's chart in one sweep — battery, screen,
     * sound, light, motion all checked in sequence and summarized in a single report.
     * The result is a plain English string suitable for Claude to interpret and relay to
     * Person A as a natural language answer.</p>
     *
     * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeGetDeviceContext},
     * on the Firebase SDK background thread.</p>
     *
     * @return  human-readable device state, e.g.
     *          {@code "Battery: 72% (discharging). Screen: off. Sound: silent (vol 0/7). Light: dark. Motion: stationary."}
     */
    public String execute() {
        String battery  = readBattery();
        String screen   = readScreen();
        String sound    = readSound();
        String light    = readLightSensor();
        String motion   = readMotion();

        String result = "Battery: " + battery + ". Screen: " + screen + ". Sound: " + sound
                + ". Light: " + light + ". Motion: " + motion + ".";

        Log.i(TAG, "[get_device_context] " + result);
        return result;
    }

    /**
     * Reads battery level percentage and charging status from the sticky battery broadcast.
     *
     * @return  e.g. {@code "85% (charging)"} or {@code "42% (discharging)"}
     */
    private String readBattery() {
        Intent batteryIntent = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) return "unavailable";

        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;

        return (pct >= 0 ? pct + "%" : "unknown") + (charging ? " (charging)" : " (discharging)");
    }

    /**
     * Reads whether the screen is currently on (interactive) or off.
     *
     * @return  {@code "on"} or {@code "off"}
     */
    private String readScreen() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm.isInteractive() ? "on" : "off";
    }

    /**
     * Reads the current ringer mode and ring volume.
     *
     * @return  e.g. {@code "silent (vol 0/7)"} or {@code "normal (vol 5/7)"}
     */
    private String readSound() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int mode = am.getRingerMode();
        int vol  = am.getStreamVolume(AudioManager.STREAM_RING);
        int max  = am.getStreamMaxVolume(AudioManager.STREAM_RING);

        String modeName;
        switch (mode) {
            case AudioManager.RINGER_MODE_SILENT:   modeName = "silent";  break;
            case AudioManager.RINGER_MODE_VIBRATE:  modeName = "vibrate"; break;
            default:                                modeName = "normal";  break;
        }
        return modeName + " (vol " + vol + "/" + max + ")";
    }

    /**
     * Reads the ambient light sensor and classifies the result as dark, dim, or bright.
     *
     * <p>Uses a {@link CountDownLatch} with a {@value SENSOR_TIMEOUT_MS}ms timeout to
     * perform a one-shot sensor read without blocking indefinitely.</p>
     *
     * <p>Classification thresholds:
     * <ul>
     *   <li>&lt; 10 lux → dark (night, pocket, face-down)</li>
     *   <li>10–200 lux → dim (indoor, low light)</li>
     *   <li>&gt; 200 lux → bright (outdoor, well-lit room)</li>
     * </ul>
     * </p>
     *
     * @return  e.g. {@code "dark (3 lux)"} or {@code "unavailable"}
     */
    private String readLightSensor() {
        float lux = readSensorValue(Sensor.TYPE_LIGHT);
        if (lux < 0) return "unavailable";

        String label;
        if      (lux < 10)  label = "dark";
        else if (lux < 200) label = "dim";
        else                label = "bright";

        return label + " (" + Math.round(lux) + " lux)";
    }

    /**
     * Reads the accelerometer and classifies motion as stationary or moving.
     *
     * <p>A stationary device has an acceleration vector whose magnitude is close to
     * 9.81 m/s² (gravity only). Values deviating more than 1.5 m/s² indicate motion.</p>
     *
     * @return  {@code "stationary"}, {@code "moving"}, or {@code "unavailable"}
     */
    private String readMotion() {
        float accelX = readSensorValue(Sensor.TYPE_ACCELEROMETER);
        if (accelX < -900) return "unavailable";

        float magnitude = Math.abs(accelX);
        return magnitude > 1.5f ? "moving" : "stationary";
    }

    /**
     * Performs a one-shot sensor read with a timeout, returning the first {@code values[0]}.
     *
     * <p>Registers a listener, waits up to {@value SENSOR_TIMEOUT_MS}ms for the first
     * {@code onSensorChanged} callback, then unregisters. Safe to call on any thread.</p>
     *
     * @param sensorType  Android sensor type constant, e.g. {@link Sensor#TYPE_LIGHT}
     * @return  first sensor reading ({@code values[0]}), or {@code -999f} if unavailable or timed out
     */
    private float readSensorValue(int sensorType) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sm.getDefaultSensor(sensorType);
        if (sensor == null) return -999f;

        float[] result = {-999f};
        CountDownLatch latch = new CountDownLatch(1);

        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                result[0] = event.values[0];
                latch.countDown();
            }
            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        try {
            latch.await(SENSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sm.unregisterListener(listener);
        }

        return result[0];
    }
}
