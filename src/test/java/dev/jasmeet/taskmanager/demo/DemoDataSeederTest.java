package dev.jasmeet.taskmanager.demo;

import dev.jasmeet.taskmanager.common.PageResponse;
import dev.jasmeet.taskmanager.project.CreateProjectRequest;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import dev.jasmeet.taskmanager.project.ProjectService;
import dev.jasmeet.taskmanager.task.CreateTaskRequest;
import dev.jasmeet.taskmanager.task.Priority;
import dev.jasmeet.taskmanager.task.Status;
import dev.jasmeet.taskmanager.task.TaskFilter;
import dev.jasmeet.taskmanager.task.TaskRepository;
import dev.jasmeet.taskmanager.task.TaskResponse;
import dev.jasmeet.taskmanager.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeder's data and rules, with the real services and H2 and a fixed clock. The seed bean is off
 * here and the seeder is built by hand, so each test controls when and with which date it runs.
 *
 * <p>This context has its own database URL. The default test database is shared by every cached
 * context, and rows left there would leak into other test classes (ADR-015).
 */
@SpringBootTest(properties = {
        "app.seed-demo-data=false",
        "spring.datasource.url=jdbc:h2:mem:seedertest;DB_CLOSE_DELAY=-1"})
class DemoDataSeederTest {

    // A Saturday, the day the milestone was written. Any date works, the offsets are what is tested.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
    private static final TaskFilter ALL = new TaskFilter(null, null, null, null, null);

    @Autowired
    ProjectService projectService;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    ProjectRepository projectRepository;

    DemoDataSeeder seeder;

    @BeforeEach
    void emptyDatabaseAndBuildSeeder() {
        // Tasks first: they hold the foreign key to projects.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        seeder = new DemoDataSeeder(projectService, taskService, CLOCK);
    }

    private List<TaskResponse> allTasks(String... sort) {
        PageResponse<TaskResponse> page = taskService.list(ALL, 0, 100, List.of(sort));
        return page.content();
    }

    private TaskResponse task(String title) {
        return allTasks().stream().filter(task -> task.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void anEmptyDatabase_getsTwoProjectsAndEightTasks() {
        assertThat(seeder.seedIfEmpty()).isTrue();

        assertThat(projectService.list()).extracting("name")
                .containsExactly("Website Launch", "Home Renovation");
        assertThat(allTasks()).hasSize(8);
    }

    @Test
    void dueDatesAreRelativeToTheClock() {
        seeder.seedIfEmpty();

        assertThat(task("Fix broken checkout link").dueDate()).isEqualTo(TODAY.minusDays(3));
        assertThat(task("Publish pricing page").dueDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(task("Order kitchen tiles").dueDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(task("Renew passport").dueDate()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void thereIsAnOverdueTaskThatIsNotDone_andStatusesAndPrioritiesAreMixed() {
        seeder.seedIfEmpty();
        List<TaskResponse> tasks = allTasks();

        assertThat(tasks).anyMatch(task -> task.dueDate().isBefore(TODAY) && task.status() != Status.DONE);
        assertThat(tasks).extracting(TaskResponse::status).contains(Status.TODO, Status.IN_PROGRESS, Status.DONE);
        assertThat(tasks).extracting(TaskResponse::priority).contains(Priority.LOW, Priority.MEDIUM, Priority.HIGH);
    }

    @Test
    void tasksAreInTheirProjects_andOneHasNone() {
        seeder.seedIfEmpty();

        assertThat(task("Fix broken checkout link").projectName()).isEqualTo("Website Launch");
        assertThat(task("Book electrician").projectName()).isEqualTo("Home Renovation");
        assertThat(task("Renew passport").projectId()).isNull();
        assertThat(allTasks()).filteredOn(task -> task.projectId() != null).hasSize(7);
    }

    @Test
    void theVersionsFollowTheServiceCalls() {
        seeder.seedIfEmpty();

        // create 0, assign project 1, update status 2: the seeder used each response's version.
        assertThat(task("Publish pricing page").version()).isEqualTo(2);
        assertThat(task("Write launch announcement").status()).isEqualTo(Status.IN_PROGRESS);
        // create 0, assign project 1.
        assertThat(task("Fix broken checkout link").version()).isEqualTo(1);
        // create only.
        assertThat(task("Renew passport").version()).isEqualTo(0);
    }

    @Test
    void theDefaultOrderIsDueDateThenPriority_soTheTwoTasksDueOnTheSameDayAreHighFirst() {
        seeder.seedIfEmpty();

        assertThat(allTasks()).extracting(TaskResponse::title).containsExactly(
                "Fix broken checkout link",
                "Publish pricing page",
                "Order kitchen tiles",
                "Write launch announcement",
                "Review analytics dashboard",
                "Book electrician",
                "Paint the hallway",
                "Renew passport");
    }

    @Test
    void sortingByPriorityPutsHighFirst_whichProvesTheRankColumnWasSet() {
        seeder.seedIfEmpty();

        assertThat(allTasks("priority,desc")).extracting(TaskResponse::priority)
                .containsExactly(Priority.HIGH, Priority.HIGH, Priority.HIGH,
                        Priority.MEDIUM, Priority.MEDIUM, Priority.MEDIUM,
                        Priority.LOW, Priority.LOW);
    }

    @Test
    void runningItAgain_addsNothing() {
        seeder.seedIfEmpty();

        assertThat(seeder.seedIfEmpty()).isFalse();

        assertThat(projectService.list()).hasSize(2);
        assertThat(allTasks()).hasSize(8);
    }

    @Test
    void aDatabaseWithOnlyAProject_isNotSeeded() {
        projectService.create(new CreateProjectRequest("Existing", null));

        assertThat(seeder.seedIfEmpty()).isFalse();

        assertThat(projectService.list()).hasSize(1);
        assertThat(allTasks()).isEmpty();
    }

    @Test
    void aDatabaseWithOnlyATask_isNotSeeded() {
        taskService.create(new CreateTaskRequest("Existing", null, TODAY, Priority.LOW));

        assertThat(seeder.seedIfEmpty()).isFalse();

        assertThat(projectService.list()).isEmpty();
        assertThat(allTasks()).hasSize(1);
    }
}
