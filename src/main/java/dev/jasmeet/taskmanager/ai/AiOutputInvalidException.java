package dev.jasmeet.taskmanager.ai;

/**
 * The AI service answered, but the answer cannot be used: not JSON, no tool call, or a draft that
 * fails validation. Mapped to 502 AI_OUTPUT_INVALID, with no retry (ADR-013).
 *
 * <p>The raw output is kept for the server log, cut to RAW_OUTPUT_MAX characters so a runaway
 * response cannot flood the log. Neither the reason nor the raw output reaches the client.
 */
public class AiOutputInvalidException extends RuntimeException {

    static final int RAW_OUTPUT_MAX = 1000;

    private final String rawOutput;

    public AiOutputInvalidException(String reason, String rawOutput) {
        super(reason);
        this.rawOutput = truncate(rawOutput);
    }

    public String getRawOutput() {
        return rawOutput;
    }

    private static String truncate(String raw) {
        if (raw == null || raw.length() <= RAW_OUTPUT_MAX) {
            return raw;
        }
        return raw.substring(0, RAW_OUTPUT_MAX) + "... (" + raw.length() + " characters in total)";
    }
}
