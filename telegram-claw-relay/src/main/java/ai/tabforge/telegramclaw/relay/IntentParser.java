package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;

/**
 * Converts natural language Telegram messages into structured tool calls, and interprets
 * raw device results back into natural language for Person A.
 *
 * <p>Implementations: {@link AnthropicLlmClient} (Claude API) and {@link GroqLlmClient}
 * (Groq API — free tier, default for v1.0.0). Selected at startup by {@link LlmClientFactory}
 * based on the {@code LLM_PROVIDER} environment variable.</p>
 */
public interface IntentParser {

    /**
     * Parses a raw natural language Telegram message into a structured tool call.
     *
     * @param messageText   the raw Telegram message from the authorized sender
     * @param senderChatId  the Telegram chat ID of the sender
     * @return              a {@link ClawCommand} with tool name, parameters, and optional
     *                      natural-language acknowledgment to send back immediately
     * @throws IntentParseException  if the LLM returns no tool call or the API call fails
     */
    ClawCommand parseIntent(String messageText, long senderChatId) throws IntentParseException;

    /**
     * Interprets a raw device result in natural language.
     *
     * @param toolName          the tool that produced the result
     * @param rawResult         the raw result string from the Android device
     * @param originalQuestion  the original Telegram message, used for language detection;
     *                          may be null
     * @return  a 2-3 sentence natural language answer in the same language as the question,
     *          or a plain fallback if the API call fails
     */
    String interpretResult(String toolName, String rawResult, String originalQuestion);
}
