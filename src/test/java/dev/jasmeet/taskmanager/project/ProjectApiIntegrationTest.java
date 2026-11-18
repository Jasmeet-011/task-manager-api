package dev.jasmeet.taskmanager.project;

import dev.jasmeet.taskmanager.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Project endpoints end to end through the real Spring context and H2. */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        // Tasks first: they hold the foreign key to projects.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private static String projectJson(String name, String description) {
        return """
                {"name": "%s", "description": %s}"""
                .formatted(name, description == null ? "null" : "\"" + description + "\"");
    }

    private int postStatus(String json) throws Exception {
        return mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    // ---------- POST /projects ----------

    @Test
    void create_returns201WithLocationAndBody() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Home", "chores")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/projects/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Home"))
                .andExpect(jsonPath("$.description").value("chores"));
    }

    private Long firstProjectId() {
        return projectRepository.findAll().get(0).getId();
    }

    @Test
    void create_trimsTheName() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("  Home  ", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Home"));
    }

    @Test
    void create_acceptsNameAndDescriptionAtTheirLimits() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("n".repeat(Project.NAME_MAX), "d".repeat(Project.DESCRIPTION_MAX))))
                .andExpect(status().isCreated());
    }

    @Test
    void create_blankName_returns400NamingTheField() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void create_tooLongNameAndDescription_listsBothFields() throws Exception {
        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("n".repeat(Project.NAME_MAX + 1),
                                "d".repeat(Project.DESCRIPTION_MAX + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')].message")
                        .value("must be at most 100 characters"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='description')].message")
                        .value("must be at most 500 characters"));
    }

    @Test
    void create_missingBody_returns400Malformed() throws Exception {
        mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---------- duplicate names ----------

    @Test
    void create_duplicateName_returns409AndLeavesTheFirstProjectUnchanged() throws Exception {
        postStatus(projectJson("Home", "original"));

        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Home", "second")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DUPLICATE_PROJECT_NAME"))
                .andExpect(jsonPath("$.instance").value("/projects"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("already exists"));

        mockMvc.perform(get("/projects"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value("original"));
    }

    @Test
    void create_duplicateAfterTrimming_returns409() throws Exception {
        postStatus(projectJson("Home", null));

        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("  Home ", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PROJECT_NAME"));
    }

    @Test
    void create_nameDifferingOnlyInCase_isAllowed() throws Exception {
        // Names are case-sensitive on purpose (ADR-011). This test documents that limit.
        postStatus(projectJson("Home", null));

        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("home", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void database_itselfRejectsDuplicateNames() {
        // No service and no check-then-insert here: only the schema can stop this.
        projectRepository.saveAndFlush(new Project("Home", null));

        assertThatThrownBy(() -> projectRepository.saveAndFlush(new Project("Home", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void create_twoConcurrentRequestsWithTheSameName_oneWinsOneConflicts() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return postStatus(projectJson("Race", null));
                }));
            }
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(projectRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- GET /projects ----------

    @Test
    void list_whenEmpty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void list_returnsAllProjectsInIdOrder() throws Exception {
        postStatus(projectJson("Work", "office"));
        postStatus(projectJson("Home", null));

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Work"))
                .andExpect(jsonPath("$[0].description").value("office"))
                .andExpect(jsonPath("$[1].name").value("Home"))
                .andExpect(jsonPath("$[1].description").doesNotExist());
    }

    // ---------- GET /projects/{id} ----------

    @Test
    void get_returnsTheProject() throws Exception {
        postStatus(projectJson("Home", "chores"));
        Long id = firstProjectId();

        mockMvc.perform(get("/projects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Home"))
                .andExpect(jsonPath("$.description").value("chores"));
    }

    @Test
    void get_missingProject_returns404WithErrorBody() throws Exception {
        mockMvc.perform(get("/projects/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Project 999999 not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void get_nonNumericId_returns400NamingTheField() throws Exception {
        mockMvc.perform(get("/projects/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
    }
}
