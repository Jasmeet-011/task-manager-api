package dev.jasmeet.taskmanager.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The priority_rank column is internal: no getter, not in TaskResponse. So these tests read the real
 * column with SQL and sort by the entity field, against the real database.
 */
@SpringBootTest
class PriorityRankIntegrationTest {

    private static final LocalDate DUE = LocalDate.of(2026, 12, 1);

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }

    private TaskResponse create(String title, Priority priority) {
        return taskService.create(new CreateTaskRequest(title, null, DUE, priority));
    }

    private int rankOf(Long id) {
        return jdbcTemplate.queryForObject("select priority_rank from tasks where id = ?", Integer.class, id);
    }

    @Test
    void create_storesRankLow1Medium2High3() {
        TaskResponse low = create("low", Priority.LOW);
        TaskResponse medium = create("medium", Priority.MEDIUM);
        TaskResponse high = create("high", Priority.HIGH);

        assertThat(rankOf(low.id())).isEqualTo(1);
        assertThat(rankOf(medium.id())).isEqualTo(2);
        assertThat(rankOf(high.id())).isEqualTo(3);
    }

    @Test
    void sortingByRank_ordersLowMediumHigh_notAlphabetically() {
        // Inserted out of order. Alphabetical priority order would be HIGH, LOW, MEDIUM.
        create("high", Priority.HIGH);
        create("low", Priority.LOW);
        create("medium", Priority.MEDIUM);

        List<Priority> ascending = taskRepository.findAll(Sort.by("priorityRank")).stream()
                .map(Task::getPriority)
                .toList();

        assertThat(ascending).containsExactly(Priority.LOW, Priority.MEDIUM, Priority.HIGH);
    }

    @Test
    void update_changesTheRankWithThePriority() {
        TaskResponse created = create("task", Priority.LOW);
        assertThat(rankOf(created.id())).isEqualTo(1);

        taskService.update(created.id(),
                new UpdateTaskRequest("task", null, DUE, Priority.HIGH, Status.TODO,
                        created.version()));

        assertThat(rankOf(created.id())).isEqualTo(3);
    }

    @Test
    void update_toTheSamePriority_keepsTheRank() {
        TaskResponse created = create("task", Priority.MEDIUM);

        taskService.update(created.id(),
                new UpdateTaskRequest("renamed", null, DUE, Priority.MEDIUM, Status.IN_PROGRESS,
                        created.version()));

        assertThat(rankOf(created.id())).isEqualTo(2);
    }
}
