package dev.jasmeet.taskmanager.ai;

/** No API key is set, so the model is never called. Mapped to 503 AI_NOT_CONFIGURED. */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException() {
        super("No Anthropic API key configured: ANTHROPIC_API_KEY is not set");
    }
}
