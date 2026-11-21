package dev.jasmeet.taskmanager.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** PUT is a full replacement: every field except description is required, and so is version. Omitting description clears it. */
public record UpdateTaskRequest(
        @NotBlank @Size(max = Task.TITLE_MAX, message = "must be at most " + Task.TITLE_MAX + " characters")
        String title,
        @Size(max = Task.DESCRIPTION_MAX, message = "must be at most " + Task.DESCRIPTION_MAX + " characters")
        String description,
        @NotNull LocalDate dueDate,
        @NotNull Priority priority,
        @NotNull Status status,
        // The version the client last saw (ADR-007). Required: without it the server cannot detect a conflict.
        // @Min(0) because versions start at 0, so a negative one is a malformed value, not an out-of-date one.
        @NotNull @Min(0) Long version) {
}
