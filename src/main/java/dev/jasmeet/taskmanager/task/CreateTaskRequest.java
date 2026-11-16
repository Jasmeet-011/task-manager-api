package dev.jasmeet.taskmanager.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** No status: every new task starts as TODO. */
public record CreateTaskRequest(
        @NotBlank @Size(max = Task.TITLE_MAX, message = "must be at most " + Task.TITLE_MAX + " characters")
        String title,
        @Size(max = Task.DESCRIPTION_MAX, message = "must be at most " + Task.DESCRIPTION_MAX + " characters")
        String description,
        @NotNull LocalDate dueDate,
        @NotNull Priority priority) {
}
