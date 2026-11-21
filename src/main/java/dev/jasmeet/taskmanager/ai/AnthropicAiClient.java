package dev.jasmeet.taskmanager.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * AiClient over the Anthropic Messages API, with plain RestClient and no SDK (ADR-004).
 *
 * <p>One request, no retries (ADR-013). How each outcome is classified:
 * <ul>
 *   <li>no key: AiNotConfiguredException, before any request is sent</li>
 *   <li>connect or read timeout: AiTimeoutException</li>
 *   <li>an error status, or the host cannot be reached: AiUpstreamException</li>
 *   <li>a 2xx we cannot use (not JSON, no tool call, two tool calls, cut off, refused): AiOutputInvalidException</li>
 * </ul>
 *
 * <p>The key is only ever put in the x-api-key header. It is removed from every exception message and
 * raw output built here, in case the upstream ever echoes it back.
 */
public class AnthropicAiClient implements AiClient {

    // The API version must match the request and response shapes below, so it is not a config value.
    // 2026-10-21 rejects temperature, top_p and top_k, and we send none of them.
    static final String API_VERSION = "2026-10-21";
    static final String MESSAGES_PATH = "/v1/messages";
    // Thinking counts toward max_tokens. The draft itself is about 100 tokens. Hitting the cap is
    // reported as invalid output (stop_reason max_tokens).
    static final int MAX_TOKENS = 4096;
    // Extracting four fields needs little reasoning, and a user is waiting on a button.
    static final String EFFORT = "low";
    private static final int ERROR_BODY_MAX = 500;

    private final RestClient restClient;
    private final AnthropicProperties properties;
    private final JsonMapper jsonMapper;

