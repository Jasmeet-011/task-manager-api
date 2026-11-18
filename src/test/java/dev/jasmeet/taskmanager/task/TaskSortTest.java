package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.FieldViolation;
import dev.jasmeet.taskmanager.common.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskSortTest {

    private static final Sort DEFAULT = Sort.by(
            Order.asc("dueDate"), Order.desc("priorityRank"), Order.asc("id"));

    // ---------- default ----------

    @Test
    void nothingSupplied_usesDefault() {
        assertThat(TaskSort.resolve(null)).isEqualTo(DEFAULT);
        assertThat(TaskSort.resolve(List.of())).isEqualTo(DEFAULT);
    }

    @Test
    void blankValues_countAsNotSupplied() {
        assertThat(TaskSort.resolve(List.of(""))).isEqualTo(DEFAULT);
        assertThat(TaskSort.resolve(Arrays.asList("  ", null))).isEqualTo(DEFAULT);
    }

    // ---------- valid input ----------

    @Test
    void priority_sortsOnTheRankColumn_andIdIsLast() {
        assertThat(TaskSort.resolve(List.of("priority,desc")))
                .isEqualTo(Sort.by(Order.desc("priorityRank"), Order.asc("id")));
    }

    @Test
    void directionDefaultsToAscending() {
        assertThat(TaskSort.resolve(List.of("priority")))
                .isEqualTo(Sort.by(Order.asc("priorityRank"), Order.asc("id")));
        assertThat(TaskSort.resolve(List.of("dueDate")))
                .isEqualTo(Sort.by(Order.asc("dueDate"), Order.asc("id")));
    }

    @Test
    void directionIsCaseInsensitive_fieldNameIsNot() {
        assertThat(TaskSort.resolve(List.of("dueDate,DESC")))
                .isEqualTo(Sort.by(Order.desc("dueDate"), Order.asc("id")));
        assertThatThrownBy(() -> TaskSort.resolve(List.of("duedate")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void severalSorts_keepTheirOrder_andClientSortReplacesTheDefault() {
        assertThat(TaskSort.resolve(List.of("priority,desc", "dueDate,desc")))
                .isEqualTo(Sort.by(Order.desc("priorityRank"), Order.desc("dueDate"), Order.asc("id")));
    }

    @Test
    void blankEntriesBesideRealOnes_areIgnored() {
        assertThat(TaskSort.resolve(List.of("", "priority,desc")))
                .isEqualTo(Sort.by(Order.desc("priorityRank"), Order.asc("id")));
    }

    // ---------- invalid input: always InvalidRequestException naming "sort" ----------

    private static List<FieldViolation> violationsFor(String... rawSort) {
        InvalidRequestException thrown = assertThrows(
                InvalidRequestException.class, () -> TaskSort.resolve(List.of(rawSort)));
        return thrown.getFieldErrors();
    }

    @Test
    void unknownField_isRejected_andAllowedFieldsAreListed() {
        for (String field : List.of("title", "id", "status", "priorityRank", "project")) {
            List<FieldViolation> violations = violationsFor(field);

            assertThat(violations).hasSize(1);
            assertThat(violations.get(0).field()).isEqualTo("sort");
            assertThat(violations.get(0).message())
                    .contains("unknown sort field '" + field + "'")
                    .contains("dueDate, priority");
        }
    }

    @Test
    void badDirection_isRejected() {
        List<FieldViolation> violations = violationsFor("priority,sideways");

        assertThat(violations).containsExactly(new FieldViolation("sort",
                "invalid sort direction 'sideways', must be asc or desc"));
    }

    @Test
    void emptyDirection_isRejected() {
        assertThat(violationsFor("priority,").get(0).message()).contains("invalid sort direction ''");
    }

    @Test
    void emptyField_isRejected() {
        assertThat(violationsFor(",desc").get(0).message()).contains("unknown sort field ''");
    }

    @Test
    void tooManyParts_isRejected() {
        assertThat(violationsFor("priority,desc,asc").get(0).message()).contains("is not valid");
        assertThat(violationsFor("dueDate,desc,priority").get(0).message()).contains("is not valid");
    }

    @Test
    void sameFieldTwice_isRejected() {
        assertThat(violationsFor("priority,asc", "priority,desc").get(0).message())
                .isEqualTo("sort field 'priority' is given more than once");
    }

    @Test
    void everyBadEntryIsReported() {
        List<FieldViolation> violations = violationsFor("title", "priority,sideways");

        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(violation -> assertThat(violation.field()).isEqualTo("sort"));
    }
}
