package dev.jasmeet.taskmanager.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The real AnthropicAiClient against MockRestServiceServer, which replaces the HTTP layer, so no
 * request leaves the JVM. The real timeouts are tested separately, in SuggestEndToEndTest, because the
 * mock server also replaces the request factory that holds them.
 */
class AnthropicAiClientTest {

    private static final String KEY = "sk-ant-test-DO-NOT-LEAK";
    private static final String BASE_URL = "https://anthropic.test";
    private static final String TOOL = "create_task_draft";

    private static final String THINKING = """
            {"type": "thinking", "thinking": "", "signature": "SIGNATURE-abc123"}""";
    private static final String TOOL_USE = """
            {"type": "tool_use", "id": "toolu_1", "name": "create_task_draft", "input": {"title": "Pay rent"}}""";
    private static final String TEXT = """
            {"type": "text", "text": "I need more detail about this task."}""";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private MockRestServiceServer server;

    // ---------- helpers ----------

    /** A client whose requests go to a fresh mock server, saved in the server field. */
    private AnthropicAiClient clientWithKey(String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AnthropicProperties properties = new AnthropicProperties(key, "claude-haiku-5-5",
                URI.create(BASE_URL), Duration.ofSeconds(5), Duration.ofSeconds(20));
        return new AnthropicAiClient(builder, properties, jsonMapper);
    }

    private AiToolCall call() {
        JsonNode schema = jsonMapper.readTree("""
                {"type": "object", "properties": {"title": {"type": "string"}},
                 "required": ["title"], "additionalProperties": false}""");
        return new AiToolCall("Turn the note into a task.", "pay rent by Friday", TOOL, "Records the draft.", schema);
    }

