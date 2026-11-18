package dev.jasmeet.taskmanager.task;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sorting through GET /tasks against the real database. Rows are seeded through the service. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskSortIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }

    private TaskResponse create(String title, String dueDate, Priority priority) {
        return taskService.create(new CreateTaskRequest(title, null, LocalDate.parse(dueDate), priority));
    }

    private ResultActions list(String... paramsAsNameValuePairs) throws Exception {
        var request = get("/tasks");
        for (int i = 0; i < paramsAsNameValuePairs.length; i += 2) {
            request.param(paramsAsNameValuePairs[i], paramsAsNameValuePairs[i + 1]);
        }
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    // ---------- default sort ----------

    @Test
    void noSort_ordersByDueDateThenPriorityDescThenId() throws Exception {
        create("late-low", "2026-12-05", Priority.LOW);
        create("early-low", "2026-12-01", Priority.LOW);
        create("early-high-1", "2026-12-01", Priority.HIGH);
        create("early-high-2", "2026-12-01", Priority.HIGH);
        create("middle-medium", "2026-12-03", Priority.MEDIUM);

        list().andExpect(jsonPath("$.content[*].title").value(contains(
                "early-high-1", "early-high-2", "early-low", "middle-medium", "late-low")));
    }

    @Test
    void blankSort_usesTheDefault() throws Exception {
        create("later", "2026-12-05", Priority.HIGH);
        create("sooner", "2026-12-01", Priority.LOW);

        list("sort", "").andExpect(jsonPath("$.content[*].title").value(contains("sooner", "later")));
    }

    // ---------- priority sorts by meaning ----------

    @Test
    void priorityAscending_isLowMediumHigh_notAlphabetical() throws Exception {
        create("h", "2026-12-01", Priority.HIGH);
        create("l", "2026-12-01", Priority.LOW);
        create("m", "2026-12-01", Priority.MEDIUM);

        list("sort", "priority,asc").andExpect(jsonPath("$.content[*].priority").value(contains(
                "LOW", "MEDIUM", "HIGH")));
        list("sort", "priority").andExpect(jsonPath("$.content[*].priority").value(contains(
                "LOW", "MEDIUM", "HIGH")));
    }

    @Test
    void priorityDescending_isHighMediumLow() throws Exception {
        create("h", "2026-12-01", Priority.HIGH);
        create("l", "2026-12-01", Priority.LOW);
        create("m", "2026-12-01", Priority.MEDIUM);

        // One value that contains a comma: proves the raw value is not split by Spring's converter.
        list("sort", "priority,desc").andExpect(jsonPath("$.content[*].priority").value(contains(
                "HIGH", "MEDIUM", "LOW")));
    }

    @Test
    void changingAPriority_movesTheTaskInTheSort() throws Exception {
        TaskResponse low = create("was-low", "2026-12-01", Priority.LOW);
        create("medium", "2026-12-01", Priority.MEDIUM);

        taskService.update(low.id(),
                new UpdateTaskRequest("was-low", null, LocalDate.parse("2026-12-01"), Priority.HIGH, Status.TODO));

        list("sort", "priority,desc").andExpect(jsonPath("$.content[*].title").value(contains(
                "was-low", "medium")));
    }

    // ---------- a client sort replaces the default ----------

    @Test
    void clientSort_replacesTheDefault_andTiesFallBackToId() throws Exception {
        create("later-first-id", "2026-12-09", Priority.HIGH);
        create("sooner-second-id", "2026-12-01", Priority.HIGH);

        // The default would put the earlier due date first. Sorting by priority alone leaves a tie, and id breaks it.
        list("sort", "priority,desc").andExpect(jsonPath("$.content[*].title").value(contains(
                "later-first-id", "sooner-second-id")));
    }

    @Test
    void dueDateDescending() throws Exception {
        create("a", "2026-12-01", Priority.LOW);
        create("b", "2026-12-09", Priority.LOW);
        create("c", "2026-12-05", Priority.LOW);

        list("sort", "dueDate,desc").andExpect(jsonPath("$.content[*].title").value(contains("b", "c", "a")));
    }

    @Test
    void repeatedSortParams_applyInOrder() throws Exception {
        create("low-early", "2026-12-01", Priority.LOW);
        create("high-late", "2026-12-09", Priority.HIGH);
        create("high-early", "2026-12-01", Priority.HIGH);

        list("sort", "priority,desc", "sort", "dueDate,asc")
                .andExpect(jsonPath("$.content[*].title").value(contains("high-early", "high-late", "low-early")));
    }

    // ---------- paging is stable ----------

    @Test
    void tiedRows_appearExactlyOnceAcrossPages() throws Exception {
        List<Long> createdIds = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            createdIds.add(create("task-" + i, "2026-12-01", Priority.MEDIUM).id());
        }

        // Every row ties on priority, so only the id tiebreaker keeps the pages consistent.
        List<Integer> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("sort", "priority,desc", "page", String.valueOf(page), "size", "3")
                    .andReturn().getResponse().getContentAsString();
            List<Integer> ids = JsonPath.read(body, "$.content[*].id");
            seen.addAll(ids);
        }

        assertThat(seen).containsExactlyElementsOf(createdIds.stream().map(Long::intValue).toList());
    }

    // ---------- the rank stays internal ----------

    @Test
    void responseNeverContainsThePriorityRank() throws Exception {
        create("t", "2026-12-01", Priority.HIGH);

        list().andExpect(jsonPath("$.content[0].priorityRank").doesNotExist())
                .andExpect(jsonPath("$.content[0].priority").value("HIGH"));
    }
}
