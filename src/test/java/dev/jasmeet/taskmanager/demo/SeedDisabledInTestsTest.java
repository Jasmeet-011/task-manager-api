package dev.jasmeet.taskmanager.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed must be off in every normal test, and the test config must ADD to the main
 * application.properties, not replace it (ADR-015). This uses the default test context, the one
 * most tests share.
 */
@SpringBootTest
class SeedDisabledInTestsTest {

    @Autowired
    Environment environment;

    @Autowired
    ApplicationContext context;

    @Test
    void theSeedIsOff_andThereIsNoSeederBean() {
        assertThat(environment.getProperty("app.seed-demo-data"))
                .as("src/test/resources/config/application.properties must set app.seed-demo-data=false")
                .isEqualTo("false");
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
    }

    @Test
    void theMainConfigIsStillInEffect_soTheTestFileAddedAndDidNotReplace() {
        // One setting from each part of the main file. If a test file shadowed it, these would be gone.
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:taskmanager");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jackson.datatype.enum.fail-on-numbers-for-enums"))
                .isEqualTo("true");
        assertThat(environment.getProperty("ai.anthropic.model")).isEqualTo("claude-haiku-5-5");
    }
}
