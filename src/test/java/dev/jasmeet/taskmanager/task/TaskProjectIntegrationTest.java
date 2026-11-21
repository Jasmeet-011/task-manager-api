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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Assigning and unassigning tasks to projects, end to end. No @Transactional on the test,
 * so every request really commits and later requests read committed data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskProjectIntegrationTest {

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

    private long createTask(String title) throws Exception {
        String json = """
                {"title": "%s", "dueDate": "2026-12-01", "priority": "MEDIUM"}""".formatted(title);
        String body = mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createProject(String name) throws Exception {
        String json = """
                {"name": "%s"}""".formatted(name);
        String body = mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String assign(long taskId, long projectId) throws Exception {
        return mockMvc.perform(put("/tasks/" + taskId + "/project/" + projectId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- PUT /tasks/{id}/project/{projectId} ----------

    @Test
    void assign_returns200WithProjectFieldsAndPersistsIt() throws Exception {
        long taskId = createTask("Write report");
        long projectId = createProject("Work");

        mockMvc.perform(put("/tasks/" + taskId + "/project/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.title").value("Write report"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.projectName").value("Work"));

        mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.projectName").value("Work"));
    }

    @Test
    void assign_taskInAnotherProject_movesIt() throws Exception {
        long taskId = createTask("Write report");
        long home = createProject("Home");
        long work = createProject("Work");
        assign(taskId, home);

        mockMvc.perform(put("/tasks/" + taskId + "/project/" + work))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(work))
                .andExpect(jsonPath("$.projectName").value("Work"));

        mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(jsonPath("$.projectId").value(work));
    }

    @Test
    void assign_sameCallRepeated_returnsTheSameResponse() throws Exception {
        long taskId = createTask("Write report");
        long projectId = createProject("Work");

        String first = assign(taskId, projectId);
        String second = assign(taskId, projectId);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void assign_missingTask_returns404NamingTheTask() throws Exception {
        long projectId = createProject("Work");

        mockMvc.perform(put("/tasks/999999/project/" + projectId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Task 999999 not found"))
                .andExpect(jsonPath("$.instance").value("/tasks/999999/project/" + projectId))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void assign_missingProject_returns404NamingTheProjectAndLeavesTheTaskUnassigned() throws Exception {
        long taskId = createTask("Write report");

        mockMvc.perform(put("/tasks/" + taskId + "/project/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Project 999999 not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(jsonPath("$.projectId").doesNotExist());
    }

    @Test
    void assign_bothMissing_reportsTheTaskFirst() throws Exception {
        mockMvc.perform(put("/tasks/999998/project/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Task 999998 not found"));
    }

    @Test
    void assign_nonNumericIds_return400NamingTheField() throws Exception {
        mockMvc.perform(put("/tasks/abc/project/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));

        mockMvc.perform(put("/tasks/1/project/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("projectId"));
    }

    // ---------- DELETE /tasks/{id}/project ----------

    @Test
    void unassign_returns204AndClearsTheProject() throws Exception {
        long taskId = createTask("Write report");
        assign(taskId, createProject("Work"));

        mockMvc.perform(delete("/tasks/" + taskId + "/project"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.projectName").doesNotExist());
    }

    @Test
    void unassign_taskWithoutProject_stillReturns204() throws Exception {
        long taskId = createTask("Write report");

        mockMvc.perform(delete("/tasks/" + taskId + "/project")).andExpect(status().isNoContent());
    }

    @Test
    void unassign_twice_returns204BothTimes() throws Exception {
        long taskId = createTask("Write report");
        assign(taskId, createProject("Work"));

        mockMvc.perform(delete("/tasks/" + taskId + "/project")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/tasks/" + taskId + "/project")).andExpect(status().isNoContent());
    }

    @Test
    void unassign_missingTask_returns404() throws Exception {
        mockMvc.perform(delete("/tasks/999999/project"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Task 999999 not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void unassign_nonNumericId_returns400() throws Exception {
        mockMvc.perform(delete("/tasks/abc/project"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
    }

    // ---------- the project is independent of the task ----------

    @Test
    void putOnTheTask_keepsItsProject() throws Exception {
        long taskId = createTask("Write report");
        long projectId = createProject("Work");
        // The assignment changed the task row, so the version is now 1. A PUT must send that one.
        long version = ((Number) JsonPath.read(assign(taskId, projectId), "$.version")).longValue();

        mockMvc.perform(put("/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Renamed", "dueDate": "2026-12-24",
                                 "priority": "HIGH", "status": "IN_PROGRESS", "version": %d}""".formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.projectName").value("Work"));

        mockMvc.perform(get("/tasks/" + taskId))
                .andExpect(jsonPath("$.projectId").value(projectId));
    }

    @Test
    void putOnTheTask_ignoresAProjectIdInTheBody() throws Exception {
        long taskId = createTask("Write report");
        long other = createProject("Other");

        mockMvc.perform(put("/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "dueDate": "2026-12-24", "priority": "HIGH",
                                 "status": "TODO", "version": 0, "projectId": %d}""".formatted(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").doesNotExist());
    }

    @Test
    void deletingATask_leavesItsProject() throws Exception {
        long taskId = createTask("Write report");
        long projectId = createProject("Work");
        assign(taskId, projectId);

        mockMvc.perform(delete("/tasks/" + taskId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Work"));
    }

    @Test
    void unassigning_leavesTheProject() throws Exception {
        long taskId = createTask("Write report");
        long projectId = createProject("Work");
        assign(taskId, projectId);

        mockMvc.perform(delete("/tasks/" + taskId + "/project")).andExpect(status().isNoContent());

        mockMvc.perform(get("/projects/" + projectId)).andExpect(status().isOk());
    }

    // ---------- TaskResponse carries the project ----------

    @Test
    void list_showsProjectFieldsAndNullForAnUnassignedTask() throws Exception {
        long assigned = createTask("In a project");
        createTask("Loose");
        assign(assigned, createProject("Work"));

        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].projectName").value("Work"))
                .andExpect(jsonPath("$.content[1].projectId").doesNotExist())
                .andExpect(jsonPath("$.content[1].projectName").doesNotExist());
    }
}
