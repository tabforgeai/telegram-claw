package ai.tabforge.telegramclaw.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP handler for the /pair endpoint — receives a freshly generated pairing PIN from
 * the Android Claw app and registers it with {@link PairingService}.
 *
 * <p>Analogy: like a hotel front desk activating a key card — the device owner (Android app)
 * has minted a new key code (PIN) and asks the front desk (relay) to activate it so the
 * intended guest (new Telegram user) can use it to check in.</p>
 *
 * <p>Expected request: {@code POST /pair} with body {@code pin=XXXXXX}
 * (Content-Type: application/x-www-form-urlencoded, 6-char alphanumeric).</p>
 *
 * <p>Called by: {@link PairingManager} on the Android side via HTTP POST.</p>
 */
public class PairHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(PairHandler.class);
    private final PairingService pairingService;

    public PairHandler(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        Map<String, String> params = parseFormBody(bodyStr);
        String pin = params.get("pin");

        if (pin == null || !pin.matches("[A-Za-z0-9]{6}")) {
            log.warn("[PAIR] Invalid or missing PIN in /pair request: '{}'", pin);
            sendResponse(exchange, 400, "Bad Request");
            return;
        }

        pairingService.registerPin(pin);
        sendResponse(exchange, 200, "OK");
    }

    private Map<String, String> parseFormBody(String body) {
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8),
                        (a, b) -> a));
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
