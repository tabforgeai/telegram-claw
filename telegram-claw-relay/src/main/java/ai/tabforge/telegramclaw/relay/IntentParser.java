package ai.tabforge.telegramclaw.relay;

import ai.tabforge.telegramclaw.relay.model.ClawCommand;
import com.anthropic.client.AnthropicClient;
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
 * Converts a natural language Telegram message into a structured tool call by asking Claude.
 *
 * <p>Analogy: like a human dispatcher at an emergency call center — the caller says
 * "there's a fire on the third floor", and the dispatcher translates that into a structured
 * work order: truck number, address, priority level. This class is that dispatcher:
 * raw human text goes in, a precise machine instruction ({@link ClawCommand}) comes out.
 * Claude is the dispatcher's brain — we supply the list of available actions (tools),
 * Claude picks the right one and fills in the parameters.</p>
 *
 * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} after the sender has been
 * identified and their message text extracted. One Claude API call is made per incoming
 * Telegram message.</p>
 *
 * <p>Model selection: reads the {@code CLAUDE_MODEL} environment variable.
 * Defaults to {@code claude-haiku-4-5-20251001} (fast and cheap for intent parsing).
 * Switch to {@code claude-sonnet-4-6} for more complex multi-step reasoning.</p>
 */
public class IntentParser {

    private static final Logger log = LoggerFactory.getLogger(IntentParser.class);
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

    /**
     * Constructs an IntentParser using the given Anthropic API client.
     *
     * <p>Analogy: like hiring a dispatcher and giving them the company's procedure manual
     * at the start of their shift — the client is the phone line to Claude, the tool manifest
     * is the list of available actions the dispatcher is trained on. Both are set once
     * and reused for every incoming message.</p>
     *
     * <p>Called by: {@link Main#main} once at startup; the same instance handles all messages.</p>
     *
     * @param client  the Anthropic API client, configured with the API key from
     *                the {@code ANTHROPIC_API_KEY} environment variable
     */
    public IntentParser(AnthropicClient client) {
        this.client = client;
        this.model = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-haiku-4-5-20251001");
        this.toolManifest = buildToolManifest();
        log.info("IntentParser initialized | model: {} | tools: {}", model, toolManifest.size());
    }

