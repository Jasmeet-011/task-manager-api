package dev.jasmeet.taskmanager.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /tasks/suggest. The limit keeps cost, latency and abuse down (ADR-013). */
public record SuggestTaskRequest(
        @NotBlank
        @Size(max = SuggestTaskRequest.TEXT_MAX, message = "must be at most " + SuggestTaskRequest.TEXT_MAX + " characters")
        String text) {

    public static final int TEXT_MAX = 500;
}
