package ai.tabforge.telegramclaw.tool;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.TimeUnit;

/**
 * Implements the {@code location_fetcher} tool — returns the device's current GPS coordinates.
 *
 * <p>Analogy: like asking your phone "where are you right now?" — it checks its last known
 * position from the GPS chip and reports back with latitude, longitude, and how accurate the
 * reading is. The accuracy depends on whether GPS was recently active; if the phone has been
 * indoors for a long time, the cached fix may be stale or unavailable.</p>
 *
 * <p>This tool always requires human confirmation before execution (Protocol 1 — Human-in-the-Loop).
 * {@link ai.tabforge.telegramclaw.CommandExecutor} presents a confirmation dialog to the device
 * owner and only calls {@link #execute()} after an explicit Allow.</p>
 *
 * <p>Uses {@link FusedLocationProviderClient#getLastLocation()} — the fastest and most
 * battery-friendly approach. It returns the last known location cached by the system without
 * triggering a new GPS fix. If no cached location is available, it returns null and this method
 * reports that GPS may be off.</p>
 *
 * <p>Requires: {@code ACCESS_FINE_LOCATION} (or {@code ACCESS_COARSE_LOCATION}) granted at
 * runtime. If permission is not granted, returns a descriptive error instead of throwing.</p>
 *
 * <p>Called by: {@link ai.tabforge.telegramclaw.CommandExecutor#executeLocationFetcher},
 * on a background thread (Firebase SDK executor).</p>
 */
public class LocationFetcherTool {

    private static final String TAG     = "LocationFetcherTool";
    private static final long   TIMEOUT = 10L;

    private final Context context;

    public LocationFetcherTool(Context context) {
        this.context = context;
    }

    /**
     * Fetches the last known device location and returns it as a human-readable string.
     *
     * @return  location string, e.g. {@code "Lat: 44.818611, Lon: 20.459444 (accuracy: 15m)"},
     *          or a descriptive message if location is unavailable or permission is missing
     */
    public String execute() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return "Location permission not granted. Enable it in device Settings → Apps → Claw → Permissions.";
        }

        try {
            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
            CancellationTokenSource cts = new CancellationTokenSource();
            Location location = Tasks.await(
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken()),
                    TIMEOUT, TimeUnit.SECONDS);

            if (location == null) {
                return "Location unavailable — GPS and network location are both off or blocked. " +
                       "Check that Location is enabled in device Settings.";
            }

            Log.i(TAG, "[location_fetcher] lat=" + location.getLatitude()
                    + " lon=" + location.getLongitude()
                    + " accuracy=" + location.getAccuracy() + "m");

            return String.format("Lat: %.6f, Lon: %.6f (accuracy: %.0fm)",
                    location.getLatitude(), location.getLongitude(), location.getAccuracy());

        } catch (Exception e) {
            Log.e(TAG, "[location_fetcher] Failed: " + e.getMessage());
            return "Location fetch failed: " + e.getMessage();
        }
    }
}
