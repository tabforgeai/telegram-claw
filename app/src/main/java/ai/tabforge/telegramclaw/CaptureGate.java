package ai.tabforge.telegramclaw;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry that bridges {@link CaptureActivity} (UI/main thread) with
 * {@link ai.tabforge.telegramclaw.tool.CameraCaptureTool} (background thread).
 *
 * <p>Analogy: like a photo lab's ticket counter — CommandExecutor drops off the film
 * (registers a future), CaptureActivity develops it (takes the photo and saves the file),
 * then calls the ticket number (resolve) so CommandExecutor can pick up the result.</p>
 *
 * <p>Same atomicity guarantee as {@link ConfirmationGate}: if the Activity is killed
 * before resolving, {@code CameraCaptureTool.execute()} times out and resolves with
 * {@code null}, which CommandExecutor treats as a capture failure.</p>
 */
public class CaptureGate {

    private static final ConcurrentHashMap<Long, CompletableFuture<String>> pending =
            new ConcurrentHashMap<>();

    /**
     * Registers a pending capture for the given chatId.
     *
     * @param chatId  Telegram chat ID of the requester — used as the key
     * @return  a future that completes with the absolute file path on success, or {@code null} on failure
     */
    public static CompletableFuture<String> register(long chatId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(chatId, future);
        return future;
    }

    /**
     * Resolves the pending capture for the given chatId.
     *
     * @param chatId    Telegram chat ID whose capture to resolve
     * @param filePath  absolute path to the saved JPEG, or {@code null} if capture failed
     */
    public static void resolve(long chatId, String filePath) {
        CompletableFuture<String> future = pending.remove(chatId);
        if (future != null) {
            future.complete(filePath);
        }
    }
}
