package dev.jasmeet.taskmanager.task;

import com.jayway.jsonpath.JsonPath;
import dev.jasmeet.taskmanager.project.CreateProjectRequest;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import dev.jasmeet.taskmanager.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filters on GET /tasks against the real database. Every test starts from the same nine tasks.
 * Listed in the default sort order (dueDate asc, priority desc, id asc), which is what the tests expect:
 *
 * <pre>
 * title           due         priority  status       project
 * before-range    2026-11-15  HIGH      TODO         Work
 * m1              2026-11-16  HIGH      TODO         Work
 * m2              2026-11-18  HIGH      IN_PROGRESS  Work
 * done            2026-11-18  HIGH      DONE         Work
 * other-project   2026-11-18  HIGH      TODO         Home
 * no-project      2026-11-18  HIGH      TODO         (none)
 * medium          2026-11-18  MEDIUM    TODO         Work
 * m3              2026-11-22  HIGH      TODO         Work
 * after-range     2026-11-23  HIGH      TODO         Work
 * </pre>
 *
 * m1, m2 and m3 are the only tasks that match: status TODO or IN_PROGRESS, priority HIGH, project Work,
 * due from 2026-11-16 to 2026-11-22. m1 and m3 sit exactly on the edges.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskFilterIntegrationTest {

    private static final List<String> ALL_IN_DEFAULT_ORDER = List.of(
            "before-range", "m1", "m2", "done", "other-project", "no-project", "medium", "m3", "after-range");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    ProjectService projectService;

    @Autowired
    ProjectRepository projectRepository;

    long workId;
    long homeId;

    @BeforeEach
    void seed() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        workId = projectService.create(new CreateProjectRequest("Work", null)).id();
        homeId = projectService.create(new CreateProjectRequest("Home", null)).id();

        task("m1", "2026-11-16", Priority.HIGH, Status.TODO, workId);
        task("m2", "2026-11-18", Priority.HIGH, Status.IN_PROGRESS, workId);
        task("m3", "2026-11-22", Priority.HIGH, Status.TODO, workId);
        task("before-range", "2026-11-15", Priority.HIGH, Status.TODO, workId);
        task("after-range", "2026-11-23", Priority.HIGH, Status.TODO, workId);
        task("done", "2026-11-18", Priority.HIGH, Status.DONE, workId);
        task("medium", "2026-11-18", Priority.MEDIUM, Status.TODO, workId);
        task("other-project", "2026-11-18", Priority.HIGH, Status.TODO, homeId);
        task("no-project", "2026-11-18", Priority.HIGH, Status.TODO, null);
    }

    private void task(String title, String dueDate, Priority priority, Status status, Long projectId) {
        LocalDate due = LocalDate.parse(dueDate);
        Long id = taskService.create(new CreateTaskRequest(title, null, due, priority)).id();
        if (status != Status.TODO) {
            taskService.update(id, new UpdateTaskRequest(title, null, due, priority, status));
        }
        if (projectId != null) {
            taskService.assignProject(id, projectId);
        }
    }

    private ResultActions list(String... nameValuePairs) throws Exception {
        MockHttpServletRequestBuilder request = get("/tasks");
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private List<Integer> idsOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.content[*].id");
    }

    // ---------- the required test: several filters combined with pagination ----------

    @Test
    void combinedFilters_withPagination_returnsTheRightRowsAndTotals() throws Exception {
        String[] filters = {
                "status", "TODO", "status", "IN_PROGRESS",
                "priority", "HIGH",
                "projectId", String.valueOf(workId),
                "dueAfter", "2026-11-16",
                "dueBefore", "2026-11-22",
                "size", "2"};

        // Page 0: the two earliest matches. m1 is due exactly on dueAfter, so the range is inclusive.
        list(withPage(filters, "0"))
                .andExpect(jsonPath("$.content[*].title").value(contains("m1", "m2")))
                .andExpect(jsonPath("$.content[0].projectName").value("Work"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Page 1: m3 is due exactly on dueBefore.
        list(withPage(filters, "1"))
                .andExpect(jsonPath("$.content[*].title").value(contains("m3")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    private static String[] withPage(String[] filters, String page) {
        String[] all = Arrays.copyOf(filters, filters.length + 2);
        all[filters.length] = "page";
        all[filters.length + 1] = page;
        return all;
    }

    // ---------- each filter alone ----------

    @Test
    void noFilters_returnsEverything_inTheDefaultOrder() throws Exception {
        list().andExpect(jsonPath("$.content[*].title").value(contains(ALL_IN_DEFAULT_ORDER.toArray())))
                .andExpect(jsonPath("$.totalElements").value(9));
    }

    @Test
    void blankValues_areTreatedAsNotSupplied() throws Exception {
        list("status", "", "priority", "", "projectId", "", "dueAfter", "", "dueBefore", "", "sort", "")
                .andExpect(jsonPath("$.totalElements").value(9));
    }

    @Test
    void singleStatus() throws Exception {
        list("status", "DONE").andExpect(jsonPath("$.content[*].title").value(contains("done")));
    }

    @Test
    void singlePriority() throws Exception {
        list("priority", "MEDIUM").andExpect(jsonPath("$.content[*].title").value(contains("medium")));
    }

    @Test
    void projectId_returnsOnlyThatProjectsTasks() throws Exception {
        list("projectId", String.valueOf(homeId))
                .andExpect(jsonPath("$.content[*].title").value(contains("other-project")));
        list("projectId", String.valueOf(workId))
                .andExpect(jsonPath("$.totalElements").value(7));
    }

    @Test
    void unknownProjectId_isAnEmptyList_not404() throws Exception {
        list("projectId", "999999")
                .andExpect(jsonPath("$.content").value(empty()))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ---------- several values for one filter: OR within, repeated form and comma form ----------

    @Test
    void severalStatuses_repeatedForm_matchAnyOfThem() throws Exception {
        list("status", "TODO", "status", "IN_PROGRESS")
                .andExpect(jsonPath("$.content[*].title").value(contains(
                        "before-range", "m1", "m2", "other-project", "no-project", "medium", "m3", "after-range")));
    }

    @Test
    void severalStatuses_commaForm_givesTheSameResultAsTheRepeatedForm() throws Exception {
        List<Integer> repeated = idsOf(list("status", "TODO", "status", "IN_PROGRESS"));
        List<Integer> comma = idsOf(list("status", "TODO,IN_PROGRESS"));

        assertThat(comma).hasSize(8).isEqualTo(repeated);
    }

    @Test
    void severalPriorities_commaFormAndRepeatedForm_giveTheSameResult() throws Exception {
        List<Integer> repeated = idsOf(list("priority", "LOW", "priority", "MEDIUM"));
        List<Integer> comma = idsOf(list("priority", "LOW,MEDIUM"));

        assertThat(comma).hasSize(1).isEqualTo(repeated);
    }

    @Test
    void commaFormWithTrailingComma_ignoresTheEmptyPart() throws Exception {
        list("status", "DONE,").andExpect(jsonPath("$.content[*].title").value(contains("done")));
    }

    @Test
    void orWithinAFilter_andAcrossFilters() throws Exception {
        // (DONE or IN_PROGRESS) and MEDIUM: no task is both, so nothing matches.
        list("status", "DONE,IN_PROGRESS", "priority", "MEDIUM")
                .andExpect(jsonPath("$.content").value(empty()));
        // (DONE or IN_PROGRESS) and HIGH matches both done and m2.
        list("status", "DONE,IN_PROGRESS", "priority", "HIGH")
                .andExpect(jsonPath("$.content[*].title").value(contains("m2", "done")));
    }

    // ---------- date range: inclusive on both ends ----------

    @Test
    void dueAfterAlone_includesThatDay() throws Exception {
        list("dueAfter", "2026-11-22").andExpect(jsonPath("$.content[*].title").value(contains("m3", "after-range")));
    }

    @Test
    void dueBeforeAlone_includesThatDay() throws Exception {
        list("dueBefore", "2026-11-16").andExpect(jsonPath("$.content[*].title").value(contains("before-range", "m1")));
    }

    @Test
    void equalDates_meanDueExactlyThatDay() throws Exception {
        list("dueAfter", "2026-11-16", "dueBefore", "2026-11-16")
                .andExpect(jsonPath("$.content[*].title").value(contains("m1")));
    }

    // ---------- filters work together with sort ----------

    @Test
    void filtersAndSort_combine() throws Exception {
        list("projectId", String.valueOf(workId), "dueAfter", "2026-11-22", "sort", "dueDate,desc")
                .andExpect(jsonPath("$.content[*].title").value(contains("after-range", "m3")));
    }
}