    /** A Messages API response with the given stop reason (null leaves it out) and content blocks. */
    private static String response(String stopReason, String... blocks) {
        String stop = stopReason == null ? "" : "\"stop_reason\": \"" + stopReason + "\",";
        return """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-haiku-5-5", %s
                 "content": [%s], "usage": {"input_tokens": 10, "output_tokens": 5}}"""
                .formatted(stop, String.join(",", blocks));
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    // ---------- the request ----------

    @Test
    void sendsTheExpectedRequest() {
        AnthropicAiClient client = clientWithKey(KEY);
        server.expect(requestTo(BASE_URL + "/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", KEY))
                .andExpect(header("anthropic-version", "2026-10-21"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.max_tokens").value(4096))
                .andExpect(jsonPath("$.output_config.effort").value("low"))
                .andExpect(jsonPath("$.system").value("Turn the note into a task."))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("pay rent by Friday"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value(TOOL))
                .andExpect(jsonPath("$.tools[0].description").value("Records the draft."))
                .andExpect(jsonPath("$.tools[0].strict").value(true))
                .andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
                .andExpect(jsonPath("$.tools[0].input_schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.tool_choice.type").value("auto"))
                .andExpect(jsonPath("$.tool_choice.disable_parallel_tool_use").value(true))
                // API version 2026-10-21 rejects sampling parameters, so none may be sent.
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(response("tool_use", TOOL_USE), MediaType.APPLICATION_JSON));

        client.callTool(call());

        server.verify();
    }

    // ---------- a usable response ----------

    @Test
    void returnsTheToolInput_skippingTheThinkingBlock() {
        AnthropicAiClient client = clientWithKey(KEY);
        respondWith(response("tool_use", THINKING, TOOL_USE));

        JsonNode input = client.callTool(call());

        assertThat(input.path("title").stringValue()).isEqualTo("Pay rent");
    }

    @Test
    void returnsTheToolInput_whenTextComesBeforeTheToolCall() {
        AnthropicAiClient client = clientWithKey(KEY);
        respondWith(response("tool_use", TEXT, TOOL_USE));

        assertThat(client.callTool(call()).path("title").stringValue()).isEqualTo("Pay rent");
    }

    // ---------- no key ----------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void noKey_throwsNotConfigured_andSendsNoRequest(String key) {
        AnthropicAiClient client = clientWithKey(key);
        // No expectations: any request would fail the test.

        assertThrows(AiNotConfiguredException.class, () -> client.callTool(call()));

        server.verify();
    }

    // ---------- error statuses ----------

    @ParameterizedTest
    @CsvSource({"400, true", "401, true", "403, true", "404, true", "429, false", "500, false", "529, false"})
    void errorStatus_throwsUpstream_flaggedWhenTheOperatorMustAct(int status, boolean operatorMustAct) {
        AnthropicAiClient client = clientWithKey(KEY);
        server.expect(requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("request-id", "req_42")
                        .body("""
                                {"type": "error", "error": {"type": "some_error", "message": "it failed"}}"""));

        AiUpstreamException ex = assertThrows(AiUpstreamException.class, () -> client.callTool(call()));

        assertThat(ex.isOperatorMustAct()).isEqualTo(operatorMustAct);
        assertThat(ex.getMessage()).contains("HTTP " + status).contains("req_42").contains("it failed");
    }

    @Test
    void rejectedKey_saysWhatToFix_andTheKeyIsRemovedEvenIfEchoed() {
        AnthropicAiClient client = clientWithKey(KEY);
        server.expect(requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(401))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type": "error", "error": {"type": "authentication_error",
                                 "message": "invalid x-api-key %s"}}""".formatted(KEY)));

        AiUpstreamException ex = assertThrows(AiUpstreamException.class, () -> client.callTool(call()));

        assertThat(ex.getMessage())
                .contains("check ANTHROPIC_API_KEY")
                .contains("[redacted]")
                .doesNotContain(KEY);
    }

    // ---------- I/O failures ----------

    static Stream<IOException> timeouts() {
        return Stream.of(
                new HttpTimeoutException("request timed out"),               // Spring's read timeout
                new HttpConnectTimeoutException("HTTP connect timed out"),   // the JDK's connect timeout
                new SocketTimeoutException("Read timed out"),
                new IOException("wrapped", new HttpTimeoutException("request timed out")));
    }

    @ParameterizedTest
    @MethodSource("timeouts")
    void timeout_throwsAiTimeout(IOException failure) {
        AnthropicAiClient client = clientWithKey(KEY);
        server.expect(requestTo(BASE_URL + "/v1/messages")).andRespond(withException(failure));

        assertThrows(AiTimeoutException.class, () -> client.callTool(call()));
    }

    static Stream<IOException> unreachable() {
        return Stream.of(new ConnectException("Connection refused"), new UnknownHostException("anthropic.test"));
    }

    @ParameterizedTest
    @MethodSource("unreachable")
    void unreachableHost_throwsUpstream_notFlaggedForTheOperator(IOException failure) {
        AnthropicAiClient client = clientWithKey(KEY);
        server.expect(requestTo(BASE_URL + "/v1/messages")).andRespond(withException(failure));

        AiUpstreamException ex = assertThrows(AiUpstreamException.class, () -> client.callTool(call()));

        assertThat(ex.isOperatorMustAct()).isFalse();
        assertThat(ex.getMessage()).startsWith("Could not reach the AI service");
    }

    // ---------- a 2xx that cannot be used ----------

    static Stream<Arguments> unusableResponses() {
        String otherTool = TOOL_USE.replace("create_task_draft", "delete_everything");
        String noInput = """
                {"type": "tool_use", "id": "toolu_1", "name": "create_task_draft"}""";
        return Stream.of(
                Arguments.of("not JSON", "<html>502 Bad Gateway</html>", "not JSON"),
                Arguments.of("empty body", "", "empty"),
                Arguments.of("answered in text", response("end_turn", THINKING, TEXT), "got end_turn"),
                Arguments.of("cut off", response("max_tokens", THINKING), "got max_tokens"),
                Arguments.of("refused", response("refusal"), "got refusal"),
                Arguments.of("no stop reason", response(null, TOOL_USE), "got (missing)"),
                Arguments.of("tool_use stop but no call", response("tool_use", TEXT), "exactly one tool call but got 0"),
                Arguments.of("two calls", response("tool_use", TOOL_USE, TOOL_USE), "exactly one tool call but got 2"),
                Arguments.of("wrong tool", response("tool_use", otherTool), "Expected a call to create_task_draft"),
                Arguments.of("call without input", response("tool_use", noInput), "no input"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableResponses")
    void unusableResponse_throwsOutputInvalid(String name, String body, String reason) {
        AnthropicAiClient client = clientWithKey(KEY);
        respondWith(body);

        AiOutputInvalidException ex = assertThrows(AiOutputInvalidException.class, () -> client.callTool(call()));

        assertThat(ex.getMessage()).contains(reason);
    }

    @Test
    void notJson_keepsTheBodyAsTheRawOutput() {
        AnthropicAiClient client = clientWithKey(KEY);
        respondWith("<html>502 Bad Gateway</html>");

        AiOutputInvalidException ex = assertThrows(AiOutputInvalidException.class, () -> client.callTool(call()));

        assertThat(ex.getRawOutput()).isEqualTo("<html>502 Bad Gateway</html>");
    }

    @Test
    void rawOutput_leavesOutThinkingBlocks_butKeepsWhatTheModelSaid() {
        AnthropicAiClient client = clientWithKey(KEY);
        respondWith(response("end_turn", THINKING, TEXT));

        AiOutputInvalidException ex = assertThrows(AiOutputInvalidException.class, () -> client.callTool(call()));

        assertThat(ex.getRawOutput())
                .contains("I need more detail about this task.")
                .contains("end_turn")
                .doesNotContain("SIGNATURE-abc123");
    }
}
