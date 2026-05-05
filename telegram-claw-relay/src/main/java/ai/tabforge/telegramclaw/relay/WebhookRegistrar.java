package ai.tabforge.telegramclaw.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Registers the relay server's /webhook URL with the Telegram Bot API at startup.
 *
 * <p>Analogy: like an employee registering a new office address with HR —
 * when you move to a new office (new deployment URL), you update HR (Telegram) so
 * that future mail (updates) gets delivered to the right place. This class handles
 * that one-time address update automatically every time the server starts, so the
 * developer never has to do it manually via curl or Postman.</p>
 *
 * <p>Called by: Main.main() at server startup, before the HTTP server begins accepting
 * connections. Requires two environment variables: TELEGRAM_BOT_TOKEN and WEBHOOK_URL.</p>
 *
 * <p>Telegram Bot API reference: https://core.telegram.org/bots/api#setwebhook</p>
 */
public class WebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(WebhookRegistrar.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String TELEGRAM_BASE = "https://api.telegram.org/bot";

    private final HttpClient httpClient;
    private final String botToken;
    private final String webhookUrl;

    /**
     * Constructs a WebhookRegistrar with the given bot credentials.
     *
     * <p>Analogy: like reading a configuration file on startup — the values come from
     * outside the program (environment variables), not hard-coded inside, so the same
     * JAR can be deployed with different bots and different server URLs without recompiling.</p>
     *
     * <p>Called by: Main.main() once at startup, after reading env vars.</p>
     *
     * @param botToken   the Telegram bot token from BotFather, format "1234567890:ABCdef...";
     *                   used in every API URL as /bot{token}/methodName
     * @param webhookUrl the public HTTPS URL Telegram will POST updates to,
     *                   e.g. "https://xxxx.ngrok.io/webhook"; Telegram rejects plain HTTP
     */
    public WebhookRegistrar(String botToken, String webhookUrl) {
        this.botToken = botToken;
        this.webhookUrl = webhookUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Calls setWebhook on the Telegram Bot API and returns whether it succeeded.
     *
     * <p>Analogy: like submitting a change-of-address form to the post office —
     * you send one request, the post office (Telegram) acknowledges it, and from that
     * point all future mail (updates) goes to the new address. If the form is rejected
     * (invalid URL, invalid token), the post office tells you why and no mail is rerouted.
     * We log the reason but do not crash — the HTTP server still starts so you can
     * debug the configuration without restarting the entire process.</p>
     *
     * <p>Called by: Main.main() once at startup. This is a blocking call (synchronous HTTP).
     * It completes before the HttpServer begins accepting connections.</p>
     *
     * @return true if Telegram confirmed the webhook was set ("ok": true in the response);
     *         false if the request failed or Telegram returned an error code
     */
    public boolean register() {
        log.info("Registering webhook with Telegram: {}", webhookUrl);

        try {
            String requestBody = mapper.writeValueAsString(new SetWebhookRequest(webhookUrl));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_BASE + botToken + "/setWebhook"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            TelegramApiResponse apiResponse = mapper.readValue(response.body(), TelegramApiResponse.class);

            if (apiResponse.isOk()) {
                log.info("Webhook registered. Telegram confirmed: \"{}\"", apiResponse.getDescription());
                return true;
            } else {
                log.error("Telegram rejected webhook registration — error {}: {}",
                        apiResponse.getErrorCode(), apiResponse.getDescription());
                return false;
            }

        } catch (Exception e) {
            log.error("Webhook registration failed — cannot reach Telegram API: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Calls getWebhookInfo and logs the current webhook state for diagnostics.
     *
     * <p>Analogy: like calling the post office to confirm your address change went through —
     * after submitting the form you call back and ask "what address do you have on file?"
     * This is useful when messages are not arriving, to confirm whether the registration
     * actually took effect and whether Telegram reported any delivery errors.</p>
     *
     * <p>Called by: Main.main() immediately after a successful register(), for diagnostics.
     * Never throws — failures are logged as warnings, not propagated.</p>
     */
    public void logWebhookInfo() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_BASE + botToken + "/getWebhookInfo"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var result = mapper.readTree(response.body()).path("result");

            log.info("  Active webhook URL:   {}", result.path("url").asText("(none)"));
            log.info("  Pending update count: {}", result.path("pending_update_count").asInt(0));

            String lastError = result.path("last_error_message").asText(null);
            if (lastError != null) {
                log.warn("  Last delivery error:  {}", lastError);
            }

        } catch (Exception e) {
            log.warn("Could not retrieve webhook info: {}", e.getMessage());
        }
    }

    // ── Inner classes for JSON serialization ─────────────────────────────────────

    /**
     * Request body for the setWebhook Telegram API call.
     *
     * <p>Analogy: like a pre-printed form with one field — the post office form
     * only needs your new address, nothing else. Jackson serializes this to
     * {@code {"url":"https://..."}} which is exactly what Telegram expects.</p>
     */
    private static class SetWebhookRequest {
        private final String url;

        SetWebhookRequest(String url) { this.url = url; }

        public String getUrl() { return url; }
    }

    /**
     * Standard Telegram Bot API response envelope, shared by all Telegram API methods.
     *
     * <p>Analogy: like an official letter that always starts with "Approved" or "Rejected" —
     * every Telegram API response has the same outer structure: ok (boolean), and either
     * a result (on success) or error_code + description (on failure).</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TelegramApiResponse {
        private boolean ok;

        @JsonProperty("error_code")
        private int errorCode;

        private String description;

        public boolean isOk() { return ok; }
        public int getErrorCode() { return errorCode; }
        public String getDescription() { return description; }

        public void setOk(boolean ok) { this.ok = ok; }
        public void setErrorCode(int errorCode) { this.errorCode = errorCode; }
        public void setDescription(String description) { this.description = description; }
    }
}