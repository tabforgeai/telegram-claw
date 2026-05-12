package ai.tabforge.telegramclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that automatically starts {@link ClawForegroundService} when the device boots.
 *
 * <p>Analogy: like an alarm clock set to ring every morning — when the device finishes booting,
 * Android broadcasts {@code ACTION_BOOT_COMPLETED} to all registered receivers. This receiver
 * catches that broadcast and immediately starts the Claw service, so the device is ready to
 * receive remote commands without the user having to manually open the app after every reboot.</p>
 *
 * <p>Without this receiver, the ForegroundService would only run while the app is open or until
 * the next reboot. With it, Claw is always active from the moment the phone starts — matching
 * the use case of monitoring a family member's phone that may reboot overnight.</p>
 *
 * <p>Requires: {@code RECEIVE_BOOT_COMPLETED} permission in AndroidManifest.xml, and the
 * receiver must be declared with {@code android:enabled="true"} and
 * {@code android:exported="true"} so the OS can deliver the system broadcast.</p>
 *
 * <p>Called by: Android OS, after the device completes its boot sequence.</p>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    /**
     * Receives the {@code ACTION_BOOT_COMPLETED} broadcast and starts {@link ClawForegroundService}.
     *
     * <p>Analogy: like a building manager who arrives first thing every morning to unlock the doors
     * and start the security system — this method fires once per boot, checks that the right
     * broadcast arrived (not some other system event), and starts the Claw service using
     * {@code startForegroundService()}, which is required for foreground services on API 26+.</p>
     *
     * <p>The action check ({@code Intent.ACTION_BOOT_COMPLETED}) is a safety guard: receivers
     * can be triggered by other broadcasts if misconfigured, so we verify the action before acting.</p>
     *
     * <p>Called by: Android OS after boot. Execution time must be short — BroadcastReceivers
     * have a 10-second limit before the OS considers them unresponsive. We start the service
     * and return immediately; all long-running work happens inside the service.</p>
     *
     * @param context  the application context provided by the OS
     * @param intent   the broadcast intent; action will be {@code android.intent.action.BOOT_COMPLETED}
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Log.i(TAG, "Device booted — starting ClawForegroundService.");
        Intent serviceIntent = new Intent(context, ClawForegroundService.class);
        context.startForegroundService(serviceIntent);
    }
}
