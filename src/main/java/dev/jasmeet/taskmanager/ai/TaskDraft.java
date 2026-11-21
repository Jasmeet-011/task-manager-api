package dev.jasmeet.taskmanager.ai;

import dev.jasmeet.taskmanager.task.Priority;

import java.time.LocalDate;

/**
 * An unsaved task suggested by the model. It has the same fields as CreateTaskRequest, so the UI can
 * POST it to /tasks once it is complete (ADR-003, ADR-013).
 *
 * <p>It is a separate record because dueDate and priority may be null here: the model only fills in
 * what the note says, and the user fills the gaps. There is no status: "new tasks start as TODO"
 * stays in one place, TaskService.create.
 */
public record TaskDraft(
        String title,
        String description,
        LocalDate dueDate,
        Priority priority) {
}
