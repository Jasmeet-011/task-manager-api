package dev.jasmeet.taskmanager.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key is set to empty explicitly, so this test behaves the same whether or not
 * ANTHROPIC_API_KEY is set in the shell that runs it.
 */
@SpringBootTest(properties = "ai.anthropic.api-key=")
class AnthropicPropertiesTest {

    private static final String SECRET = "sk-ant-test-DO-NOT-LEAK";

    @Autowired
    AnthropicProperties properties;

    // ---------- binding from application.properties ----------

    @Test
    void bindsTheConfiguredDefaults() {
        assertThat(properties.model()).isEqualTo("claude-haiku-5-5");
        assertThat(properties.baseUrl()).isEqualTo(URI.create("https://api.anthropic.com"));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void appStartsWithNoKey_andReportsNotConfigured() {
        assertThat(properties.isConfigured()).isFalse();
    }

    // ---------- isConfigured ----------

    @Test
    void nullEmptyOrBlankKey_isNotConfigured() {
        assertThat(withKey(null).isConfigured()).isFalse();
        assertThat(withKey("").isConfigured()).isFalse();
        assertThat(withKey("   ").isConfigured()).isFalse();
    }

    @Test
    void realKey_isConfigured() {
        assertThat(withKey(SECRET).isConfigured()).isTrue();
    }

    // ---------- the key never shows up in toString ----------

    @Test
    void toString_hidesTheKey() {
        String text = withKey(SECRET).toString();
        assertThat(text).doesNotContain(SECRET).contains("apiKey=(set)").contains("model=claude-haiku-5-5");
    }

    @Test
    void toString_saysWhenNoKeyIsSet() {
        assertThat(withKey("").toString()).contains("apiKey=(not set)");
    }

    private static AnthropicProperties withKey(String key) {
        return new AnthropicProperties(key, "claude-haiku-5-5", URI.create("https://api.anthropic.com"),
                Duration.ofSeconds(5), Duration.ofSeconds(20));
    }
}
