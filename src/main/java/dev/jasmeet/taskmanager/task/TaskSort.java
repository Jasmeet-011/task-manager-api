package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.FieldViolation;
import dev.jasmeet.taskmanager.common.InvalidRequestException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the raw "sort" query values into a Spring Sort (ADR-012).
 *
 * <p>Format: "field" or "field,asc|desc", repeatable. Spring's own parser is lenient: "priority,foo"
 * silently becomes two ascending sorts, and ",desc" is ignored. This one is strict, so every bad value
 * is a 400 naming "sort".
 *
 * <p>Only the fields in {@link SortField} are allowed. A client-supplied sort replaces the default,
 * and id ascending is always the last key, so rows that tie on every other key keep a fixed order
 * and paging never repeats or drops a row.
 */
final class TaskSort {

    private static final String PARAM = "sort";

    /** What clients may sort by, and the entity property each one really sorts on. */
    private enum SortField {
        DUE_DATE("dueDate", "dueDate"),
        // Sorting the text column would give HIGH, LOW, MEDIUM. The rank column gives LOW < MEDIUM < HIGH.
        PRIORITY("priority", "priorityRank");

        private final String apiName;
        private final String property;

        SortField(String apiName, String property) {
            this.apiName = apiName;
            this.property = property;
        }
    }

    private static final Order ID_TIEBREAKER = Order.asc("id");

    // "What should I do next": nearest deadline first, most urgent first among equals.
    private static final Sort DEFAULT_SORT = Sort.by(
            Order.asc(SortField.DUE_DATE.property),
            Order.desc(SortField.PRIORITY.property),
            ID_TIEBREAKER);

    private TaskSort() {
    }

    /** @param rawSort the values of the "sort" param, one per occurrence. Null, empty or blank means not supplied. */
    static Sort resolve(List<String> rawSort) {
        List<String> entries = rawSort == null ? List.of() : rawSort.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .toList();
        if (entries.isEmpty()) {
            return DEFAULT_SORT;
        }

        List<Order> orders = new ArrayList<>();
        List<FieldViolation> violations = new ArrayList<>();
        Set<SortField> seen = new HashSet<>();
        for (String entry : entries) {
            parseEntry(entry, orders, violations, seen);
        }
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations);
        }
        orders.add(ID_TIEBREAKER);
        return Sort.by(orders);
    }

    private static void parseEntry(String entry, List<Order> orders,
            List<FieldViolation> violations, Set<SortField> seen) {
        // -1 keeps a trailing empty part, so "priority," is rejected and not read as "priority".
        String[] parts = entry.split(",", -1);
        if (parts.length > 2) {
            violations.add(violation("'" + entry + "' is not valid, use field or field,asc|desc"));
            return;
        }

        String fieldName = parts[0].trim();
        SortField field = Arrays.stream(SortField.values())
                .filter(candidate -> candidate.apiName.equals(fieldName))
                .findFirst()
                .orElse(null);
        if (field == null) {
            violations.add(violation("unknown sort field '" + fieldName + "', must be one of " + allowedFields()));
            return;
        }

        Direction direction = Direction.ASC;
        if (parts.length == 2) {
            String directionName = parts[1].trim();
            if (directionName.equalsIgnoreCase("asc")) {
                direction = Direction.ASC;
            } else if (directionName.equalsIgnoreCase("desc")) {
                direction = Direction.DESC;
            } else {
                violations.add(violation("invalid sort direction '" + directionName + "', must be asc or desc"));
                return;
            }
        }

        if (!seen.add(field)) {
            violations.add(violation("sort field '" + field.apiName + "' is given more than once"));
            return;
        }
        orders.add(new Order(direction, field.property));
    }

    private static FieldViolation violation(String message) {
        return new FieldViolation(PARAM, message);
    }

    private static String allowedFields() {
        return Arrays.stream(SortField.values())
                .map(field -> field.apiName)
                .collect(Collectors.joining(", "));
    }
}
