package ai.tabforge.telegramclaw.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Receives the tool execution result from the Android Claw app and closes the response loop.
 *
 * <p>Analogy: like the second half of a phone call — Person A placed a request (first half),
 * the relay dispatched it via FCM, Android executed it, and now Android calls back with the
 * result (second half). This class handles that callback: it receives the raw result,
 * asks Claude to interpret it in natural language, and sends the final answer to Person A.</p>
 *
 * <p>Flow for query tools (e.g. {@code get_device_context}):
 * <ol>
 *   <li>Android POSTs {@code {chatId, tool, result}} to {@code /callback}</li>
 *   <li>{@link IntentParser#interpretResult} asks Claude: "The device returned X. What does it mean?"</li>
 *   <li>{@link ResponseRouter} sends Claude's natural language answer to Person A in Telegram</li>
 * </ol>
 * </p>
 *
 * <p>For action tools (e.g. {@code audio_manager}), the raw result is sent directly to Telegram
 * without a Claude interpretation pass — action confirmations ("Volume set to 80%") are already
 * readable as-is, and the extra API call would add latency for no benefit.</p>
 *
 * <p>Registered by: {@link Main#main} at {@code /callback}.
 * Called by: {@link ai.tabforge.telegramclaw.TelegramReplyClient} on the Android device.</p>
 */
public class CallbackReceiver implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackReceiver.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Tools whose results require a Claude interpretation pass before being shown to Person A.
     * Action tools (audio_manager, notification_sender) are excluded — their results are
     * already human-readable confirmations.
     */
    private static final Set<String> QUERY_TOOLS = Set.of("get_device_context", "location_fetcher");

    private final IntentParser intentParser;
    private final ResponseRouter responseRouter;
    private final PendingCallbackStore pendingCallbackStore;

    /**
     * Constructs a CallbackReceiver wired to all processing components.
     *
     * @param intentParser         used to interpret query-tool results via a second Claude call
     * @param responseRouter       delivers the final answer to Person A via Telegram sendMessage
     * @param pendingCallbackStore claims the original question and cancels the timeout
     */
    public CallbackReceiver(IntentParser intentParser, ResponseRouter responseRouter,
                            PendingCallbackStore pendingCallbackStore) {
        this.intentParser = intentParser;
        this.responseRouter = responseRouter;
        this.pendingCallbackStore = pendingCallbackStore;
    }

    /**
     * Handles a POST from the Android device containing the tool execution result.
     *
     * <p>Expected JSON body:
     * <pre>{@code {"chatId": 123456789, "tool": "get_device_context", "result": "Battery: 85%..."}}</pre>
     * </p>
     *
     * <p>Responds with 200 immediately (before processing), so Android is not blocked
     * waiting for the Claude API call to complete.</p>
     *
     * @param exchange  the HTTP request/response pair
     * @throws IOException  if reading the request body or writing the response fails
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        log.debug("[CALLBACK] Raw payload: {}", body);

        sendResponse(exchange, 200, "");

        try {
            Map<?, ?> payload = mapper.readValue(body, Map.class);
            long chatId = Long.parseLong(String.valueOf(payload.get("chatId")));
            String tool   = String.valueOf(payload.get("tool"));
            String result = String.valueOf(payload.get("result"));

            log.info("[CALLBACK] tool={} | chatId={} | result={}", tool, chatId, result);

            String reply;
            if (QUERY_TOOLS.contains(tool)) {
                String originalQuestion = pendingCallbackStore.claim(chatId);
                if (originalQuestion == null) {
                    log.warn("[CALLBACK] Timeout already fired for chatId={} — dropping late result.", chatId);
                    return;
                }
                reply = intentParser.interpretResult(tool, result, originalQuestion);
            } else {
                reply = result;
            }

            responseRouter.sendReply(chatId, reply, tool);

        } catch (Exception e) {
            log.warn("[CALLBACK_FAIL] Could not process callback: {} | body: {}", e.getMessage(), body);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
