package ai.tabforge.telegramclaw.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Sends a text reply back to Person A via the Telegram Bot API sendMessage method.
 *
 * <p>Analogy: like a telephone operator completing a call — Person A placed a request
 * ("turn up his ringer"), the relay server processed it (Claude parsed the intent, FCM
 * dispatched the command), and now the operator calls Person A back to confirm:
 * "Done — ringer set to maximum." This class is that callback: it closes the
 * request-response loop so Person A knows their message was understood and acted on.</p>
 *
 * <p>The reply text comes from Claude's natural language response, which is generated
 * alongside the tool call in {@link IntentParser}. If Claude did not produce a text
 * response, a generic acknowledgment is sent instead.</p>
 *
 * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after CommandDispatcher
 * has dispatched the FCM push. The reply is sent regardless of whether FCM succeeded —
 * Person A should always get a response confirming that their message was understood.</p>
 */
public class ResponseRouter {

    private static final Logger log = LoggerFactory.getLogger(ResponseRouter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String botToken;
    private final HttpClient httpClient;

    /**
     * Constructs a ResponseRouter using the given Telegram bot token.
     *
     * <p>Analogy: like giving a courier the company's letterhead and stamp — the token
     * is the credential that proves to Telegram's API that this server is authorized to
     * send messages on behalf of the bot. Without it, Telegram rejects all outgoing messages.</p>
     *
     * <p>Called by: {@link Main#main} once at startup, sharing the same bot token used
     * by {@link WebhookRegistrar}.</p>
     *
     * @param botToken  the Telegram bot token from BotFather (format: 1234567890:ABCdef...)
     */
    public ResponseRouter(String botToken) {
        this.botToken = botToken;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Sends Claude's natural language reply (or a fallback message) to the Telegram sender.
     *
     * <p>Analogy: like a receptionist reading back a confirmation — "Your appointment has been
     * scheduled for Tuesday at 3pm." The content comes from whoever processed the request
     * (Claude), not from the receptionist herself. If the content is missing, the receptionist
     * says "Your request has been received" as a neutral acknowledgment.</p>
     *
     * <p>Uses Telegram's {@code sendMessage} Bot API method via HTTP POST.
     * This is a best-effort delivery — if sending fails, the failure is logged but
     * does not propagate as an exception, since the command has already been dispatched.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after the ClawCommand
     * is dispatched, regardless of whether FCM delivery succeeded or failed.</p>
     *
     * @param chatId               the Telegram chat ID of Person A — where to send the reply
     * @param naturalLanguageReply Claude's text response from alongside the tool call;
     *                             may be null if Claude did not produce a text block
     * @param toolName             the tool that was called — used in the fallback message
     *                             if naturalLanguageReply is null or blank
     */
    public void sendReply(long chatId, String naturalLanguageReply, String toolName) {
        String text = (naturalLanguageReply != null && !naturalLanguageReply.isBlank())
                ? naturalLanguageReply
                : "Command received: " + toolName + ". Sending to device.";

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            String bodyJson = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[REPLY] Sent to chatId={}: \"{}\"", chatId, text);
            } else {
                log.warn("[REPLY_FAIL] Telegram returned {} for chatId={}: {}", response.statusCode(), chatId, response.body());
            }

        } catch (Exception e) {
            log.warn("[REPLY_FAIL] Could not send reply to chatId={}: {}", chatId, e.getMessage());
        }
    }
}
