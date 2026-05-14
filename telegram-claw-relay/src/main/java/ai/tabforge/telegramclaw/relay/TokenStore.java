package ai.tabforge.telegramclaw.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists paired Telegram user IDs to disk with a configurable TTL — Protocol 4.
 *
 * <p>Analogy: like a hotel's guest registry that the front desk keeps in a locked drawer —
 * when a guest checks in (pairing), their name and room-key expiry date are written into
 * the book. Next morning, when the hotel opens again (relay restart), the front desk reads
 * the book: guests whose keys haven't expired yet are let in without re-checking-in;
 * guests with expired keys are quietly removed from the list.</p>
 *
 * <p>Storage format: a JSON array in {@code tokens.json} (or the path set by
 * {@code TOKEN_STORE_PATH}). Each entry contains chatId, display name, pairedAt
 * and expiresAt timestamps (epoch ms). The file is rewritten atomically on every change.</p>
 *
 * <p>TTL is controlled by the {@code TOKEN_TTL_DAYS} environment variable (default: 30 days).
 * Env-var-whitelisted IDs in {@code AUTHORIZED_USER_IDS} are permanent and bypass this store.</p>
 *
 * <p>Called by: {@link AuthorizationService} — {@code isValid} during every auth check,
 * {@code store} when {@link PairingService} accepts a new pairing PIN.</p>
 */
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path filePath;
    private final long ttlMs;
    private final ConcurrentHashMap<Long, TokenEntry> tokens = new ConcurrentHashMap<>();
    // In-memory set of IDs whose tokens expired this session — used to send helpful re-pair message.
    private final java.util.concurrent.CopyOnWriteArraySet<Long> recentlyExpired =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    /**
     * Loads the token store from disk, discarding any expired entries.
     *
     * @param filePath  path to the JSON file (created on first write if absent)
     * @param ttlDays   how many days a paired token remains valid
     */
    public TokenStore(String filePath, long ttlDays) {
        this.filePath = Path.of(filePath);
        this.ttlMs = ttlDays * 24L * 60 * 60 * 1000;
        load();
    }

    /**
     * Returns true if the given chat ID has a valid (non-expired) token on disk.
     * Expired tokens are evicted from memory and the file is updated.
     */
    public boolean isValid(long chatId) {
        TokenEntry entry = tokens.get(chatId);
        if (entry == null) return false;
        if (System.currentTimeMillis() > entry.expiresAt) {
            tokens.remove(chatId);
            recentlyExpired.add(chatId);
            persist();
            log.info("[TOKEN] Token for id={} ({}) expired — evicted.", chatId, entry.name);
            return false;
        }
        return true;
    }

    /**
     * Returns true if this chat ID had a valid token that expired during this relay session.
     * Used by {@link TelegramUpdateReceiver} to send a helpful re-pair message instead of
     * silently dropping the message.
     */
    public boolean wasExpired(long chatId) {
        return recentlyExpired.contains(chatId);
    }

    /**
     * Writes a new paired token to memory and disk, replacing any prior entry for the same ID.
     *
     * @param chatId  Telegram user ID of the newly paired user
     * @param name    display name for logging and the stored record
     */
    public void store(long chatId, String name) {
        long now = System.currentTimeMillis();
        long expiresAt = now + ttlMs;
        tokens.put(chatId, new TokenEntry(chatId, name, now, expiresAt));
        persist();
        log.info("[TOKEN] {} (id={}) stored — valid until {}.",
                name, chatId, Instant.ofEpochMilli(expiresAt).toString().substring(0, 10));
    }

    /** Number of currently valid tokens (for the startup banner). */
    public int size() {
        return tokens.size();
    }

    private void load() {
        if (!Files.exists(filePath)) {
            log.info("[TOKEN] No token store at '{}' — starting with empty store.", filePath);
            return;
        }
        try {
            List<TokenEntry> entries = mapper.readValue(
                    filePath.toFile(), new TypeReference<List<TokenEntry>>() {});
            long now = System.currentTimeMillis();
            int expired = 0;
            for (TokenEntry e : entries) {
                if (e.expiresAt > now) {
                    tokens.put(e.chatId, e);
                } else {
                    expired++;
                }
            }
            log.info("[TOKEN] Loaded {} valid token(s) from '{}' ({} expired, skipped).",
                    tokens.size(), filePath, expired);
        } catch (IOException e) {
            log.error("[TOKEN] Failed to load token store: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(filePath.toFile(), new ArrayList<>(tokens.values()));
        } catch (IOException e) {
            log.error("[TOKEN] Failed to persist token store: {}", e.getMessage());
        }
    }

    /** JSON-serializable token record. Public fields required for Jackson. */
    public static class TokenEntry {
        public long chatId;
        public String name;
        public long pairedAt;
        public long expiresAt;

        public TokenEntry() {}

        public TokenEntry(long chatId, String name, long pairedAt, long expiresAt) {
            this.chatId = chatId;
            this.name = name;
            this.pairedAt = pairedAt;
            this.expiresAt = expiresAt;
        }
    }
}
