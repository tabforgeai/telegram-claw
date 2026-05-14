package ai.tabforge.telegramclaw.relay;

/**
 * Creates the appropriate {@link IntentParser} implementation based on the
 * {@code LLM_PROVIDER} environment variable.
 *
 * <p>Supported values:
 * <ul>
 *   <li>{@code groq} (default) — uses {@link GroqLlmClient}; requires {@code GROQ_API_KEY}</li>
 *   <li>{@code anthropic} — uses {@link AnthropicLlmClient}; requires {@code ANTHROPIC_API_KEY}</li>
 * </ul>
 * Model is selected via {@code LLM_MODEL}; each provider has its own default if not set.</p>
 */
public class LlmClientFactory {

    public static IntentParser create() {
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "groq").toLowerCase().trim();
        return switch (provider) {
            case "groq"      -> new GroqLlmClient();
            case "anthropic" -> new AnthropicLlmClient();
            default -> throw new IllegalStateException(
                    "Unknown LLM_PROVIDER: \"" + provider + "\". Use \"groq\" or \"anthropic\".");
        };
    }
}
