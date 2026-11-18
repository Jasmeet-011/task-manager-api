package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.project.Project;

import java.time.LocalDate;

public record TaskResponse(
        Long id,
        String title,
        String description,
        LocalDate dueDate,
        Priority priority,
        Status status,
        Long projectId,
        String projectName) {

    public static TaskResponse from(Task task) {
        // task.getProject() can be a lazy proxy. getId() needs no query, but getName() loads the project
        // with one SELECT. The list query fetches the project in the same SELECT (see TaskRepository), so
        // the list pays nothing extra. get, update and the assign endpoints still pay that one SELECT.
        Project project = task.getProject();
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getDueDate(),
                task.getPriority(),
                task.getStatus(),
                project == null ? null : project.getId(),
                project == null ? null : project.getName());
    }
}
