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
        // task.getProject() is a lazy proxy. getId() needs no query, but getName() loads the project
        // with one SELECT. In TaskService.list this runs once per task: the N+1 that M4 fixes.
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
