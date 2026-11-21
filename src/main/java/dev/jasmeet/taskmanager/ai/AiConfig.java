package dev.jasmeet.taskmanager.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring for the AI feature. Binds the ai.anthropic.* properties to AnthropicProperties. */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AiConfig {
}
