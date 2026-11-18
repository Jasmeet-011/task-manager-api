package dev.jasmeet.taskmanager.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = Project.NAME_MAX, message = "must be at most " + Project.NAME_MAX + " characters")
        String name,
        @Size(max = Project.DESCRIPTION_MAX,
                message = "must be at most " + Project.DESCRIPTION_MAX + " characters")
        String description) {
}
