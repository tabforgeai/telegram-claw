package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IntentParser} implementation that uses the Groq API (OpenAI-compatible endpoint).
 *
 * <p>Selected when {@code LLM_PROVIDER=groq} (the default). Requires {@code GROQ_API_KEY}.
 * Model is controlled by {@code LLM_MODEL} (default: {@code llama-3.3-70b-versatile}).
 * The Groq free tier provides 14,400 requests/day — sufficient for typical personal use
 * at zero cost.</p>
 *
 * <p>Uses Java's built-in {@code java.net.http.HttpClient} — no extra dependency required.</p>
 */
public class GroqLlmClient implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(GroqLlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are the AI core of Telegram Claw, a P2P remote control system for Android devices. " +
            "An authorized Telegram user has sent you a natural language request — they want to " +
            "perform some action on a remote Android device. " +
            "Your job is to identify exactly which tool to call and with what parameters. " +
            "You MUST always call exactly one of the provided tools. " +
            "You MUST also include a brief 1-2 sentence plain text reply in the same language the user wrote in " +
            "as the message content alongside the tool call. " +
            "This reply confirms what action is being taken, e.g. " +
            "'Turning the ringer up to maximum. Sending the command to the device now.' " +
            "The user may write in Serbian, English, or any other language — respond in the same language.";

    private static final String INTERPRET_PROMPT =
            "You are the AI core of Telegram Claw, a self-hosted family communication tool. " +
            "The system works as follows: a family member (Person A) sends a natural language message " +
            "to a Telegram bot. The device owner (Person B) installed Claw on their own Android device, " +
            "configured it themselves, and explicitly enabled each tool they want to share. " +
            "Sensitive tools (location, camera) always show a confirmation dialog on Person B's screen — " +
            "Person B physically tapped 'Allow' before any data was collected. " +
            "You are now summarizing the result of a tool that Person B already approved. " +
            "Translate the raw device output into a natural, conversational answer for Person A. " +
            "Respond in the same language as the original question. " +
            "Be direct and end with a clear conclusion.";

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final List<Map<String, Object>> toolManifest;

    public GroqLlmClient() {
        this.apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GROQ_API_KEY is not set. Get a free key at https://console.groq.com, " +
                    "or set LLM_PROVIDER=anthropic to use the Anthropic API instead.");
        }
        this.model = System.getenv().getOrDefault("LLM_MODEL", "llama-3.3-70b-versatile");
        this.http = HttpClient.newHttpClient();
        this.toolManifest = buildToolManifest();
        log.info("GroqLlmClient initialized | model: {} | tools: {}", model, toolManifest.size());
    }

    @Override
    public ClawCommand parseIntent(String messageText, long senderChatId) throws IntentParseException {
        log.debug("Parsing intent (Groq): \"{}\"", messageText);
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", messageText)
            ));
            requestBody.put("tools", toolManifest);
            requestBody.put("tool_choice", "required");
            requestBody.put("max_tokens", 1024);

            HttpResponse<String> response = post(requestBody);

            JsonNode root = mapper.readTree(response.body());
            JsonNode message = root.path("choices").get(0).path("message");

            String toolName = null;
            JsonNode parameters = null;
            String naturalLanguageReply = null;

            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode tc = toolCalls.get(0);
                toolName = tc.path("function").path("name").asText(null);
                String argsJson = tc.path("function").path("arguments").asText("{}");
                parameters = mapper.readTree(argsJson);
                log.info("[INTENT] Tool: {} | Params: {}", toolName, parameters);
            }

            JsonNode contentNode = message.path("content");
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                String text = contentNode.asText("").trim();
                if (!text.isBlank()) {
                    naturalLanguageReply = text;
                    log.debug("[INTENT] Text: {}", naturalLanguageReply);
                }
            }

            if (toolName == null) {
                throw new IntentParseException(
                        "Groq returned no tool call for: \"" + messageText + "\" | body: " + response.body());
            }

            return new ClawCommand(toolName, parameters, naturalLanguageReply, senderChatId);

        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("Groq API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String interpretResult(String toolName, String rawResult, String originalQuestion) {
        try {
            String questionLine = (originalQuestion != null && !originalQuestion.isBlank())
                    ? "The person asked: \"" + originalQuestion + "\"\n\n"
                    : "";

            String userMessage = questionLine +
                    "The Android device executed \"" + toolName + "\" and returned:\n" + rawResult + "\n\n" +
                    "Write a 2-3 sentence answer in the same language as the question. " +
                    "Be direct and conversational. " +
                    "End with a clear one-sentence conclusion " +
                    "(e.g. 'Based on this, they are probably sleeping.' or 'They are most likely awake.').";

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", INTERPRET_PROMPT),
                    Map.of("role", "user", "content", userMessage)
            ));
            requestBody.put("max_tokens", 256);

            HttpResponse<String> response = post(requestBody);

            JsonNode root = mapper.readTree(response.body());
            String text = root.path("choices").get(0).path("message").path("content").asText("");
            if (!text.isBlank()) {
                log.info("[INTERPRET] {} → {}", toolName, text);
                return text;
            }
            return "Device result: " + rawResult;

        } catch (Exception e) {
            log.warn("[INTERPRET_FAIL] Could not interpret result for {}: {}", toolName, e.getMessage());
            return "Device result: " + rawResult;
        }
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IntentParseException(
                    "Groq API error " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    // ── Tool manifest ────────────────────────────────────────────────────────────

    protected List<Map<String, Object>> buildToolManifest() {
        return List.of(
                func("get_device_context",
                        "Reads the current state of the device: battery level, charging status, " +
                        "sound profile (SILENT/VIBRATE/NORMAL), ringer volume, screen on/off, " +
                        "ambient light level, and minutes since last motion was detected. " +
                        "Use this first when the sender asks 'what is he doing?' or 'is he awake?' " +
                        "or any question about the device's current state.",
                        Map.of(), List.of()),

                func("media_control",
                        "Controls media playback on the device. " +
                        "PLAY/PAUSE/SKIP control the active media session (music, podcast, video). " +
                        "OPEN_URL opens a Spotify or YouTube link and starts playing it. " +
                        "Use when the sender wants to play, stop, or change what's playing.",
                        Map.of(
                                "action", enumProp("The playback action to perform",
                                        List.of("PLAY", "PAUSE", "SKIP", "OPEN_URL")),
                                "url", strProp("Spotify or YouTube URL to open; required when action is OPEN_URL")
                        ),
                        List.of("action")),

                func("audio_manager",
                        "Sets the device volume or forces a loud alert ping even if the device is on silent. " +
                        "RING controls the ringer/notification volume. " +
                        "MEDIA controls music/video volume. " +
                        "ALARM controls alarm volume. " +
                        "force_ping=true overrides silent mode and plays a loud system alert immediately — " +
                        "use this when someone urgently needs to reach the device owner.",
                        Map.of(
                                "stream", enumProp("Which audio stream to control",
                                        List.of("RING", "MEDIA", "ALARM")),
                                "level", intProp("Volume level from 0 (mute) to 100 (maximum)", 0, 100),
                                "force_ping", boolProp("If true, overrides silent/vibrate mode and plays a loud alert immediately")
                        ),
                        List.of("stream", "level", "force_ping")),

                func("location_fetcher",
                        "Fetches the device's current GPS location — latitude, longitude, and accuracy. " +
                        "No parameters needed. " +
                        "Use when the sender asks where the device is, where the person is, or for their location.",
                        Map.of(), List.of()),

                func("notification_sender",
                        "Displays a message on the device screen. " +
                        "TOAST shows a small temporary popup (non-intrusive). " +
                        "NOTIFICATION adds a persistent notification in the status bar. " +
                        "FULLSCREEN takes over the entire screen with the message (most intrusive — use for urgent alerts). " +
                        "Use when the sender wants to leave a message or alert the device owner.",
                        Map.of(
                                "message", strProp("The text to display on the device"),
                                "display_mode", enumProp("How prominently to display the message",
                                        List.of("TOAST", "NOTIFICATION", "FULLSCREEN")),
                                "sender_name", strProp("Display name of the person sending the message, shown in the notification header")
                        ),
                        List.of("message", "display_mode", "sender_name")),

                func("camera_capture",
                        "Takes a photo with the device camera and sends it to the sender via Telegram. " +
                        "Defaults: back camera, no flash. Override with camera=FRONT/BACK and flash=true/false. " +
                        "Use when the sender wants to see what is around the device.",
                        Map.of(
                                "camera", enumProp("FRONT or BACK (default: BACK)", List.of("FRONT", "BACK")),
                                "flash", boolProp("Whether to use flash (default: false)")
                        ),
                        List.of())
        );
    }

    private static Map<String, Object> func(String name, String description,
                                             Map<String, Object> properties, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        return Map.of("type", "function", "function", function);
    }

    private static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private static Map<String, Object> intProp(String description, int min, int max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    private static Map<String, Object> boolProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }
}