    /**
     * The builder comes in from outside, already carrying its request factory: the real one with
     * timeouts (see requestFactory) in the app, a MockRestServiceServer in tests.
     */
    public AnthropicAiClient(RestClient.Builder builder, AnthropicProperties properties, JsonMapper jsonMapper) {
        this.restClient = builder.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Both timeouts, set explicitly. The JDK HttpClient rather than HttpURLConnection, because
     * HttpURLConnection can fail on a 401 to a streamed POST instead of returning it.
     */
    public static ClientHttpRequestFactory requestFactory(AnthropicProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public JsonNode callTool(AiToolCall call) {
        if (!properties.isConfigured()) {
            throw new AiNotConfiguredException();
        }
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(MESSAGES_PATH)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", API_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MessagesRequest.of(properties.model(), call))
                    .retrieve()
                    // Read as text, not straight into a type, so a body that is not JSON can still be logged.
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            throw upstreamError(ex);
        } catch (ResourceAccessException ex) {
            // Any I/O failure: a timeout, a refused connection, an unknown host.
            if (isTimeout(ex)) {
                throw new AiTimeoutException(scrub("No response from the AI service in time: " + ex.getMessage()), ex);
            }
            throw new AiUpstreamException(scrub("Could not reach the AI service: " + ex.getMessage()), false, ex);
        }
        return toolInput(responseBody, call.toolName());
    }

    // ---------- reading a 2xx response ----------

    /** Returns the arguments of the single call to toolName, or throws AiOutputInvalidException. */
    private JsonNode toolInput(String body, String toolName) {
        if (body == null || body.isBlank()) {
            throw invalid("The response body is empty", body);
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw invalid("The response body is not JSON", body);
        }
        // tool_use is the only stop reason that means "stopped to call the tool". end_turn means it answered
        // in text, max_tokens means it was cut off, refusal means it declined.
        String stopReason = root.path("stop_reason").asString("(missing)");
        if (!"tool_use".equals(stopReason)) {
            throw invalid("Expected stop_reason tool_use but got " + stopReason, forLog(root));
        }
        List<JsonNode> toolCalls = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asString(""))) {
                toolCalls.add(block);
            }
        }
        if (toolCalls.size() != 1) {
            throw invalid("Expected exactly one tool call but got " + toolCalls.size(), forLog(root));
        }
        JsonNode toolCall = toolCalls.get(0);
        if (!toolName.equals(toolCall.path("name").asString(""))) {
            throw invalid("Expected a call to " + toolName + " but got " + toolCall.path("name"), forLog(root));
        }
        JsonNode input = toolCall.get("input");
        if (input == null) {
            throw invalid("The tool call has no input", forLog(root));
        }
        return input;
    }

    /**
     * The response for the log, without thinking blocks. Their signatures are long opaque strings
     * that would fill the truncated log line before the part that matters.
     */
    private String forLog(JsonNode root) {
        if (!(root instanceof ObjectNode object) || !object.path("content").isArray()) {
            return root.toString();
        }
        ArrayNode kept = jsonMapper.createArrayNode();
        for (JsonNode block : object.path("content")) {
            String type = block.path("type").asString("");
            if (!type.equals("thinking") && !type.equals("redacted_thinking")) {
                kept.add(block);
            }
        }
        ObjectNode copy = object.deepCopy();
        copy.set("content", kept);
        return copy.toString();
    }

    private AiOutputInvalidException invalid(String reason, String rawOutput) {
        return new AiOutputInvalidException(reason, scrub(rawOutput));
    }

    // ---------- errors ----------

    /**
     * 400, 401, 403 and 404 mean something on our side is wrong and retrying will not help, so they
     * are flagged for the operator. 429, 5xx and 529 (overloaded) are likely to pass on their own.
     */
    private AiUpstreamException upstreamError(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        boolean operatorMustAct = status == 400 || status == 401 || status == 403 || status == 404;
        String hint = switch (status) {
            case 401, 403 -> " The API key was rejected: check ANTHROPIC_API_KEY.";
            case 404 -> " Check ai.anthropic.model and ai.anthropic.base-url.";
            case 400 -> " The API refused the request.";
            default -> "";
        };
        HttpHeaders headers = ex.getResponseHeaders();
        String requestId = headers == null ? null : headers.getFirst("request-id");
        String message = "Anthropic returned HTTP " + status + "." + hint
                + " request-id=" + requestId + ", body: " + truncate(ex.getResponseBodyAsString());
        return new AiUpstreamException(scrub(message), operatorMustAct, ex);
    }

    /**
     * Walks the cause chain: Spring wraps the I/O error in ResourceAccessException, and may wrap it
     * again in a plain IOException. Spring's read timeout throws HttpTimeoutException, and so does the
     * JDK's connect timeout (HttpConnectTimeoutException is a subclass).
     */
    private static boolean isTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private String scrub(String text) {
        if (text == null || !properties.isConfigured()) {
            return text;
        }
        return text.replace(properties.apiKey(), "[redacted]");
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= ERROR_BODY_MAX) {
            return text;
        }
        return text.substring(0, ERROR_BODY_MAX) + "...";
    }

    // ---------- request body (only the fields we use) ----------

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("output_config") OutputConfig outputConfig,
            String system,
            List<Message> messages,
            List<Tool> tools,
            @JsonProperty("tool_choice") ToolChoice toolChoice) {

        static MessagesRequest of(String model, AiToolCall call) {
            return new MessagesRequest(
                    model,
                    MAX_TOKENS,
                    new OutputConfig(EFFORT),
                    call.systemPrompt(),
                    List.of(new Message("user", call.userText())),
                    // strict: when the model calls the tool, its arguments always match the schema.
                    List.of(new Tool(call.toolName(), call.toolDescription(), true, call.inputSchema())),
                    // auto, not forced: forcing a tool returns 400 on several current models (ADR-013).
                    // The prompt says to call it, and no call is reported as invalid output.
                    new ToolChoice("auto", true));
        }
    }

    record OutputConfig(String effort) {
    }

    record Message(String role, String content) {
    }

    record Tool(
            String name,
            String description,
            boolean strict,
            @JsonProperty("input_schema") JsonNode inputSchema) {
    }

    record ToolChoice(
            String type,
            @JsonProperty("disable_parallel_tool_use") boolean disableParallelToolUse) {
    }
}
