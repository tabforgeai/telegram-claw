package ai.tabforge.telegramclaw;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;

/**
 * Overlay activity that presents a confirmation dialog whenever a sensitive tool
 * (location, camera) is requested remotely — implementing Protocol 1 (Human-in-the-Loop).
 *
 * <p>Analogy: like a hotel front desk calling your room before letting someone in —
 * even if a visitor claims to know you, the hotel (Android) stops them at the desk and
 * calls upstairs. You decide whether to let them up. If you don't answer in 60 seconds,
 * the hotel turns them away automatically.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>CommandExecutor calls {@link ConfirmationGate#register} and starts this Activity</li>
 *   <li>Activity shows AlertDialog with tool name and 60-second countdown</li>
 *   <li>User taps Allow → {@link ConfirmationGate#resolve}(chatId, true) → CommandExecutor unblocks</li>
 *   <li>User taps Deny or timer expires → {@link ConfirmationGate#resolve}(chatId, false)</li>
 * </ol>
 * </p>
 *
 * <p>The activity uses a translucent theme ({@code Theme.Translucent.NoTitleBar}) so only the
 * AlertDialog is visible — the rest of the screen remains unchanged. {@code FLAG_SHOW_WHEN_LOCKED}
 * and {@code FLAG_TURN_SCREEN_ON} ensure the dialog appears even if the phone is asleep.</p>
 */
public class ConfirmationActivity extends Activity {

    public static final String EXTRA_TOOL_NAME = "tool_name";
    public static final String EXTRA_CHAT_ID   = "chat_id";

    private static final long TIMEOUT_MS = 60_000L;

    private CountDownTimer countDownTimer;
    private long chatId;
    private boolean resolved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        chatId = getIntent().getLongExtra(EXTRA_CHAT_ID, 0L);
        String toolName   = getIntent().getStringExtra(EXTRA_TOOL_NAME);
        String displayName = toolName != null ? toolName.replace("_", " ").toUpperCase() : "UNKNOWN TOOL";

        final AlertDialog[] dialogRef = {null};

        countDownTimer = new CountDownTimer(TIMEOUT_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                if (dialogRef[0] != null) {
                    dialogRef[0].setMessage(buildMessage(displayName, secondsLeft));
                }
            }

            @Override
            public void onFinish() {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                resolve(false);
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remote Access Request")
                .setMessage(buildMessage(displayName, 60))
                .setPositiveButton("Allow", (d, which) -> {
                    countDownTimer.cancel();
                    resolve(true);
                })
                .setNegativeButton("Deny", (d, which) -> {
                    countDownTimer.cancel();
                    resolve(false);
                })
                .setCancelable(false)
                .create();

        dialogRef[0] = dialog;
        dialog.show();
        countDownTimer.start();
    }

    private String buildMessage(String displayName, int secondsLeft) {
        return "Someone is requesting access to your " + displayName + ".\n\n" +
               "Tap Allow or Deny.\n\nAuto-deny in " + secondsLeft + "s";
    }

    private void resolve(boolean approved) {
        if (resolved) return;
        resolved = true;
        ConfirmationGate.resolve(chatId, approved);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
        // Safety net: if activity is killed without a button press, deny the request
        resolve(false);
    }
}
