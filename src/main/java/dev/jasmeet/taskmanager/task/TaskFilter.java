package dev.jasmeet.taskmanager.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The optional filters of GET /tasks. A null or empty value means "no filter on this".
 * Several values in one list mean OR (status=TODO&status=DONE). Different filters combine with AND.
 * Both dates are inclusive.
 */
public record TaskFilter(
        List<Status> status,
        List<Priority> priority,
        Long projectId,
        LocalDate dueAfter,
        LocalDate dueBefore) {

    public TaskFilter {
        // "?status=TODO," converts the empty part after the comma to null. Drop it, like a blank sort value.
        status = status == null ? List.of() : status.stream().filter(Objects::nonNull).toList();
        priority = priority == null ? List.of() : priority.stream().filter(Objects::nonNull).toList();
    }
}
