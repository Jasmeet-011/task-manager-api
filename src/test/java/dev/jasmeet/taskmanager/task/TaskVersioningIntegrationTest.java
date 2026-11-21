package dev.jasmeet.taskmanager.task;

import com.jayway.jsonpath.JsonPath;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Autowired
    TaskService taskService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        // Tasks first: they hold the foreign key to projects.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ---------- helpers ----------

    private record Outcome(int status, String title) {
    }

    private ResultActions putTask(long id, String title, long version) throws Exception {
        return mockMvc.perform(put("/tasks/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(updateJson(title, version)));
    }

    /** Starts all requests together (behind a latch) and returns their HTTP statuses, in the order given. */
    private static List<Integer> runAtTheSameTime(List<Callable<Integer>> requests) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> request : requests) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return request.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private int assignStatus(long taskId, long projectId) throws Exception {
        return mockMvc.perform(put("/tasks/" + taskId + "/project/" + projectId))
                .andReturn().getResponse().getStatus();
    }

    private int deleteStatus(long taskId) throws Exception {
        return mockMvc.perform(delete("/tasks/" + taskId)).andReturn().getResponse().getStatus();
    }

    /** The whole ADR-005 body of a version conflict, not just the status. */
    private static void expectVersionConflict(ResultActions actions, long id) throws Exception {
        actions.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/tasks/" + id))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

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

    // ---------- a stale version is a 409 ----------

    @Test
    void put_withTheRightVersion_succeeds_thenTheSameVersionAgainIsA409() throws Exception {
        long id = idOf(createTask("Start"));

        putTask(id, "First", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        // Version 0 is now stale. The body is the full ADR-005 shape.
        expectVersionConflict(putTask(id, "Second", 0), id);

        // The rejected PUT changed nothing: the first PUT's data is still stored, at version 1.
        mockMvc.perform(get("/tasks/" + id))
                .andExpect(jsonPath("$.title").value("First"))
                .andExpect(jsonPath("$.version").value(1));

        // After re-fetching, the current version works.
        putTask(id, "Third", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void put_staleVersion_isRejectedEvenWhenItWouldChangeNothing() throws Exception {
        long id = idOf(createTask("Start"));
        putTask(id, "Changed", 0).andExpect(status().isOk());

        // Same values as the stored task, but the client's copy is out of date. The comparison runs first.
        expectVersionConflict(putTask(id, "Changed", 0), id);
    }

    @Test
    void put_aVersionAheadOfTheTask_isAlsoA409() throws Exception {
        long id = idOf(createTask("Start"));

        expectVersionConflict(putTask(id, "Changed", 5), id);
    }

    @Test
    void assign_bumpsTheVersion_soAPutWithTheOldVersionIsA409() throws Exception {
        long id = idOf(createTask("Start"));
        assign(id, createProject("Work"));

        // The task row changed (project_id), so a client still holding version 0 must be told.
        expectVersionConflict(putTask(id, "Changed", 0), id);
        putTask(id, "Changed", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    // ---------- two requests at once ----------

    @RepeatedTest(10)
    void put_twoConcurrentRequestsWithTheSameVersion_oneWinsOneConflicts() throws Exception {
        long id = idOf(createTask("Start"));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            // Different titles: a PUT that changes nothing writes nothing, so two identical ones would both pass.
            for (String title : List.of("Alpha", "Beta")) {
                futures.add(pool.submit(() -> {
                    go.await();
                    int httpStatus = putTask(id, title, 0).andReturn().getResponse().getStatus();
                    return new Outcome(httpStatus, title);
                }));
            }
            go.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            // Exactly one 200 and one 409, never a 500. Depending on timing the loser is stopped by the
            // comparison or by the flush. Both must give the same answer.
            assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);

            // No lost update: what is stored is exactly the winner's PUT.
            String winner = outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow().title();
            mockMvc.perform(get("/tasks/" + id))
                    .andExpect(jsonPath("$.title").value(winner))
                    .andExpect(jsonPath("$.version").value(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWriteBetweenLoadAndFlush_makesFlushThrowTheExceptionTheServiceCatches() throws Exception {
        // The race above cannot choose which check stops the loser. This one forces the flush path, so it
        // pins what TaskService.flushOrConflict depends on: on real H2, a flush after someone else's commit
        // throws ObjectOptimisticLockingFailureException (not a lock error), also through the repository proxy.
        long id = idOf(createTask("Start"));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                Task loaded = taskRepository.findById(id).orElseThrow(); // loaded at version 0

                // Another request commits a change to the same task while this transaction is open.
                try {
                    pool.submit(() -> taskService.update(id, new UpdateTaskRequest(
                            "Rival", null, LocalDate.parse("2026-12-01"), Priority.MEDIUM, Status.TODO, 0L))).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }

                loaded.update("Mine", null, LocalDate.parse("2026-12-01"), Priority.MEDIUM, Status.TODO);
                taskRepository.flush();
            })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

            // The rival's commit stands. Ours was rolled back.
            mockMvc.perform(get("/tasks/" + id))
                    .andExpect(jsonPath("$.title").value("Rival"))
                    .andExpect(jsonPath("$.version").value(1));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- delete never looks at the version ----------

    @Test
    void delete_succeedsWhateverTheVersionIs() throws Exception {
        long id = idOf(createTask("Start"));
        putTask(id, "Edited", 0).andExpect(status().isOk());
        putTask(id, "Edited again", 1).andExpect(status().isOk());

        // The task is at version 2. There is no version to send, and none is needed.
        mockMvc.perform(delete("/tasks/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/tasks/" + id)).andExpect(status().isNotFound());
    }

    @RepeatedTest(10)
    void delete_twoConcurrentDeletes_oneSucceedsAndTheOtherIsNotFound() throws Exception {
        long id = idOf(createTask("Start"));

        List<Integer> statuses = runAtTheSameTime(List.of(() -> deleteStatus(id), () -> deleteStatus(id)));

        // The second one finds nothing to delete. Deleting the loaded entity would have made it a 500.
        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
    }

    @RepeatedTest(10)
    void delete_racingAnUpdate_deleteAlwaysWins() throws Exception {
        long id = idOf(createTask("Start"));

        List<Integer> statuses = runAtTheSameTime(List.of(
                () -> deleteStatus(id),
                () -> putTask(id, "Edited", 0).andReturn().getResponse().getStatus()));

        assertThat(statuses.get(0)).isEqualTo(204);
        // The update either ran first (200), found the task already gone (404), or lost the race (409).
        assertThat(statuses.get(1)).isIn(200, 404, 409);
        mockMvc.perform(get("/tasks/" + id)).andExpect(status().isNotFound());
    }

    // ---------- assign and unassign under a race: 200 or 409, never a 500 ----------

    @RepeatedTest(10)
    void assign_twoConcurrentAssignsToDifferentProjects_neverAnError() throws Exception {
        long id = idOf(createTask("Start"));
        long work = createProject("Work");
        long home = createProject("Home");

        List<Integer> statuses = runAtTheSameTime(List.of(() -> assignStatus(id, work), () -> assignStatus(id, home)));

        // Not "last write wins": a request that loses the race is told so (409) and can retry.
        assertThat(statuses).allSatisfy(code -> assertThat(code).isIn(200, 409));
        assertThat(statuses).contains(200);
        long finalProject = ((Number) JsonPath.read(
                mockMvc.perform(get("/tasks/" + id)).andReturn().getResponse().getContentAsString(),
                "$.projectId")).longValue();
        assertThat(finalProject).isIn(work, home);
    }

    @RepeatedTest(10)
    void assign_racingAnUpdate_neverAnError() throws Exception {
        long id = idOf(createTask("Start"));
        long work = createProject("Work");

        List<Integer> statuses = runAtTheSameTime(List.of(
                () -> assignStatus(id, work),
                () -> putTask(id, "Edited", 0).andReturn().getResponse().getStatus()));

        assertThat(statuses).allSatisfy(code -> assertThat(code).isIn(200, 409));
    }
}
