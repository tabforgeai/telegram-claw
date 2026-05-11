package ai.tabforge.telegramclaw.relay;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Entry point for the Telegram Claw Relay Server.
 *
 * <p>Analogy: like a power switch on a server rack — flipping it on (running main)
 * starts all the components in sequence: webhook registration with Telegram,
 * the HTTP listener, the route registrations, and the thread executor.
 * Once started, the server runs until the process is killed.</p>
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>TELEGRAM_BOT_TOKEN            — the token from BotFather (format: 1234567890:ABCdef...)</li>
 *   <li>WEBHOOK_URL                   — the public HTTPS URL where Telegram will POST updates
 *                                       (e.g., https://xxxx.ngrok.io/webhook)</li>
 *   <li>ANTHROPIC_API_KEY             — Anthropic API key for Claude intent parsing</li>
 *   <li>GOOGLE_APPLICATION_CREDENTIALS — absolute path to the Firebase service account JSON file</li>
 *   <li>FCM_DEVICE_TOKEN              — FCM registration token of the target Android device</li>
 *   <li>PORT                          — HTTP port to listen on (default: 8080)</li>
 *   <li>CLAUDE_MODEL                  — model for intent parsing (default: claude-haiku-4-5-20251001)</li>
 * </ul>
 * </p>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Starts the HTTP server and registers the Telegram webhook endpoint.
     *
     * <p>Analogy: like opening a restaurant — you first register the address with the
     * city (setWebhook with Telegram) so customers know where to find you, then unlock
     * the door (/webhook endpoint), and assign waiters (virtual thread executor) before
     * the first customer arrives. Registration happens before the HTTP server starts
     * so no Telegram update is missed during startup.</p>
     *
     * <p>Java 21 virtual threads ({@code newVirtualThreadPerTaskExecutor}) mean each
     * incoming request gets its own lightweight thread with no thread pool sizing required.</p>
     *
     * @param args  command-line arguments (not used; configuration via environment variables)
     * @throws IOException  if the server cannot bind to the port (e.g., port already in use)
     */
    public static void main(String[] args) throws IOException {
        printStartupBanner();

        String botToken  = System.getenv("TELEGRAM_BOT_TOKEN");
        String webhookUrl = System.getenv("WEBHOOK_URL");

        if (botToken == null || botToken.isBlank()) {
            log.error("TELEGRAM_BOT_TOKEN is not set — skipping webhook registration.");
            log.error("Set it to your BotFather token and restart to enable registration.");
        } else if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("WEBHOOK_URL is not set — skipping webhook registration.");
            log.warn("Run 'ngrok http 8080', copy the https:// URL, set WEBHOOK_URL, and restart.");
        } else {
            WebhookRegistrar registrar = new WebhookRegistrar(botToken, webhookUrl);
            boolean registered = registrar.register();
            if (registered) {
                registrar.logWebhookInfo();
            }
        }

        String authorizedIds = System.getenv("AUTHORIZED_USER_IDS");
        AuthorizationService authorizationService = new AuthorizationService(authorizedIds);

        AnthropicClient anthropicClient = AnthropicOkHttpClient.fromEnv();
        IntentParser intentParser = new IntentParser(anthropicClient);

        CommandDispatcher commandDispatcher = null;
        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        String deviceToken = System.getenv("FCM_DEVICE_TOKEN");
        if (credentialsPath != null && !credentialsPath.isBlank()
                && deviceToken != null && !deviceToken.isBlank()) {
            try {
                commandDispatcher = new CommandDispatcher(credentialsPath, deviceToken);
            } catch (IOException e) {
                log.error("Failed to initialize Firebase Admin SDK: {}", e.getMessage());
                log.error("Check that GOOGLE_APPLICATION_CREDENTIALS points to a valid service account JSON.");
            }
        } else {
            log.warn("GOOGLE_APPLICATION_CREDENTIALS or FCM_DEVICE_TOKEN not set — FCM dispatch disabled.");
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/webhook", new TelegramUpdateReceiver(intentParser, commandDispatcher, authorizationService));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        log.info("Server is up on port {}. Waiting for Telegram updates...", port);
    }

    /**
     * Logs a startup banner showing the server version and current env var status.
     *
     * <p>Analogy: like a car's dashboard at ignition — all indicator lights are checked
     * at once so any missing configuration is visible before the first request arrives,
     * without having to read through long startup logs to find what was set and what was not.</p>
     *
     * <p>Called by: main(), once before any network activity.</p>
     */
    private static void printStartupBanner() {
        String token         = System.getenv("TELEGRAM_BOT_TOKEN");
        String webhook       = System.getenv("WEBHOOK_URL");
        String anthropicKey  = System.getenv("ANTHROPIC_API_KEY");
        String credentials   = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        String fcmToken      = System.getenv("FCM_DEVICE_TOKEN");
        String authIds       = System.getenv("AUTHORIZED_USER_IDS");
        String model         = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-haiku-4-5-20251001");
        String port          = System.getenv().getOrDefault("PORT", "8080");

        String tokenStatus       = (token       != null && !token.isBlank())       ? "SET" : "NOT SET (required)";
        String webhookStatus     = (webhook     != null && !webhook.isBlank())     ? webhook : "NOT SET — run ngrok first";
        String anthropicStatus   = (anthropicKey != null && !anthropicKey.isBlank()) ? "SET" : "NOT SET (required)";
        String credentialsStatus = (credentials != null && !credentials.isBlank()) ? credentials : "NOT SET — FCM disabled";
        String fcmTokenStatus    = (fcmToken    != null && !fcmToken.isBlank())    ? "SET" : "NOT SET — FCM disabled";
        String authStatus        = (authIds     != null && !authIds.isBlank())     ? authIds : "NOT SET — open access";

        log.info("---------------------------------------------------");
        log.info("  Telegram Claw Relay Server  |  Phase 1 Day 7");
        log.info("---------------------------------------------------");
        log.info("  PORT:                           {}", port);
        log.info("  TELEGRAM_BOT_TOKEN:             {}", tokenStatus);
        log.info("  WEBHOOK_URL:                    {}", webhookStatus);
        log.info("  ANTHROPIC_API_KEY:              {}", anthropicStatus);
        log.info("  CLAUDE_MODEL:                   {}", model);
        log.info("  GOOGLE_APPLICATION_CREDENTIALS: {}", credentialsStatus);
        log.info("  FCM_DEVICE_TOKEN:               {}", fcmTokenStatus);
        log.info("  AUTHORIZED_USER_IDS:            {}", authStatus);
        log.info("---------------------------------------------------");
    }
}
