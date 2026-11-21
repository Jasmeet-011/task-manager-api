package dev.jasmeet.taskmanager.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * The ai.anthropic.* properties. Nothing about the model or the endpoint is hardcoded.
 *
 * @param apiKey         from ANTHROPIC_API_KEY. Empty when not set, and the app still starts.
 * @param model          the model id sent with every request
 * @param baseUrl        the API root, without /v1/messages
 * @param connectTimeout how long to wait for the connection to open
 * @param readTimeout    how long to wait for the response once connected
 */
@ConfigurationProperties("ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    /** A blank key counts as no key, so a stray space in the env var still means 503 and not a 401. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * A record's default toString prints every field, which would put the key in any log line
     * that prints this object. Only whether a key is set is shown.
     */
    @Override
    public String toString() {
        return "AnthropicProperties[apiKey=" + (isConfigured() ? "(set)" : "(not set)")
                + ", model=" + model
                + ", baseUrl=" + baseUrl
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
