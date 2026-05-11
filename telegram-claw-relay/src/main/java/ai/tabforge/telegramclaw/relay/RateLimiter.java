package ai.tabforge.telegramclaw.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors command frequency per authorized sender and auto-freezes accounts that send
 * an abnormally high number of commands in a short time window.
 *
 * <p>Analogy: like a bank's fraud detection system — if your card is used 10 times in
 * 60 seconds at different locations, the bank flags the account and blocks further
 * transactions until you call to confirm. This class is that fraud detector: it does not
 * question occasional bursts (a person can legitimately send a few commands quickly),
 * but it catches scripted attacks or compromised Telegram accounts sending floods of commands.</p>
 *
 * <p>Uses a sliding window: only commands within the last 60 seconds are counted.
 * Timestamps older than the window are discarded on every check, so the count naturally
 * resets as time passes — no periodic cleanup task needed.</p>
 *
 * <p>Thresholds (Protocol 8):
 * <ul>
 *   <li>{@code > 5} commands in 60 seconds → {@link Result#WARNING} — logged, command still processed</li>
 *   <li>{@code > 10} commands in 60 seconds → {@link Result#FROZEN} — sender blocked, FCM alert sent</li>
 * </ul>
 * </p>
 *
 * <p>Frozen state is in-memory only — a server restart clears all freezes.
 * In Phase 3, the device owner will be able to lift a freeze from within the Android app.</p>
 *
 * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after authorization,
 * before {@link IntentParser}. Blocking happens before any Claude API call is made.</p>
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final int WARNING_THRESHOLD = 5;
    private static final int FREEZE_THRESHOLD  = 10;
    private static final long WINDOW_MS        = 60_000L;

    /** Result of a rate limit check for a single sender. */
    public enum Result {
        /** Under warning threshold — command proceeds normally. */
        ALLOWED,
        /** Between warning and freeze thresholds — command proceeds, anomaly logged. */
        WARNING,
        /** Just crossed freeze threshold — command blocked, sender frozen, FCM alert sent. */
        FROZEN,
        /** Sender was already frozen from a previous check — command blocked silently. */
        ALREADY_FROZEN
    }

    private final ConcurrentHashMap<Long, ArrayDeque<Long>> commandTimestamps = new ConcurrentHashMap<>();
    private final Set<Long> frozenSenders = ConcurrentHashMap.newKeySet();

    /**
     * Records a command attempt for the sender and returns whether it is allowed.
     *
     * <p>Analogy: like a nightclub clicker counter — each time a guest (sender) tries to
     * enter (send a command), the bouncer clicks the counter. If the count stays below
     * the warning threshold, no comment. Between warning and freeze, the bouncer notes
     * it in the incident log but still lets them in. Above freeze threshold, the bouncer
     * stops them, marks their name on the blocked list, and calls security (FCM alert).</p>
     *
     * <p>Thread-safe: each sender's timestamp deque is synchronized individually,
     * so concurrent messages from different senders do not contend with each other.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} on every incoming text message.</p>
     *
     * @param senderId           the Telegram user ID of the sender
     * @param senderDisplayName  display name used in log messages
     * @return the result of the rate limit check — caller decides how to act on it
     */
    public Result check(long senderId, String senderDisplayName) {
        if (frozenSenders.contains(senderId)) {
            return Result.ALREADY_FROZEN;
        }

        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        ArrayDeque<Long> times = commandTimestamps.computeIfAbsent(senderId, k -> new ArrayDeque<>());

        int count;
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < windowStart) {
                times.pollFirst();
            }
            times.addLast(now);
            count = times.size();
        }

        if (count > FREEZE_THRESHOLD) {
            frozenSenders.add(senderId);
            log.warn("[FREEZE] Sender {} (id={}) auto-suspended: {} commands in 60 seconds.",
                    senderDisplayName, senderId, count);
            return Result.FROZEN;
        }

        if (count > WARNING_THRESHOLD) {
            log.warn("[RATE_WARNING] Sender {} (id={}): {} commands in 60 seconds — unusual activity.",
                    senderDisplayName, senderId, count);
            return Result.WARNING;
        }

        return Result.ALLOWED;
    }

    /**
     * Returns true if the sender is currently in the frozen state.
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} to check freeze status
     * independently of recording a new command (e.g., for logging purposes).</p>
     *
     * @param senderId  the Telegram user ID to check
     * @return true if the sender has been auto-frozen and not yet manually unfrozen
     */
    public boolean isFrozen(long senderId) {
        return frozenSenders.contains(senderId);
    }
}
