package dev.jasmeet.taskmanager.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutputInvalidExceptionTest {

    @Test
    void shortOutput_isKeptWhole() {
        String raw = "x".repeat(AiOutputInvalidException.RAW_OUTPUT_MAX);
        assertThat(new AiOutputInvalidException("reason", raw).getRawOutput()).isEqualTo(raw);
    }

    @Test
    void longOutput_isCutAndSaysHowLongItWas() {
        String raw = "x".repeat(AiOutputInvalidException.RAW_OUTPUT_MAX + 1);
        String kept = new AiOutputInvalidException("reason", raw).getRawOutput();
        assertThat(kept)
                .startsWith("x".repeat(AiOutputInvalidException.RAW_OUTPUT_MAX) + "...")
                .endsWith("(1001 characters in total)");
    }

    @Test
    void nullOutput_staysNull() {
        assertThat(new AiOutputInvalidException("reason", null).getRawOutput()).isNull();
    }

    @Test
    void reasonIsTheMessage() {
        assertThat(new AiOutputInvalidException("no tool call", "{}").getMessage()).isEqualTo("no tool call");
    }
}
