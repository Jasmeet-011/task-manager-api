package dev.jasmeet.taskmanager.task;

import com.jayway.jsonpath.JsonPath;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Optimistic locking (ADR-007) end to end through the real Spring context and H2. No @Transactional on the
 * test, so every request really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskVersioningIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    ProjectRepository projectRepository;

    @BeforeEach
    void cleanDatabase() {
        // Tasks first: they hold the foreign key to projects.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ---------- helpers ----------

    private static String updateJson(String title, long version) {
        return """
                {"title": "%s", "dueDate": "2026-12-01", "priority": "MEDIUM", "status": "TODO", "version": %d}"""
                .formatted(title, version);
    }

    /** Creates a task and returns the JSON body of the 201. */
    private String createTask(String title) throws Exception {
        String json = """
                {"title": "%s", "dueDate": "2026-12-01", "priority": "MEDIUM"}""".formatted(title);
        return mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private long createProject(String name) throws Exception {
        String body = mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}""".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static long idOf(String taskJson) {
        return ((Number) JsonPath.read(taskJson, "$.id")).longValue();
    }

    private String assign(long taskId, long projectId) throws Exception {
        return mockMvc.perform(put("/tasks/" + taskId + "/project/" + projectId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long versionIn(String taskJson) {
        return ((Number) JsonPath.read(taskJson, "$.version")).longValue();
    }

    private long versionOf(long taskId) throws Exception {
        return versionIn(mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ---------- every TaskResponse carries the version ----------

    @Test
    void create_getAndList_allCarryTheVersion_startingAtZero() throws Exception {
        String created = createTask("T");
        long id = idOf(created);

        // POST
        assertThat(versionIn(created)).isEqualTo(0);
        // GET by id
        mockMvc.perform(get("/tasks/" + id)).andExpect(jsonPath("$.version").value(0));
        // GET list
        mockMvc.perform(get("/tasks")).andExpect(jsonPath("$.content[0].version").value(0));
    }

    // ---------- PUT returns the new version ----------

    @Test
    void put_returnsTheIncrementedVersion_andTheClientCanKeepGoingWithIt() throws Exception {
        long id = idOf(createTask("Old"));

        mockMvc.perform(put("/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content(updateJson("First", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // The version in the response is the one the next PUT needs. Before the flush was added, the response
        // showed 0 here and this next call would have been a false conflict (C2 turns the check on).
        mockMvc.perform(put("/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content(updateJson("Second", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void put_thatChangesNothing_keepsTheVersion() throws Exception {
        long id = idOf(createTask("Same"));

        // Same values as the task already has. Hibernate sees no change, runs no UPDATE, so no increment.
        mockMvc.perform(put("/tasks/" + id).contentType(MediaType.APPLICATION_JSON).content(updateJson("Same", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
    }

    // ---------- assign and unassign change the task row, so they bump the version ----------

    @Test
    void assign_returnsTheNewVersion() throws Exception {
        long id = idOf(createTask("T"));
        long projectId = createProject("Work");

        String response = assign(id, projectId);

        assertThat(versionIn(response)).isEqualTo(1);
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void assign_toTheSameProjectAgain_doesNotBumpTheVersion() throws Exception {
        long id = idOf(createTask("T"));
        long projectId = createProject("Work");
        assign(id, projectId);

        String again = assign(id, projectId);

        assertThat(versionIn(again)).isEqualTo(1);
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void assign_toAnotherProject_bumpsAgain() throws Exception {
        long id = idOf(createTask("T"));
        assign(id, createProject("Work"));

        String moved = assign(id, createProject("Home"));

        assertThat(versionIn(moved)).isEqualTo(2);
    }

    @Test
    void unassign_bumpsTheVersion_butRepeatingItDoesNot() throws Exception {
        long id = idOf(createTask("T"));
        assign(id, createProject("Work"));
        assertThat(versionOf(id)).isEqualTo(1);

        mockMvc.perform(delete("/tasks/" + id + "/project")).andExpect(status().isNoContent());
        assertThat(versionOf(id)).isEqualTo(2);

        // Already unassigned: nothing changes, so nothing is written and the version stays.
        mockMvc.perform(delete("/tasks/" + id + "/project")).andExpect(status().isNoContent());
        assertThat(versionOf(id)).isEqualTo(2);
    }
}
