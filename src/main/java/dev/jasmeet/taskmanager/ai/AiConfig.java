package dev.jasmeet.taskmanager.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Wiring for the AI feature: binds the ai.anthropic.* properties and builds the client. */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AiConfig {

    /**
     * The bean's type is the interface, so tests can replace it with @MockitoBean AiClient.
     * It is built even with no key: the key is checked on each call, so the app always starts.
     */
    @Bean
    public AiClient aiClient(AnthropicProperties properties, JsonMapper jsonMapper) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(AnthropicAiClient.requestFactory(properties));
        return new AnthropicAiClient(builder, properties, jsonMapper);
    }
}
