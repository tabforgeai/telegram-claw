package ai.tabforge.telegramclaw;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry that bridges {@link ConfirmationActivity} (UI thread) with
 * {@link CommandExecutor} (background thread).
 *
 * <p>When CommandExecutor needs user confirmation before executing a sensitive tool
 * (location, camera), it registers a {@link CompletableFuture} here and blocks on it.
 * ConfirmationActivity resolves the future when the user taps Allow/Deny or when the
 * 60-second auto-deny fires. The background thread then unblocks and proceeds.</p>
 *
 * <p>Analogy: like a locked door with a buzzer — CommandExecutor rings the bell and waits
 * at the door (future.get()); ConfirmationActivity is the person inside who decides whether
 * to buzz the door open (resolve true) or ignore it (resolve false after timeout).</p>
 *
 * <p>ConcurrentHashMap ensures thread-safety when multiple requests arrive simultaneously
 * (one per chatId). {@code pending.remove()} is atomic, so a race between Allow and
 * auto-deny timeout is safe — whichever fires first completes the future; the second
 * call finds null and does nothing.</p>
 */
public class ConfirmationGate {

    private static final ConcurrentHashMap<Long, CompletableFuture<Boolean>> pending =
            new ConcurrentHashMap<>();

    /**
     * Registers a pending confirmation for the given chatId and returns a future that
     * will be completed when the user responds (or the timeout fires).
     *
     * <p>Called by: {@link CommandExecutor#requestConfirmation} on a background thread.</p>
     *
     * @param chatId  the Telegram chat ID of the requester — used as the key so each
     *                pending request is isolated by sender
     * @return  a future that completes with {@code true} (approved) or {@code false} (denied)
     */
    public static CompletableFuture<Boolean> register(long chatId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(chatId, future);
        return future;
    }

    /**
     * Resolves the pending confirmation for the given chatId.
     *
     * <p>Called by: {@link ConfirmationActivity} on the UI thread, either when the user
     * taps a button or when the 60-second countdown timer fires.</p>
     *
     * <p>If no pending future exists for this chatId (e.g., a second call after the future
     * was already resolved), this method is a no-op — the remove returns null and nothing
     * happens. This makes the Allow-button and auto-deny-timer race condition safe.</p>
     *
     * @param chatId    the Telegram chat ID identifying which request to resolve
     * @param approved  {@code true} if the user tapped Allow; {@code false} for Deny or timeout
     */
    public static void resolve(long chatId, boolean approved) {
        CompletableFuture<Boolean> future = pending.remove(chatId);
        if (future != null) {
            future.complete(approved);
        }
    }
}