    /**
     * Parses a raw natural language Telegram message into a structured tool call.
     *
     * <p>Analogy: like sending a voice memo to a translator who not only transcribes it
     * but also identifies the action to take and fills out the appropriate form. The user
     * says "pojacaj mu zvono" in Serbian; Claude hears it, recognizes the intent, and
     * returns a filled-out "audio_manager" work order with stream=RING, level=100,
     * force_ping=true.</p>
     *
     * <p>Called by: {@link TelegramUpdateReceiver#handleMessage} for every incoming text message.
     * This is a blocking call — it waits for the Claude API response before returning.</p>
     *
     * @param messageText   the raw Telegram message from the authorized sender,
     *                      e.g. "Pojacaj mu zvono, hitno ga trazim";
     *                      may be in any language
     * @param senderChatId  the Telegram chat ID of the sender, stored in the returned
     *                      {@link ClawCommand} so {@code ResponseRouter} knows where to
     *                      send the reply (Phase 1 Day 8)
     * @return              a {@link ClawCommand} with: tool name, parameters as JSON,
     *                      and Claude's natural-language reply (if any) to send back to the sender
     * @throws IntentParseException  if Claude returns no tool call, if the returned tool
     *                               name is not in the manifest, or if the Claude API call fails
     */
    public ClawCommand parseIntent(String messageText, long senderChatId) throws IntentParseException {
        log.debug("Parsing intent: \"{}\"", messageText);

        try {
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(messageText);
            for (Tool tool : toolManifest) {
                paramsBuilder.addTool(tool);
            }
            MessageCreateParams params = paramsBuilder.build();

            Message response = client.messages().create(params);
            log.debug("Claude stop_reason: {}", response.stopReason());

            String toolName = null;
            JsonNode parameters = null;
            String naturalLanguageReply = null;

            for (ContentBlock block : response.content()) {
                if (block.toolUse().isPresent()) {
                    var tu = block.toolUse().get();
                    toolName = tu.name();
                    // _input() returns the raw JsonValue; serialize via Jackson to get a JsonNode
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
                        "Claude returned no tool call for message: \"" + messageText + "\"" +
                        " | stop_reason: " + response.stopReason());
            }

            return new ClawCommand(toolName, parameters, naturalLanguageReply, senderChatId);

        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Interprets a raw tool result in natural language by asking Claude.
     *
     * <p>Analogy: like a lab technician translating a blood test printout into plain English
     * for a patient — the raw numbers (device sensor readings) are accurate but unreadable;
     * Claude reads the printout and says "Your iron is a little low, but everything else looks fine."
     * This is the second Claude call in the request-response loop: the first call (in
     * {@link #parseIntent}) picks the tool; this call interprets the device's answer.</p>
     *
     * <p>Called by: {@link CallbackReceiver#handle} for query tools after Android reports back.
     * Uses a plain text call (no tool manifest) so Claude answers conversationally.</p>
     *
     * @param toolName   the tool that produced the result, e.g. {@code "get_device_context"}
     * @param rawResult  the raw result string from the Android device
     * @return  Claude's 1-2 sentence natural language interpretation, or the raw result if the call fails
     */
    /**
     * Interprets a raw tool result in natural language by asking Claude.
     *
     * <p>Analogy: like a lab technician translating a blood test printout into plain English
     * for a patient — the raw numbers (device sensor readings) are accurate but unreadable;
     * Claude reads them and says "Your iron is a little low, but everything else looks fine."
     * This is the second Claude call in the request-response loop: the first call (in
     * {@link #parseIntent}) picks the tool; this call interprets the device's answer.</p>
     *
     * <p>The original question is included so Claude can:
     * <ol>
     *   <li>Respond in the same language the person used (Serbian, English, etc.)</li>
     *   <li>Frame the answer in terms of what the person actually wanted to know</li>
     *   <li>End with a direct conclusion ("Based on this, they are probably sleeping.")</li>
     * </ol>
     * </p>
     *
     * <p>Called by: {@link CallbackReceiver#handle} for query tools after Android reports back.</p>
     *
     * @param toolName          the tool that produced the result, e.g. {@code "get_device_context"}
     * @param rawResult         the raw result string from the Android device
     * @param originalQuestion  the original message from Person A, used for language detection
     *                          and answer framing; may be null if not available
     * @return  Claude's natural language answer, or a formatted fallback if the call fails
     */
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

    /**
     * Builds the fixed list of tools that every device exposes.
     *
     * <p>Analogy: like printing the menu at a restaurant — the menu (tool manifest) is
     * the same for every customer (incoming message). Claude reads the menu and picks
     * the appropriate dish (tool). In Phase 3+, this will be filtered per device based
     * on the device's {@code PermissionManifest}; for Phase 1 we always send all 6 tools.</p>
     *
     * <p>Called by: constructor, once at startup.</p>
     *
     * @return  unmodifiable list of all 6 Telegram Claw tools, each with full JSON schema
     */
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

    /**
     * Defines the {@code get_device_context} tool — reads passive device sensors.
     *
     * <p>Analogy: like a doctor checking a patient's vitals before prescribing treatment —
     * Claude needs the device's current state (battery, screen, sound profile, motion, light)
     * before it can make a sensible decision about what action to take.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code get_device_context}; no parameters required
     */
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

    /**
     * Defines the {@code media_control} tool — controls media playback.
     *
     * <p>Analogy: like a remote control for someone else's TV — you decide what plays
     * without touching their device. PLAY/PAUSE/SKIP control the current media session;
     * OPEN_URL launches a Spotify or YouTube link directly on the device.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code media_control}
     */
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

    /**
     * Defines the {@code audio_manager} tool — sets volume or forces a loud alert ping.
     *
     * <p>Analogy: like a fire alarm pull station — in normal circumstances the building
     * respects "quiet hours", but the pull station bypasses all normal rules when there
     * is an emergency. The {@code force_ping} parameter is that pull station: it overrides
     * silent mode and plays a loud alert even if the device is on silent.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code audio_manager}
     */
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

    /**
     * Defines the {@code location_fetcher} tool — requests the device's current GPS location.
     *
     * <p>Analogy: like sharing your live location on Google Maps, but on demand —
     * the device returns a one-time coordinate snapshot. This tool always triggers a
     * Human-in-Loop confirmation on the Android device regardless of whitelist settings;
     * the device owner must physically approve every location request.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code location_fetcher}
     */
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

    /**
     * Defines the {@code notification_sender} tool — displays a message on the device.
     *
     * <p>Analogy: like leaving a sticky note on someone's door — the message appears
     * on the device screen without being a regular Telegram chat message. The device
     * owner sees it immediately regardless of what app they are in.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code notification_sender}
     */
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

    /**
     * Defines the {@code camera_capture} tool — takes a photo with the device camera.
     *
     * <p>Analogy: like asking a friend to take a photo of something for you — the device
     * becomes your eyes when you cannot be there. The photo is sent back to the relay server
     * and forwarded to the sender as a Telegram photo message via the {@code sendPhoto} API.
     * This tool always requires explicit confirmation from the device owner.</p>
     *
     * <p>Called by: {@link #buildToolManifest()}, once at startup.</p>
     *
     * @return  Tool definition for {@code camera_capture}
     */
    private static Tool buildCameraCaptureTool() {
        return Tool.builder()
                .name("camera_capture")
                .description(
                        "Takes a photo with the device camera and sends it back to the sender via Telegram. " +
                        "FRONT uses the selfie camera. BACK uses the rear camera. " +
                        "IMPORTANT: this always requires explicit confirmation from the device owner — " +
                        "it cannot be pre-approved. Use when the sender wants to see what is in front of the camera.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("camera", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Which camera to use",
                                        "enum", List.of("FRONT", "BACK")
                                )))
                                .putAdditionalProperty("flash", JsonValue.from(Map.of(
                                        "type", "boolean",
                                        "description", "Whether to use the flash"
                                )))
                                .build())
                        .required(List.of("camera", "flash"))
                        .build())
                .build();
    }
}