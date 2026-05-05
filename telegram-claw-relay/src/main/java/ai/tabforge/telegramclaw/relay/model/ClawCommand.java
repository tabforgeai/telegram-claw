package ai.tabforge.telegramclaw.relay.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A structured command that the relay server sends to an Android Claw device.
 *
 * <p>Analogy: like a work order in a factory — the manager (Claude) decides what needs
 * to be done and fills out a standardized form. The worker (Android app) reads the form,
 * checks if they're allowed to do that task, and executes it. The form always has a task
 * type (toolName) and task parameters, regardless of what the task actually is.</p>
 *
 * <p>Produced by: IntentParser (Phase 1, Day 3) after Claude returns a tool call.
 * Consumed by: CommandDispatcher, which encrypts it and sends it via FCM.</p>
 *
 * <p>Note: this class is a skeleton for Day 1. Full implementation comes in Phase 2
 * when we wire up CommandDispatcher and the Android receiver.</p>
 */
public class ClawCommand {

    /** The tool to invoke on the device, e.g. "audio_manager", "get_device_context". */
    private String toolName;

    /**
     * Tool parameters as a JSON tree — structure varies by tool.
     * Example for audio_manager: {"stream":"RING","level":100,"force_ping":false}
     */
    private JsonNode parameters;

    /**
     * Claude's natural-language reply to send back to Person A via Telegram sendMessage.
     * Example: "Pojacavam mu zvono na maksimum."
     */
    private String naturalLanguageReply;

    /** The Telegram chat ID of the sender — used by ResponseRouter to deliver the reply. */
    private long senderChatId;

    public ClawCommand() {}

    public ClawCommand(String toolName, JsonNode parameters, String naturalLanguageReply, long senderChatId) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.naturalLanguageReply = naturalLanguageReply;
        this.senderChatId = senderChatId;
    }

    public String getToolName() { return toolName; }
    public JsonNode getParameters() { return parameters; }
    public String getNaturalLanguageReply() { return naturalLanguageReply; }
    public long getSenderChatId() { return senderChatId; }

    public void setToolName(String toolName) { this.toolName = toolName; }
    public void setParameters(JsonNode parameters) { this.parameters = parameters; }
    public void setNaturalLanguageReply(String naturalLanguageReply) { this.naturalLanguageReply = naturalLanguageReply; }
    public void setSenderChatId(long senderChatId) { this.senderChatId = senderChatId; }

    @Override
    public String toString() {
        return "ClawCommand{tool=" + toolName + ", senderChatId=" + senderChatId + "}";
    }
}
