package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.PageResponse;
import dev.jasmeet.taskmanager.project.CreateProjectRequest;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import dev.jasmeet.taskmanager.project.ProjectService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The N+1 regression test (M3 measured it, M4 fixes it). It counts the SQL statements Hibernate
 * prepares for one list call and checks the count does not grow with the number of distinct projects.
 * Hibernate statistics are switched on for this test class only.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TaskListQueryCountTest {

    private static final TaskFilter NO_FILTER = new TaskFilter(null, null, null, null, null);

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    ProjectService projectService;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private void createTasksInProjects(int projectCount, int tasksPerProject) {
        for (int p = 1; p <= projectCount; p++) {
            long projectId = projectService.create(new CreateProjectRequest("Project " + p, null)).id();
            for (int t = 1; t <= tasksPerProject; t++) {
                Long taskId = taskService.create(new CreateTaskRequest(
                        "task " + p + "." + t, null, LocalDate.of(2026, 12, 1), Priority.MEDIUM)).id();
                taskService.assignProject(taskId, projectId);
            }
        }
    }

    /** Runs one list call and returns how many SQL statements it prepared. */
    private long statementsForOneList(PageResponse<TaskResponse>[] resultHolder) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        resultHolder[0] = taskService.list(NO_FILTER, 0, 20, List.of());
        return statistics.getPrepareStatementCount();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingStatementCount_doesNotGrowWithTheNumberOfDistinctProjects() {
        PageResponse<TaskResponse>[] result = new PageResponse[1];

        createTasksInProjects(1, 6);
        long withOneProject = statementsForOneList(result);
        assertThat(result[0].content()).hasSize(6).allSatisfy(task -> assertThat(task.projectName()).isNotNull());

        taskRepository.deleteAll();
        projectRepository.deleteAll();

        createTasksInProjects(6, 1);
        long withSixProjects = statementsForOneList(result);
        assertThat(result[0].content()).hasSize(6).allSatisfy(task -> assertThat(task.projectName()).isNotNull());

        // Without the fetch, six distinct projects cost five more SELECTs than one project.
        assertThat(withSixProjects).isEqualTo(withOneProject);
        // The page is not full, so there is no count query: one SELECT with the project joined in.
        assertThat(withSixProjects).isEqualTo(1);
    }

    @Test
    void aFullPage_costsOneSelectPlusOneCount_whateverTheProjects() {
        createTasksInProjects(3, 2);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        PageResponse<TaskResponse> page = taskService.list(NO_FILTER, 0, 3, List.of());

        assertThat(page.content()).hasSize(3);
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
