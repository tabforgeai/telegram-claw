package ai.tabforge.telegramclaw;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Persistent foreground service that keeps the Telegram Claw app alive 24/7,
 * ready to receive and execute FCM commands from the relay server.
 *
 * <p>Analogy: like a security guard who stays at their post even when the building is empty —
 * this service runs continuously in the background, visible to the system as an active foreground
 * component (mandatory persistent notification), so Android never terminates it to reclaim memory.
 * From Day 12 onward, this guard will also intercept incoming FCM commands and route them
 * to the appropriate executor.</p>
 *
 * <p>Without a ForegroundService, Android kills background apps after a few minutes of
 * inactivity — which would make Telegram Claw unable to respond to remote commands.
 * The persistent notification is Android's requirement for running a foreground service:
 * the user must always know that Claw is active on their device (Protocol 3: Permission Manifest).</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #onCreate} — creates the notification channel (required for API 26+)</li>
 *   <li>{@link #onStartCommand} — calls {@code startForeground()} to lock the process in memory</li>
 *   <li>{@link #onDestroy} — logs shutdown (only happens if the user explicitly stops the service)</li>
 * </ol>
 * </p>
 *
 * <p>Started by: {@link BootReceiver} on device boot, and by {@link MainActivity} during development.
 * Day 12: will receive FCM data messages via a FirebaseMessagingService subclass.</p>
 */
public class ClawForegroundService extends Service {

    private static final String TAG = "ClawForegroundService";
    private static final String CHANNEL_ID = "claw_service_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Called once when the service is first created, before {@link #onStartCommand}.
     *
     * <p>Analogy: like a security company setting up the guard post before the first shift —
     * this is the one-time setup: creating the notification channel. Android requires a channel
     * to exist before any notification can be displayed on API 26+. Creating it here ensures
     * it is registered exactly once, even if the service is restarted multiple times.</p>
     *
     * <p>Called by: Android OS, before the first {@link #onStartCommand}.</p>
     */
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "ClawForegroundService created.");
    }

    /**
     * Called every time the service is started — including on each boot via {@link BootReceiver}.
     *
     * <p>Analogy: like a guard clocking in at the start of each shift — regardless of how many
     * times they are called back to duty, they always check in ({@code startForeground}) before
     * taking their post. {@code START_STICKY} tells Android to restart this service automatically
     * if the OS ever kills it to reclaim memory — the guard always comes back.</p>
     *
     * <p>{@code startForeground()} must be called within 5 seconds of {@code onStartCommand}
     * or Android throws an ANR. It is called immediately, before any other work.</p>
     *
     * <p>On API 29+, the foreground service type is declared explicitly ({@code dataSync})
     * to satisfy API 34+ requirements while remaining compatible with API 26-28 via the
     * pre-29 overload.</p>
     *
     * <p>Called by: Android OS, triggered by {@link BootReceiver} on boot or by
     * {@link MainActivity} during development testing.</p>
     *
     * @param intent   the Intent that started the service; may be null if Android restarts
     *                 the service after killing it (START_STICKY restart)
     * @param flags    delivery flags — START_FLAG_REDELIVERY or START_FLAG_RETRY on restart
     * @param startId  unique ID for this start request — not used in a sticky service
     * @return {@link #START_STICKY} — instructs Android to restart the service if killed
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        Log.i(TAG, "Claw service is running — waiting for commands.");
        return START_STICKY;
    }

    /**
     * Called when the service is being destroyed.
     *
     * <p>Analogy: like a guard signing out after their final shift — this normally only happens
     * if the device owner explicitly stops the service or uninstalls the app. In normal operation,
     * {@code START_STICKY} ensures the service is immediately restarted by the OS.</p>
     *
     * <p>Called by: Android OS on explicit {@code stopService()} or app uninstall.</p>
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ClawForegroundService stopped.");
    }

    /**
     * Not used — Telegram Claw uses a started service, not a bound service.
     *
     * <p>Analogy: like a security guard who works alone without a radio handset — they do not
     * respond to direct calls (binding), they just patrol. Binding is for services that expose
     * a live API to client components; this service instead runs independently and reacts to
     * FCM pushes (added in Day 12).</p>
     *
     * @param intent  the binding Intent (ignored)
     * @return null — binding is not supported
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Creates the notification channel required for the persistent service notification on API 26+.
     *
     * <p>Analogy: like registering a radio frequency with the authorities before broadcasting —
     * Android requires every notification channel to be declared before use. Calling this method
     * multiple times with the same channel ID is safe: the OS treats subsequent calls as no-ops.</p>
     *
     * <p>{@code IMPORTANCE_LOW} is intentional: the persistent "Claw is running" notification
     * must never interrupt the user with sound or vibration — it is a status indicator only.</p>
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Claw Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Telegram Claw active in the background.");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    /**
     * Builds the persistent notification displayed while the service is running.
     *
     * <p>Analogy: like the "On Duty" sign at a security desk — always visible to anyone
     * who glances at the status bar, confirming that Claw is active. {@code setOngoing(true)}
     * prevents the user from dismissing it by swiping, which is required for foreground services.
     * {@code NotificationCompat} ensures the format is compatible across all supported API levels.</p>
     *
     * @return a fully configured {@link Notification} ready to pass to {@code startForeground()}
     */
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Telegram Claw")
                .setContentText("Active — waiting for commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build();
    }
}
