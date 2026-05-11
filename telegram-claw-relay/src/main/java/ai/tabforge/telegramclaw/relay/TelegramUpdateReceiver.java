package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;
import ai.tabforge.telegramclaw.relay.model.TelegramUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Receives every HTTP POST that the Telegram Bot API sends to our /webhook endpoint,
 * then routes text messages to IntentParser for Claude-powered intent recognition.
 *
 * <p>Analogy: like a receptionist at a company's front desk — every visitor (Telegram Update)
 * arrives here first. The receptionist checks who they are and what they want, logs the visit,
 * and then passes the visitor to the right department (IntentParser). Anyone arriving via the
 * wrong door (non-POST) is politely turned away.</p>
 *
 * <p>Called by: the JDK HttpServer, registered in Main.java as the handler for /webhook.
 * Telegram sends one Update per POST call for each incoming message or event.</p>
 *
 * <p>Unlike Viber, Telegram does not require a specific response body — an empty 200 OK
 * is sufficient. However, returning 200 promptly is critical: if Telegram does not receive
 * 200 within a few seconds, it retries and eventually stops delivering updates to the webhook.</p>
 */
public class TelegramUpdateReceiver implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateReceiver.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final IntentParser intentParser;
    private final CommandDispatcher commandDispatcher;
    private final AuthorizationService authorizationService;

    /**
     * Constructs a TelegramUpdateReceiver wired to all processing pipeline components.
     *
     * <p>Analogy: like assembling a processing line in a factory — the conveyor belt
     * (this class) moves each item (Telegram message) through three stations in order:
     * the security gate (AuthorizationService) rejects unauthorized senders,
     * the translator (IntentParser) determines what to do,
     * and the dispatcher (CommandDispatcher) sends the work order to the factory floor
     * (Android device). The dispatcher may be null if Firebase is not configured.</p>
     *
     * <p>Called by: {@link Main#main} once at startup.</p>
     *
     * @param intentParser         calls Claude to produce a ClawCommand from natural language
     * @param commandDispatcher    sends commands via FCM; may be null if Firebase is not configured
     * @param authorizationService checks whether a Telegram user ID is on the whitelist
     */
    public TelegramUpdateReceiver(IntentParser intentParser,
                                  CommandDispatcher commandDispatcher,
                                  AuthorizationService authorizationService) {
        this.intentParser = intentParser;
        this.commandDispatcher = commandDispatcher;
        this.authorizationService = authorizationService;
    }

    /**
     * Entry point for every HTTP request that arrives at /webhook.
     *
     * <p>Analogy: like a mailroom scanner — every package (HTTP request) passes through.
     * Packages addressed wrong (not POST) are returned. Valid packages are opened,
     * contents logged, and Telegram always gets a receipt (200 OK) immediately.
     * If Telegram does not receive 200, it retries with exponential backoff and
     * eventually drops the webhook, so we must respond before doing any heavy work.</p>
     *
     * <p>Called by: JDK HttpServer on each incoming connection, on a virtual thread
     * (Java 21 — one lightweight thread per request, no pool sizing needed).</p>
     *
     * @param exchange  the HTTP request/response pair provided by JDK HttpServer
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
        log.debug("Raw Telegram update payload: {}", body);

        // Always respond 200 immediately — Telegram retries if we are slow.
        sendResponse(exchange, 200, "");

        // Process the update after responding, so Telegram does not wait on us.
        try {
            TelegramUpdate update = mapper.readValue(body, TelegramUpdate.class);
            dispatchUpdate(update);
        } catch (Exception e) {
            log.warn("Could not parse Telegram update: {} | body: {}", e.getMessage(), body);
        }
    }

    /**
     * Routes a parsed Telegram Update to the correct handler based on which field is set.
     *
     * <p>Analogy: like a telephone switchboard operator — each Update arrives with a
     * different "slot" filled in (message, edited_message, callback_query, etc.), and the
     * operator connects the call to the right handler by checking which slot is present.
     * We only handle text messages for now; other update types are logged and ignored.</p>
     *
     * <p>Called by: handle(), after the 200 OK response has been sent to Telegram.</p>
     *
     * @param update  the deserialized Telegram Update; exactly one field will be non-null
     */
    private void dispatchUpdate(TelegramUpdate update) {
        if (update.getMessage() != null) {
            handleMessage(update.getMessage());
        } else if (update.getEditedMessage() != null) {
            log.debug("[EDITED_MESSAGE] update_id={} — ignoring edited messages.", update.getUpdateId());
        } else {
            log.debug("[UNKNOWN_UPDATE] update_id={} — unrecognized update type.", update.getUpdateId());
        }
    }

    /**
     * Processes an incoming text message from an authorized or unknown Telegram user.
     *
     * <p>Analogy: like a triage nurse in an emergency room — the patient (message) arrives,
     * the nurse checks: is there a message? Is it text? Who sent it? Logs the details,
     * then either handles it (text → forward to IntentParser in Day 3) or dismisses it
     * (non-text message — we do not handle photos, stickers, etc. as commands).</p>
     *
     * <p>Called by: dispatchUpdate() when update.getMessage() is non-null.</p>
     *
     * @param message  the Message from the Update; guaranteed non-null by the caller
     */
    private void handleMessage(TelegramUpdate.Message message) {
        TelegramUpdate.User sender = message.getFrom();
        String text = message.getText();

        if (sender == null) {
            log.warn("[MESSAGE] Received message with no sender — ignoring.");
            return;
        }

        if (!authorizationService.isAuthorized(sender.getId())) {
            log.warn("[DENIED] Unauthorized sender: {} (id={}) — message rejected.",
                    sender.displayName(), sender.getId());
            return;
        }

        if (text != null && !text.isBlank()) {
            log.info("[MESSAGE] From: {} (id={}) | Text: \"{}\"",
                    sender.displayName(), sender.getId(), text);
            try {
                ClawCommand command = intentParser.parseIntent(text, message.getChat().getId());
                log.info("[COMMAND] Tool: {} | Params: {} | ChatId: {}",
                        command.getToolName(), command.getParameters(), command.getSenderChatId());

                if (commandDispatcher != null) {
                    try {
                        commandDispatcher.send(command);
                    } catch (DispatchException e) {
                        log.warn("[DISPATCH_FAIL] Could not deliver '{}' via FCM: {}",
                                command.getToolName(), e.getMessage());
                    }
                } else {
                    log.debug("[DISPATCH_SKIP] FCM not configured — command parsed but not sent.");
                }
                // TODO Phase 1 Day 8: forward command to ResponseRouter (Telegram reply)
            } catch (IntentParseException e) {
                log.warn("[INTENT_FAIL] Could not parse intent from \"{}\": {}", text, e.getMessage());
            }
        } else {
            log.info("[MESSAGE] Non-text message from {} (id={}) — ignoring.",
                    sender.displayName(), sender.getId());
        }
    }

    /**
     * Writes an HTTP response with the given status code and body text.
     *
     * <p>Analogy: like stamping and sealing an outgoing envelope — you write the
     * status on the outside (HTTP status code) and put the letter inside (body bytes),
     * then hand it to the postal service (JDK HttpServer) for delivery.</p>
     *
     * <p>Called by: handle() for both successful and error responses.</p>
     *
     * @param exchange    the HTTP exchange to respond to
     * @param statusCode  HTTP status code (200 for success, 405 for wrong method)
     * @param body        response body; empty string is fine for Telegram acknowledgments
     * @throws IOException  if writing to the response channel fails
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
