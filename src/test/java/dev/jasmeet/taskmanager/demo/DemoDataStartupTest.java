package dev.jasmeet.taskmanager.demo;

import dev.jasmeet.taskmanager.project.ProjectService;
import dev.jasmeet.taskmanager.task.Status;
import dev.jasmeet.taskmanager.task.TaskFilter;
import dev.jasmeet.taskmanager.task.TaskResponse;
import dev.jasmeet.taskmanager.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With app.seed-demo-data=true the app seeds itself at startup. This is what a reviewer gets from
 * ./mvnw spring-boot:run. The test-level property wins over the test config file, which sets false.
 *
 * <p>Own database URL, for the same reason as in DemoDataSeederTest: the seed rows must not reach the
 * database the other test classes share.
 */
@SpringBootTest(properties = {
        "app.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:seedstartup;DB_CLOSE_DELAY=-1"})
class DemoDataStartupTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ProjectService projectService;

    @Autowired
    TaskService taskService;

    @Autowired
    Clock clock;

    @Test
    void theSeederBeanExists_andTheDatabaseWasFilledAtStartup() {
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).hasSize(1);

        List<TaskResponse> tasks =
                taskService.list(new TaskFilter(null, null, null, null, null), 0, 100, null).content();

        assertThat(projectService.list()).hasSize(2);
        assertThat(tasks).hasSize(8);
        assertThat(tasks).anyMatch(
                task -> task.dueDate().isBefore(LocalDate.now(clock)) && task.status() != Status.DONE);
    }
}
