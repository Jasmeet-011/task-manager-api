package dev.jasmeet.taskmanager.task;

import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

/**
 * One small Specification per filter, combined with AND. A filter that was not supplied becomes
 * unrestricted(), which adds no condition. Attribute names are the entity's field names.
 */
final class TaskSpecifications {

    private TaskSpecifications() {
    }

    /** All supplied filters must match. Within status or priority, any listed value matches. */
    static Specification<Task> from(TaskFilter filter) {
        return Specification.allOf(List.of(
                statusIn(filter.status()),
                priorityIn(filter.priority()),
                inProject(filter.projectId()),
                dueOnOrAfter(filter.dueAfter()),
                dueOnOrBefore(filter.dueBefore())));
    }

    static Specification<Task> statusIn(List<Status> statuses) {
        if (statuses.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> root.get("status").in(statuses);
    }

    // Filters on the priority column itself. Only sorting needs the rank column.
    static Specification<Task> priorityIn(List<Priority> priorities) {
        if (priorities.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> root.get("priority").in(priorities);
    }

    static Specification<Task> inProject(Long projectId) {
        if (projectId == null) {
            return Specification.unrestricted();
        }
        // Compares the foreign key column, so no join on projects is needed. An unknown id matches nothing.
        return (root, query, builder) -> builder.equal(root.get("project").get("id"), projectId);
    }

    static Specification<Task> dueOnOrAfter(LocalDate date) {
        if (date == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("dueDate"), date);
    }

    static Specification<Task> dueOnOrBefore(LocalDate date) {
        if (date == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> builder.lessThanOrEqualTo(root.get("dueDate"), date);
    }
}
