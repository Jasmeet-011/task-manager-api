package dev.jasmeet.taskmanager.task;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end CRUD through the real Spring context and H2. No @Transactional on the test,
 * so every request really commits and later requests read committed data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }

    // ---------- helpers ----------

    private static String createJson(String title, String description, String dueDate, String priority) {
        return """
                {"title": "%s", "description": %s, "dueDate": "%s", "priority": "%s"}"""
                .formatted(title, description == null ? "null" : "\"" + description + "\"", dueDate, priority);
    }

    private static String updateJson(String title, String description, String dueDate,
                                     String priority, String status) {
        // description omitted entirely when null, to prove "omitting it clears it"
        String descriptionField = description == null ? "" : "\"description\": \"" + description + "\",";
        return """
                {"title": "%s", %s "dueDate": "%s", "priority": "%s", "status": "%s"}"""
                .formatted(title, descriptionField, dueDate, priority, status);
    }

    /** Creates a task through the API and returns its Location header. */
    private String createViaApi(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getHeader("Location");
    }

    // ---------- POST /tasks ----------

    @Test
    void post_createsTaskWithStatusTodo_andLocationPointsAtTheCreatedTask() throws Exception {
        MvcResult created = mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Write report", "Q4 numbers", "2026-12-01", "HIGH")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Write report"))
                .andExpect(jsonPath("$.description").value("Q4 numbers"))
                .andExpect(jsonPath("$.dueDate").value("2026-12-01"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn();

        // A malformed Location must fail here: follow it and expect the created task.
        String location = created.getResponse().getHeader("Location");
        Number createdId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(URI.create(location).getPath()).isEqualTo("/tasks/" + createdId);

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId.intValue()))
                .andExpect(jsonPath("$.title").value("Write report"))
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void post_locationIgnoresQueryString() throws Exception {
        MvcResult created = mockMvc.perform(post("/tasks")
                        .param("foo", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("T", null, "2026-12-01", "LOW")))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(created.getResponse().getHeader("Location")).doesNotContain("?");
    }

    @Test
    void post_acceptsPastDueDate() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Overdue", null, "2020-01-01", "LOW")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value("2020-01-01"));
    }

    @Test
    void post_ignoresStatusSentByClient() throws Exception {
        // Unknown properties are ignored (tolerant reader, Boot default). New tasks are always TODO.
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "dueDate": "2026-12-01", "priority": "LOW", "status": "DONE"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void post_longestAllowedTitleAndDescription_roundTripThroughTheDatabase() throws Exception {
        // Proves the @Column lengths match the @Size limits: 200 and 2000 persist and read back intact.
        String title = "t".repeat(Task.TITLE_MAX);
        String description = "d".repeat(Task.DESCRIPTION_MAX);

        String location = createViaApi(createJson(title, description, "2026-12-01", "MEDIUM"));

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.description").value(description));
    }

    // ---------- GET /tasks ----------

    @Test
    void getList_returnsPageResponseShapeWithDefaults() throws Exception {
        createViaApi(createJson("A", null, "2026-12-01", "LOW"));

        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("A"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void getList_pagesInIdOrder() throws Exception {
        createViaApi(createJson("first", null, "2026-12-01", "LOW"));
        createViaApi(createJson("second", null, "2026-12-01", "LOW"));
        createViaApi(createJson("third", null, "2026-12-01", "LOW"));

        mockMvc.perform(get("/tasks").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("first"))
                .andExpect(jsonPath("$.content[1].title").value("second"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/tasks").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("third"))
                .andExpect(jsonPath("$.page").value(1));
    }

    // ---------- GET /tasks/{id} ----------

    @Test
    void getById_returnsTask() throws Exception {
        String location = createViaApi(createJson("A", "desc", "2026-12-01", "MEDIUM"));

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @Test
    void getById_missing_returns404() throws Exception {
        mockMvc.perform(get("/tasks/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- PUT /tasks/{id} ----------

    @Test
    void put_replacesAllFields_andTheChangePersists() throws Exception {
        String location = createViaApi(createJson("Old", "old description", "2026-12-01", "LOW"));

        mockMvc.perform(put(URI.create(location))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("New", "new description", "2026-12-24", "HIGH", "IN_PROGRESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // The PUT response is built before commit, so it proves nothing about persistence.
        // A separate GET proves the dirty-checked entity was really flushed to the database.
        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"))
                .andExpect(jsonPath("$.description").value("new description"))
                .andExpect(jsonPath("$.dueDate").value("2026-12-24"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void put_omittedDescription_clearsIt() throws Exception {
        String location = createViaApi(createJson("T", "will be cleared", "2026-12-01", "LOW"));

        mockMvc.perform(put(URI.create(location))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("T", null, "2026-12-01", "LOW", "TODO")))
                .andExpect(status().isOk());

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value((Object) null));
    }

    @Test
    void put_acceptsPastDueDate() throws Exception {
        String location = createViaApi(createJson("T", null, "2026-12-01", "LOW"));

        mockMvc.perform(put(URI.create(location))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("T", null, "2020-01-01", "LOW", "TODO")))
                .andExpect(status().isOk());

        mockMvc.perform(get(URI.create(location)))
                .andExpect(jsonPath("$.dueDate").value("2020-01-01"));
    }

    @Test
    void put_missing_returns404() throws Exception {
        mockMvc.perform(put("/tasks/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("T", null, "2026-12-01", "LOW", "TODO")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- DELETE /tasks/{id} ----------

    @Test
    void delete_removesTask_thenGetAndSecondDeleteReturn404() throws Exception {
        String location = createViaApi(createJson("T", null, "2026-12-01", "LOW"));

        mockMvc.perform(delete(URI.create(location)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(URI.create(location)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(URI.create(location)))
                .andExpect(status().isNotFound());
    }
}
