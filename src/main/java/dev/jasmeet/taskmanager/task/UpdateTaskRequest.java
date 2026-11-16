package dev.jasmeet.taskmanager.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** PUT is a full replacement: every field except description is required. Omitting description clears it. */
public record UpdateTaskRequest(
        @NotBlank @Size(max = Task.TITLE_MAX, message = "must be at most " + Task.TITLE_MAX + " characters")
        String title,
        @Size(max = Task.DESCRIPTION_MAX, message = "must be at most " + Task.DESCRIPTION_MAX + " characters")
        String description,
        @NotNull LocalDate dueDate,
        @NotNull Priority priority,
        @NotNull Status status) {
}
