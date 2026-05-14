package ai.tabforge.telegramclaw.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Decides whether a given Telegram user ID is allowed to send commands to this device.
 *
 * <p>Analogy: like a bouncer at a private event — the guest list (authorizedIds) is prepared
 * once at the door (server startup). Every arriving guest (Telegram sender) is checked against
 * it before being let in. If the organizer forgot to prepare a list, the bouncer lets everyone
 * in but shouts a warning that the event is open to the public.</p>
 *
 * <p>Current implementation: whitelist stored in the {@code AUTHORIZED_USER_IDS} environment
 * variable as a comma-separated list of Telegram user IDs (e.g. "8608523419,123456789").
 * If the variable is not set, all senders are accepted and a warning is logged.</p>
 *
 * <p>Future (Phase 3): whitelist will be read from {@code authorized_users.txt} on every
 * request so that new users can be added without restarting the server.</p>
 *
 * <p>Called by: {@link TelegramUpdateReceiver#handleMessage}, before IntentParser is invoked.
 * Authorization is checked first so no Claude API calls are made for unauthorized senders.</p>
 */
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final CopyOnWriteArraySet<Long> authorizedIds;
    private final boolean openAccess;

    /**
     * Parses the comma-separated list of authorized Telegram user IDs.
     *
     * <p>Analogy: like a secretary preparing the guest list from a hand-written note —
     * they split the names by comma, trim any spaces, skip anything illegible, and hand
     * the final list to the bouncer. If the note is blank, the bouncer is told to let
     * everyone in (open access) but warned that this is not ideal for production.</p>
     *
     * <p>Called by: {@link Main#main} once at startup.</p>
     *
     * @param authorizedIdsEnvVar  value of the {@code AUTHORIZED_USER_IDS} environment variable;
     *                             comma-separated Telegram user IDs (e.g. "8608523419,123456789");
     *                             null or blank means open access — all senders are accepted
     */
    public AuthorizationService(String authorizedIdsEnvVar) {
        if (authorizedIdsEnvVar == null || authorizedIdsEnvVar.isBlank()) {
            this.authorizedIds = new CopyOnWriteArraySet<>();
            this.openAccess = true;
            log.warn("AUTHORIZED_USER_IDS not set — all senders accepted (open access).");
            log.warn("Set AUTHORIZED_USER_IDS=<your Telegram user ID> to restrict access.");
        } else {
            CopyOnWriteArraySet<Long> ids = new CopyOnWriteArraySet<>();
            for (String part : authorizedIdsEnvVar.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    ids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid user ID in AUTHORIZED_USER_IDS: '{}'", trimmed);
                }
            }
            this.authorizedIds = ids;
            this.openAccess = false;
            log.info("AuthorizationService initialized | {} authorized user ID(s)", authorizedIds.size());
        }
    }

    /**
     * Returns true if the given Telegram user ID is allowed to send commands.
     *
     * <p>Analogy: like a bouncer checking an ID against the guest list — the check is
     * instantaneous (Set lookup is O(1)), happens before any expensive work (Claude API call),
     * and is completely stateless: no network, no file I/O, no side effects.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} for every incoming text message.</p>
     *
     * @param telegramUserId  the {@code from.id} field from the Telegram Update — uniquely
     *                        identifies the Telegram account that sent the message
     * @return true if the sender is authorized or if the service is in open-access mode;
     *         false if a whitelist is configured and this ID is not on it
     */
    public boolean isAuthorized(long telegramUserId) {
        if (openAccess) return true;
        return authorizedIds.contains(telegramUserId);
    }

    /**
     * Dynamically adds a Telegram user ID to the authorized set at runtime.
     * Called by {@link PairingService} when a valid pairing PIN is accepted.
     * In open-access mode this is a no-op since all senders are already accepted.
     *
     * @param chatId      Telegram user ID to authorize
     * @param senderName  display name (for logging only)
     */
    public void addAuthorizedId(long chatId, String senderName) {
        authorizedIds.add(chatId);
        log.info("[AUTH] {} (id={}) added to authorized set via pairing.", senderName, chatId);
    }

    /**
     * Returns a human-readable status string for the startup banner.
     *
     * @return "OPEN ACCESS (all senders accepted)" or "N user(s) whitelisted"
     */
    public String statusSummary() {
        if (openAccess) return "OPEN ACCESS (all senders accepted)";
        return authorizedIds.size() + " user ID(s) whitelisted";
    }
}
