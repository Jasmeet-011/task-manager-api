package dev.jasmeet.taskmanager.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one source of "now". Code that needs today's date takes a Clock instead of calling
 * LocalDate.now(), so tests can pin the date with Clock.fixed(...).
 *
 * <p>The server's time zone decides what "today" is. That is right for an app run on the user's
 * own machine. A shared server would need the user's zone instead.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
