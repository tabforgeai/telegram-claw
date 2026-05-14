package ai.tabforge.telegramclaw;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only audit log that records every command executed or denied on this device,
 * implementing Protocol 5: Transparent Audit Log.
 *
 * <p>Analogy: like a ship's logbook — every event is written in order and nothing is ever
 * erased. The captain (device owner) can open the book at any time and see exactly what
 * happened and when. The AuditLogger is that logbook: every command that arrives from the
 * relay server gets an entry, whether it succeeded, was denied by PermissionManifest,
 * or failed during execution.</p>
 *
 * <p>Log entries are stored as CSV in the app's private files directory
 * ({@code /data/data/ai.tabforge.telegramclaw/files/audit_log.csv}).
 * The file is never truncated or overwritten — only appended to — so the record is
 * tamper-evident within the constraints of a rooted Android device.
 * Full cryptographic immutability (hash-chaining) is planned for Phase 3.</p>
 *
 * <p>CSV format (one entry per line after the header):
 * <pre>
 *   timestamp,status,tool,chatId,description
 *   2026-05-12T10:30:00Z,DENIED,audio_manager,8608523419,Tool not enabled in PermissionManifest
 *   2026-05-12T10:31:05Z,SUCCESS,audio_manager,8608523419,Ringer set to level 100
 * </pre>
 * </p>
 *
 * <p>Used by: {@link CommandExecutor} (writes entries), {@link MainActivity} (reads for display).
 * Export to CSV is Protocol 5's "Export to CSV" feature — the storage format is already CSV,
 * so export is a file copy operation.</p>
 */
public class AuditLogger {

    private static final String TAG = "AuditLogger";
    private static final String LOG_FILE = "audit_log.csv";
    private static final String CSV_HEADER = "timestamp,status,tool,chatId,description\n";

    /**
     * Outcome of a command, written as the {@code status} field in every log entry.
     *
     * <p>Mirrors the relay server's {@code ClawResult.Status} so log entries are
     * interpretable without cross-referencing two codebases.</p>
     */
    public enum Status {
        /** Tool executed and completed without error. */
        SUCCESS,
        /** Tool is not enabled in PermissionManifest, or confirmation dialog was denied. */
        DENIED,
        /** Tool is enabled but threw an exception during execution. */
        ERROR
    }

    private final File logFile;

    /**
     * Opens (or creates) the audit log file in the app's private storage.
     *
     * <p>Analogy: like a clerk pulling the logbook off the shelf — if the book doesn't
     * exist yet (first run), a fresh one is created with a header row. If it already
     * exists, it is opened for appending without touching any existing entries.</p>
     *
     * <p>Called by: {@link CommandExecutor} constructor and {@link MainActivity#onCreate}.</p>
     *
     * @param context  application or service context used to locate the private files directory
     */
    public AuditLogger(Context context) {
        logFile = new File(context.getFilesDir(), LOG_FILE);
        if (!logFile.exists()) {
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write(CSV_HEADER);
            } catch (IOException e) {
                Log.e(TAG, "Failed to initialize audit log: " + e.getMessage());
            }
        }
    }

    /**
     * Appends a single command outcome entry to the audit log.
     *
     * <p>Analogy: like a clerk writing a new line in the logbook — the pen never goes back
     * to erase or overwrite. The entry is written with an ISO-8601 UTC timestamp so it
     * remains interpretable regardless of the device's local time zone.</p>
     *
     * <p>Commas in the {@code description} are replaced with semicolons to preserve the
     * single-line CSV structure without requiring full RFC 4180 quoting.</p>
     *
     * <p>Called by: {@link CommandExecutor#execute} on every command, regardless of outcome.</p>
     *
     * @param status       outcome of the command — SUCCESS, DENIED, or ERROR
     * @param tool         tool name that was requested, e.g. {@code "audio_manager"}
     * @param chatId       Telegram chat ID of Person A who sent the command
     * @param description  human-readable summary of what happened or why it was denied
     */
    public void log(Status status, String tool, long chatId, String description) {
        String timestamp = Instant.now().toString();
        String safeDescription = description.replace(",", ";");
        String line = timestamp + "," + status + "," + tool + "," + chatId + "," + safeDescription + "\n";

        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.write(line);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write audit log entry: " + e.getMessage());
        }
    }

    /**
     * Reads all log entries and returns them in reverse chronological order (newest first).
     *
     * <p>Analogy: like a clerk reading the logbook from the last page backward — most
     * recent events are shown first so the device owner sees what just happened without
     * scrolling past months of history. The header row is excluded from the output.</p>
     *
     * <p>Called by: {@link MainActivity} when the device owner taps "Refresh".</p>
     *
     * @return all log lines (excluding header) joined by newline, newest first;
     *         returns an empty string if the log has no entries yet
     */
    /**
     * Removes log entries older than {@code keepDays} days, rewriting the file in place.
     * Call once at app startup from {@link MainActivity} to keep the log bounded.
     *
     * @param keepDays  entries older than this many days are deleted; 0 clears everything
     */
    public void pruneOlderThan(int keepDays) {
        try {
            List<String> lines = Files.readAllLines(logFile.toPath());
            if (lines.size() <= 1) return;

            Instant cutoff = Instant.now().minusSeconds(keepDays * 86_400L);
            List<String> kept = new ArrayList<>();
            kept.add(lines.get(0)); // header always stays
            int pruned = 0;
            for (int i = 1; i < lines.size(); i++) {
                try {
                    Instant ts = Instant.parse(lines.get(i).split(",")[0]);
                    if (ts.isAfter(cutoff)) {
                        kept.add(lines.get(i));
                    } else {
                        pruned++;
                    }
                } catch (Exception ignored) {
                    kept.add(lines.get(i)); // keep malformed lines rather than silently lose them
                }
            }
            if (pruned > 0) {
                Files.write(logFile.toPath(), kept, StandardCharsets.UTF_8);
                Log.i(TAG, "Pruned " + pruned + " log entries older than " + keepDays + " days.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to prune audit log: " + e.getMessage());
        }
    }

    /**
     * Erases all log entries, keeping only the CSV header row.
     * Called from the "Clear" button in {@link MainActivity}.
     */
    public void clearLog() {
        try (FileWriter fw = new FileWriter(logFile, false)) {
            fw.write(CSV_HEADER);
            Log.i(TAG, "Audit log cleared by device owner.");
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear audit log: " + e.getMessage());
        }
    }

    public String readNewestFirst() {
        try {
            List<String> lines = Files.readAllLines(logFile.toPath());
            if (lines.size() <= 1) {
                return "";
            }
            List<String> entries = lines.subList(1, lines.size());
            Collections.reverse(entries);
            return String.join("\n", entries);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read audit log: " + e.getMessage());
            return "(error reading log)";
        }
    }
}
