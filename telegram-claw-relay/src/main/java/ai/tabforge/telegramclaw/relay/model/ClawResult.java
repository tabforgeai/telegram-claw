package ai.tabforge.telegramclaw.relay.model;

/**
 * The result returned by the Android Claw app after executing a ClawCommand.
 *
 * <p>Analogy: like a delivery confirmation receipt — after a package (command) is
 * delivered and the task is done, the courier (Android app) sends back a signed
 * receipt: whether it was accepted, what actually happened, and when. The relay
 * server uses this receipt to compose the reply message for Person A in Telegram.</p>
 *
 * <p>Produced by: the Android Claw app (Phase 2) after command execution.
 * Consumed by: ResponseRouter, which formats it as a Telegram sendMessage call.</p>
 *
 * <p>Note: skeleton for Day 1. Full implementation in Phase 2.</p>
 */
public class ClawResult {

    public enum Status { SUCCESS, DENIED, TIMEOUT, ERROR }

    private Status status;

    /** Human-readable description of what happened, included in the Telegram reply. */
    private String description;

    /** Raw result payload — structure varies by tool (e.g., JSON for get_device_context). */
    private String rawPayload;

    /** Unix timestamp (ms) when the command was executed on the device. */
    private long executedAt;

    public ClawResult() {}

    public ClawResult(Status status, String description, String rawPayload, long executedAt) {
        this.status = status;
        this.description = description;
        this.rawPayload = rawPayload;
        this.executedAt = executedAt;
    }

    public Status getStatus() { return status; }
    public String getDescription() { return description; }
    public String getRawPayload() { return rawPayload; }
    public long getExecutedAt() { return executedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setDescription(String description) { this.description = description; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public void setExecutedAt(long executedAt) { this.executedAt = executedAt; }
}
