package ai.tabforge.telegramclaw.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe store for pending query-tool callbacks, with automatic timeout.
 *
 * <p>Analogy: like a restaurant ticket system — when a waiter submits an order (query tool
 * dispatched via FCM), a ticket is placed on the rail with a timer. If the kitchen (Android
 * device) delivers the food (callback) before the timer expires, the ticket is claimed and
 * the dish is served. If the timer runs out (device offline or dead), the waiter returns to
 * the table and explains that the kitchen is unavailable — Person A is not left waiting in
 * silence.</p>
 *
 * <p>Two guarantees enforced by {@link ConcurrentHashMap#remove} atomicity:
 * <ul>
 *   <li>If callback arrives before timeout: {@link #claim} removes the entry; timeout fires but
 *       finds nothing and sends no message.</li>
 *   <li>If timeout fires first: it removes the entry; a late callback finds nothing in
 *       {@link #claim} and silently drops the result (avoiding a duplicate message).</li>
 * </ul>
 * </p>
 *
 * <p>Created by: {@link Main#main} once at startup.
 * Used by: {@link TelegramUpdateReceiver} (store) and {@link CallbackReceiver} (claim).</p>
 */
public class PendingCallbackStore {

    private static final Logger log = LoggerFactory.getLogger(PendingCallbackStore.class);
    static final long TIMEOUT_SECONDS = 30L;

    private final ConcurrentHashMap<Long, String> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ResponseRouter responseRouter;

    /**
     * Creates a PendingCallbackStore that uses the given router to send timeout notices.
     *
     * @param responseRouter  used to send "device offline" messages when a timeout fires
     */
    public PendingCallbackStore(ResponseRouter responseRouter) {
        this.responseRouter = responseRouter;
    }

    /**
     * Stores the original question for {@code chatId} and schedules a timeout.
     *
     * <p>If {@link #claim} is not called within {@value TIMEOUT_SECONDS} seconds,
     * the timeout fires and Person A receives a "device did not respond" message.
     * The original question is stored so {@link CallbackReceiver} can pass it to Claude
     * for language-aware, context-aware interpretation.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver} immediately after dispatching a query tool.</p>
     *
     * @param chatId            Telegram chat ID of Person A
     * @param originalQuestion  the raw message text Person A sent, e.g. "Spava li?"
     */
    public void store(long chatId, String originalQuestion) {
        pending.put(chatId, originalQuestion);
        log.debug("[PENDING] Stored for chatId={} | timeout={}s | question=\"{}\"",
                chatId, TIMEOUT_SECONDS, originalQuestion);

        scheduler.schedule(() -> {
            String removed = pending.remove(chatId);
            if (removed != null) {
                log.warn("[TIMEOUT] No callback from device for chatId={} — sending offline notice.", chatId);
                responseRouter.sendReply(chatId,
                        "The device did not respond. It may be offline or out of battery.",
                        "timeout");
            }
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves and removes the original question for {@code chatId} (atomic).
     *
     * <p>Returns {@code null} if the timeout already fired (entry was already removed)
     * or if no entry exists for this chatId. {@link CallbackReceiver} must check for
     * {@code null} and skip processing to avoid sending a duplicate response.</p>
     *
     * <p>Called by: {@link CallbackReceiver} when Android's result arrives.</p>
     *
     * @param chatId  Telegram chat ID whose pending entry to retrieve
     * @return  the original question string, or {@code null} if timed out or not found
     */
    public String claim(long chatId) {
        String question = pending.remove(chatId);
        if (question != null) {
            log.debug("[PENDING] Claimed for chatId={}", chatId);
        }
        return question;
    }
}
