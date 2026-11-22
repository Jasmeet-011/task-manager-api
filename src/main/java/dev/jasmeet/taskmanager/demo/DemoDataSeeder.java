package dev.jasmeet.taskmanager.demo;

import dev.jasmeet.taskmanager.project.CreateProjectRequest;
import dev.jasmeet.taskmanager.project.ProjectService;
import dev.jasmeet.taskmanager.task.CreateTaskRequest;
import dev.jasmeet.taskmanager.task.Priority;
import dev.jasmeet.taskmanager.task.Status;
import dev.jasmeet.taskmanager.task.TaskFilter;
import dev.jasmeet.taskmanager.task.TaskResponse;
import dev.jasmeet.taskmanager.task.TaskService;
import dev.jasmeet.taskmanager.task.UpdateTaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills an empty database with a few projects and tasks, so a reviewer who opens the page sees
 * something to filter and act on (ADR-015).
 *
 * <p>It only exists when app.seed-demo-data is true. Main application.properties sets it to true and
 * the test config sets it to false, so a missing property means no seed.
 *
 * <p>Everything goes through the services, so the priority rank and the versions are set by the same
 * code that runs for a real request. Due dates are relative to today, from the injected Clock, so the
 * data never goes stale. An exception here stops the startup on purpose: the data is fixed and
 * tested, so a failure is a bug, and an empty page would hide it.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final String WEBSITE = "Website Launch";
    static final String HOME = "Home Renovation";

    /** projectName is null for a task with no project. */
    private record SeedTask(String title, String description, int dueInDays, Priority priority,
                            Status status, String projectName) {
    }

    // Two tasks are due on the same day, so the priority tiebreak of the default order is visible.
    // One is overdue and not done, one is overdue and done, and one has no project.
    private static final List<SeedTask> TASKS = List.of(
            new SeedTask("Fix broken checkout link", "Customers get a 404 after the payment step.",
                    -3, Priority.HIGH, Status.TODO, WEBSITE),
            new SeedTask("Publish pricing page", null,
                    -1, Priority.HIGH, Status.DONE, WEBSITE),
            new SeedTask("Order kitchen tiles", "Grey, 30x60, enough for 12 square metres.",
                    2, Priority.HIGH, Status.TODO, HOME),
            new SeedTask("Write launch announcement", "Blog post and a short email to subscribers.",
                    2, Priority.MEDIUM, Status.IN_PROGRESS, WEBSITE),
            new SeedTask("Review analytics dashboard", null,
                    5, Priority.LOW, Status.TODO, WEBSITE),
            new SeedTask("Book electrician", "Get two quotes before choosing.",
                    7, Priority.MEDIUM, Status.IN_PROGRESS, HOME),
            new SeedTask("Paint the hallway", null,
                    14, Priority.LOW, Status.TODO, HOME),
            new SeedTask("Renew passport", "Photo and form first, then book the appointment.",
                    30, Priority.MEDIUM, Status.TODO, null));

    private static final List<CreateProjectRequest> PROJECTS = List.of(
            new CreateProjectRequest(WEBSITE, "Ship the new marketing site."),
            new CreateProjectRequest(HOME, "Kitchen and hallway jobs."));

    private final ProjectService projectService;
    private final TaskService taskService;
    private final Clock clock;

    public DemoDataSeeder(ProjectService projectService, TaskService taskService, Clock clock) {
        this.projectService = projectService;
        this.taskService = taskService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    /** Seeds only when there are no projects and no tasks. Returns true if it seeded. */
    public boolean seedIfEmpty() {
        if (!isEmpty()) {
            log.info("Demo data not loaded: the database already has projects or tasks.");
            return false;
        }
        LocalDate today = LocalDate.now(clock);

        Map<String, Long> projectIds = new HashMap<>();
        for (CreateProjectRequest project : PROJECTS) {
            projectIds.put(project.name(), projectService.create(project).id());
        }
        for (SeedTask seed : TASKS) {
            create(seed, today, projectIds);
        }
        log.info("Demo data loaded: {} projects and {} tasks. It disappears when the app stops.",
                PROJECTS.size(), TASKS.size());
        return true;
    }

    private boolean isEmpty() {
        // Page size 1 is enough: only totalElements is read.
        boolean noTasks = taskService.list(new TaskFilter(null, null, null, null, null), 0, 1, null)
                .totalElements() == 0;
        return noTasks && projectService.list().isEmpty();
    }

    private void create(SeedTask seed, LocalDate today, Map<String, Long> projectIds) {
        LocalDate dueDate = today.plusDays(seed.dueInDays());
        TaskResponse task = taskService.create(
                new CreateTaskRequest(seed.title(), seed.description(), dueDate, seed.priority()));
        if (seed.projectName() != null) {
            task = taskService.assignProject(task.id(), projectIds.get(seed.projectName()));
        }
        if (seed.status() != Status.TODO) {
            // New tasks are always TODO, so a different status needs a normal update. It must carry the
            // version of the response before it, which assigning a project has just raised.
            taskService.update(task.id(), new UpdateTaskRequest(
                    seed.title(), seed.description(), dueDate, seed.priority(), seed.status(), task.version()));
        }
    }
}
