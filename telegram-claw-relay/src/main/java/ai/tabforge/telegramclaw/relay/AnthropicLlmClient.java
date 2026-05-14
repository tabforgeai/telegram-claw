package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * {@link IntentParser} implementation that uses the Anthropic SDK (Claude API).
 *
 * <p>Selected when {@code LLM_PROVIDER=anthropic}. Requires {@code ANTHROPIC_API_KEY}.
 * Model is controlled by {@code LLM_MODEL} (falls back to {@code CLAUDE_MODEL} for
 * backward compatibility; default: {@code claude-haiku-4-5-20251001}).</p>
 */
public class AnthropicLlmClient implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "You are the AI core of Telegram Claw, a P2P remote control system for Android devices. " +
            "An authorized Telegram user has sent you a natural language request — they want to " +
            "perform some action on a remote Android device. " +
            "Your job is to identify exactly which tool to call and with what parameters. " +
            "You MUST always call exactly one of the provided tools. " +
            "You MUST also include a brief 1-2 sentence plain text reply in the same language the user wrote in — " +
            "this reply will be sent back to Person A as a Telegram message confirming what action is being taken. " +
            "The text reply should be conversational and confirm the action, for example: " +
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

    private final AnthropicClient client;
    private final String model;
    private final List<Tool> toolManifest;

    public AnthropicLlmClient() {
        this.client = AnthropicOkHttpClient.fromEnv();
        // Support both LLM_MODEL (new) and CLAUDE_MODEL (backward compat)
        String llmModel = System.getenv("LLM_MODEL");
        this.model = (llmModel != null && !llmModel.isBlank())
                ? llmModel
                : System.getenv().getOrDefault("CLAUDE_MODEL", "claude-haiku-4-5-20251001");
        this.toolManifest = buildToolManifest();
        log.info("AnthropicLlmClient initialized | model: {} | tools: {}", model, toolManifest.size());
    }

    @Override
    public ClawCommand parseIntent(String messageText, long senderChatId) throws IntentParseException {
        log.debug("Parsing intent (Anthropic): \"{}\"", messageText);
        try {
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(messageText);
            for (Tool tool : toolManifest) {
                paramsBuilder.addTool(tool);
            }

            Message response = client.messages().create(paramsBuilder.build());
            log.debug("Anthropic stop_reason: {}", response.stopReason());

            String toolName = null;
            JsonNode parameters = null;
            String naturalLanguageReply = null;

            for (ContentBlock block : response.content()) {
                if (block.toolUse().isPresent()) {
                    var tu = block.toolUse().get();
                    toolName = tu.name();
                    String inputJson = mapper.writeValueAsString(tu._input());
                    parameters = mapper.readTree(inputJson);
                    log.info("[INTENT] Tool: {} | Params: {}", toolName, parameters);
                } else if (block.text().isPresent()) {
                    naturalLanguageReply = block.text().get().text();
                    log.debug("[INTENT] Text block: {}", naturalLanguageReply);
                }
            }

            if (toolName == null) {
                throw new IntentParseException(
                        "Anthropic returned no tool call for: \"" + messageText + "\"" +
                        " | stop_reason: " + response.stopReason());
            }

            return new ClawCommand(toolName, parameters, naturalLanguageReply, senderChatId);

        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("Anthropic API call failed: " + e.getMessage(), e);
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

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(256L)
                    .system(INTERPRET_PROMPT)
                    .addUserMessage(userMessage)
                    .build();

            Message response = client.messages().create(params);

            for (ContentBlock block : response.content()) {
                if (block.text().isPresent()) {
                    String interpretation = block.text().get().text();
                    log.info("[INTERPRET] {} → {}", toolName, interpretation);
                    return interpretation;
                }
            }
            return "Device result: " + rawResult;

        } catch (Exception e) {
            log.warn("[INTERPRET_FAIL] Could not interpret result for {}: {}", toolName, e.getMessage());
            return "Device result: " + rawResult;
        }
    }

    private static List<Tool> buildToolManifest() {
        return List.of(
                buildGetDeviceContextTool(),
                buildMediaControlTool(),
                buildAudioManagerTool(),
                buildLocationFetcherTool(),
                buildNotificationSenderTool(),
                buildCameraCaptureTool()
        );
    }

    private static Tool buildGetDeviceContextTool() {
        return Tool.builder()
                .name("get_device_context")
                .description(
                        "Reads the current state of the device: battery level, charging status, " +
                        "sound profile (SILENT/VIBRATE/NORMAL), ringer volume, screen on/off, " +
                        "ambient light level, and minutes since last motion was detected. " +
                        "Use this first when the sender asks 'what is he doing?' or 'is he awake?' " +
                        "or any question about the device's current state.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    private static Tool buildMediaControlTool() {
        return Tool.builder()
                .name("media_control")
                .description(
                        "Controls media playback on the device. " +
                        "PLAY/PAUSE/SKIP control the active media session (music, podcast, video). " +
                        "OPEN_URL opens a Spotify or YouTube link and starts playing it. " +
                        "Use when the sender wants to play, stop, or change what's playing.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The playback action to perform",
                                        "enum", List.of("PLAY", "PAUSE", "SKIP", "OPEN_URL")
                                )))
                                .putAdditionalProperty("url", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Spotify or YouTube URL to open; required when action is OPEN_URL"
                                )))
                                .build())
                        .required(List.of("action"))
                        .build())
                .build();
    }

    private static Tool buildAudioManagerTool() {
        return Tool.builder()
                .name("audio_manager")
                .description(
                        "Sets the device volume or forces a loud alert ping even if the device is on silent. " +
                        "RING controls the ringer/notification volume. " +
                        "MEDIA controls music/video volume. " +
                        "ALARM controls alarm volume. " +
                        "force_ping=true overrides silent mode and plays a loud system alert immediately — " +
                        "use this when someone urgently needs to reach the device owner.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("stream", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Which audio stream to control",
                                        "enum", List.of("RING", "MEDIA", "ALARM")
                                )))
                                .putAdditionalProperty("level", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Volume level from 0 (mute) to 100 (maximum)",
                                        "minimum", 0,
                                        "maximum", 100
                                )))
                                .putAdditionalProperty("force_ping", JsonValue.from(Map.of(
                                        "type", "boolean",
                                        "description", "If true, overrides silent/vibrate mode and plays a loud alert immediately"
                                )))
                                .build())
                        .required(List.of("stream", "level", "force_ping"))
                        .build())
                .build();
    }

    private static Tool buildLocationFetcherTool() {
        return Tool.builder()
                .name("location_fetcher")
                .description(
                        "Fetches the device's current GPS location — latitude, longitude, and accuracy. " +
                        "No parameters needed. " +
                        "Use when the sender asks where the device is, where the person is, or for their location.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .required(List.of())
                        .build())
                .build();
    }

    private static Tool buildNotificationSenderTool() {
        return Tool.builder()
                .name("notification_sender")
                .description(
                        "Displays a message on the device screen. " +
                        "TOAST shows a small temporary popup (non-intrusive). " +
                        "NOTIFICATION adds a persistent notification in the status bar. " +
                        "FULLSCREEN takes over the entire screen with the message (most intrusive — use for urgent alerts). " +
                        "Use when the sender wants to leave a message or alert the device owner.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("message", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The text to display on the device"
                                )))
                                .putAdditionalProperty("display_mode", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "How prominently to display the message",
                                        "enum", List.of("TOAST", "NOTIFICATION", "FULLSCREEN")
                                )))
                                .putAdditionalProperty("sender_name", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Display name of the person sending the message, shown in the notification header"
                                )))
                                .build())
                        .required(List.of("message", "display_mode", "sender_name"))
                        .build())
                .build();
    }

    private static Tool buildCameraCaptureTool() {
        return Tool.builder()
                .name("camera_capture")
                .description(
                        "Takes a photo with the device camera and sends it to the sender via Telegram. " +
                        "Defaults: back camera, no flash. Override with camera=FRONT/BACK and flash=true/false. " +
                        "Use when the sender wants to see what is around the device.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("camera", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "FRONT or BACK (default: BACK)",
                                        "enum", List.of("FRONT", "BACK")
                                )))
                                .putAdditionalProperty("flash", JsonValue.from(Map.of(
                                        "type", "boolean",
                                        "description", "Whether to use flash (default: false)"
                                )))
                                .build())
                        .required(List.of())
                        .build())
                .build();
    }
}
