package ai.tabforge.telegramclaw.relay;

/**
 * Thrown when IntentParser cannot produce a valid tool call from a Telegram message.
 *
 * <p>Analogy: like a dispatcher at an emergency call center who receives a call but
 * cannot determine whether to send police, fire, or ambulance — the message came in,
 * but the intent is too unclear or malformed to route. The caller (TelegramUpdateReceiver)
 * catches this and decides how to respond (log the error, send an apology to the sender, etc.).</p>
 *
 * <p>Thrown by: IntentParser.parseIntent() in three cases:
 * (1) Claude returned no tool call at all (pure text response),
 * (2) Claude returned a tool name not in the Tool Manifest,
 * (3) The Claude API call itself failed (network error, auth error, etc.).</p>
 */
public class IntentParseException extends Exception {

    /**
     * Constructs an IntentParseException with a description of what went wrong.
     *
     * <p>Analogy: like writing the reason on a rejected form — future handlers
     * (catch blocks, loggers) need to know why parsing failed, not just that it did.</p>
     *
     * <p>Called by: IntentParser.parseIntent() when Claude returns no tool call.</p>
     *
     * @param message  human-readable description of the parse failure,
     *                 including the original message text where helpful
     */
    public IntentParseException(String message) {
        super(message);
    }

    /**
     * Constructs an IntentParseException wrapping an underlying cause.
     *
     * <p>Analogy: like a supervisor's report that says "order failed — see attached
     * original error from the supplier". The original exception (Claude API error,
     * JSON parse error) is preserved as the cause so callers can inspect both
     * the high-level failure and the root cause.</p>
     *
     * <p>Called by: IntentParser.parseIntent() when the Claude API call throws an exception.</p>
     *
     * @param message  human-readable description of the parse failure
     * @param cause    the underlying exception that caused the failure
     */
    public IntentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}