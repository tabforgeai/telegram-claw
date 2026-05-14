package ai.tabforge.telegramclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * Emergency SMS kill switch — Protocol 7.
 *
 * <p>Analogy: like a physical circuit breaker that cuts all power regardless of what the
 * smart home controller thinks — if someone sends the right SMS, Claw shuts down completely,
 * even if the relay server, Firebase, or the Android app itself is compromised. SMS is an
 * out-of-band channel that bypasses all of Claw's normal infrastructure.</p>
 *
 * <p>When an incoming SMS contains the configured kill phrase (case-insensitive), this receiver:
 * <ol>
 *   <li>Disables all tools in {@link PermissionManifest} — commands can arrive but nothing executes</li>
 *   <li>Stops {@link ClawForegroundService} — FCM listener goes offline</li>
 *   <li>Appends a KILL_SWITCH entry to the {@link AuditLogger}</li>
 * </ol>
 * </p>
 *
 * <p>The kill phrase is stored in SharedPreferences ({@code "claw_kill_switch"}) and defaults
 * to {@value DEFAULT_PHRASE}. The device owner sets it in {@link MainActivity}.</p>
 *
 * <p>Registered in AndroidManifest.xml for {@code android.provider.Telephony.SMS_RECEIVED}
 * with highest priority so it processes the SMS before other apps.</p>
 *
 * <p>Recovery: to re-enable Claw after a kill, the device owner opens MainActivity and
 * re-enables the desired tool toggles, then the foreground service auto-starts from there.</p>
 */
public class KillSwitchReceiver extends BroadcastReceiver {

    private static final String TAG = "KillSwitchReceiver";
    private static final String PREFS = "claw_kill_switch";
    private static final String KEY_PHRASE = "kill_phrase";
    static final String DEFAULT_PHRASE = "CLAW KILL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        String format = intent.getStringExtra("format");
        if (pdus == null) return;

        String killPhrase = loadKillPhrase(context).toUpperCase().trim();

        for (Object pdu : pdus) {
            SmsMessage sms = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    ? SmsMessage.createFromPdu((byte[]) pdu, format)
                    : SmsMessage.createFromPdu((byte[]) pdu);
            if (sms == null) continue;

            String body = sms.getMessageBody();
            if (body != null && body.toUpperCase().contains(killPhrase)) {
                triggerKillSwitch(context, sms.getOriginatingAddress());
                return;
            }
        }
    }

    private void triggerKillSwitch(Context context, String from) {
        Log.w(TAG, "[KILL SWITCH] Triggered via SMS from: " + from);

        new PermissionManifest(context).disableAll();
        context.stopService(new Intent(context, ClawForegroundService.class));

        new AuditLogger(context).log(
                AuditLogger.Status.SUCCESS,
                "kill_switch",
                0L,
                "SMS from " + from + " — all tools disabled; service stopped");

        Log.w(TAG, "[KILL SWITCH] All tools disabled. Foreground service stopped.");
    }

    static String loadKillPhrase(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PHRASE, DEFAULT_PHRASE);
    }

    static void saveKillPhrase(Context context, String phrase) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PHRASE, phrase).apply();
    }
}
