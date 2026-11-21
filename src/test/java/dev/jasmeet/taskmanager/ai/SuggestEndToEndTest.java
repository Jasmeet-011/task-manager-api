package dev.jasmeet.taskmanager.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path with nothing mocked: controller, service, the real AnthropicAiClient and the real
 * request factory with its timeouts. The "Anthropic API" is a JDK HttpServer bound to 127.0.0.1, so
 * nothing leaves the machine and the test runs offline. The read timeout is cut to 300ms so the
 * timeout test is quick.
 */
@SpringBootTest(properties = {
        "ai.anthropic.api-key=" + SuggestEndToEndTest.KEY,
        "ai.anthropic.read-timeout=300ms"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SuggestEndToEndTest {

    static final String KEY = "sk-ant-test-DO-NOT-LEAK";

    // Started once, before the Spring context, because the context needs its port.
    private static final HttpServer STUB = startStub();

    // What the stub answers next, and what it last received. Set per test.
    private static volatile int responseStatus;
    private static volatile String responseBody;
    private static volatile long responseDelayMillis;
    private static final AtomicInteger requestCount = new AtomicInteger();
    private static volatile String receivedApiKey;
    private static volatile String receivedBody;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void pointTheClientAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("ai.anthropic.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @BeforeEach
    void resetStub() {
        stubAnswers(200, "{}", 0);
        requestCount.set(0);
        receivedApiKey = null;
        receivedBody = null;
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    // ---------- the stub ----------

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", SuggestEndToEndTest::handle);
            // One thread per request, so a deliberately slow answer does not hold up the next test.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void handle(HttpExchange exchange) {
        try {
            requestCount.incrementAndGet();
            receivedApiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (responseDelayMillis > 0) {
                Thread.sleep(responseDelayMillis);
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("request-id", "req_stub");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ex) {
            // The client gave up (the timeout test). Nothing to answer.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void stubAnswers(int status, String body, long delayMillis) {
        responseStatus = status;
        responseBody = body;
        responseDelayMillis = delayMillis;
    }

    /** A Messages API response whose single tool call has the given input. */
    private static String toolCallResponse(String input) {
        return """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-haiku-5-5",
                 "content": [
                   {"type": "thinking", "thinking": "", "signature": "SIGNATURE-abc123"},
                   {"type": "tool_use", "id": "toolu_1", "name": "create_task_draft", "input": %s}],
                 "stop_reason": "tool_use", "usage": {"input_tokens": 700, "output_tokens": 60}}"""
                .formatted(input);
    }

    private ResultActions suggest(String text) throws Exception {
        return mockMvc.perform(post("/tasks/suggest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"" + text + "\"}"));
    }

    // ---------- tests ----------

    @Test
    void validToolCall_returnsTheDraft() throws Exception {
        stubAnswers(200, toolCallResponse("""
                {"title": "Pay rent", "description": null, "dueDate": "2026-11-27", "priority": null}"""), 0);

        suggest("pay rent by Friday")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"title": "Pay rent", "description": null, "dueDate": "2026-11-27", "priority": null}""",
                        JsonCompareMode.STRICT));

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(receivedApiKey).isEqualTo(KEY);
        assertThat(receivedBody)
                .contains("\"model\":\"claude-haiku-5-5\"")
                .contains("\"content\":\"pay rent by Friday\"");
    }

    /** The spec's malformed-output test: the AI service answers 200 with a body that is not JSON. */
    @Test
    void malformedModelOutput_is502OutputInvalid_andTheRawOutputIsLogged(CapturedOutput output) throws Exception {
        stubAnswers(200, "Sure! Here is your task: pay rent", 0);

        suggest("pay rent")
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AI_OUTPUT_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("Here is your task"))));

        assertThat(output.getAll())
                .contains("AI output rejected: The response body is not JSON")
                .contains("Sure! Here is your task: pay rent");
    }

    @Test
    void toolCallWithInvalidContent_is502OutputInvalid(CapturedOutput output) throws Exception {
        stubAnswers(200, toolCallResponse("""
                {"title": "Pay rent", "description": null, "dueDate": "2026-02-30", "priority": null}"""), 0);

        suggest("pay rent")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_OUTPUT_INVALID"));

        assertThat(output.getAll()).contains("dueDate is not a real date in yyyy-MM-dd format: 2026-02-30");
    }

    @Test
    void slowAnswer_is504Timeout_afterTheConfiguredReadTimeout() throws Exception {
        stubAnswers(200, toolCallResponse("{\"title\": \"Too late\"}"), 2000);

        long start = System.nanoTime();
        suggest("pay rent")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_TIMEOUT"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 300ms read timeout, not the 2s the stub waits.
        assertThat(elapsedMillis).isLessThan(1500);
    }

    @Test
    void rejectedKey_is502_andTheKeyAppearsNeitherInTheResponseNorInTheLog(CapturedOutput output) throws Exception {
        stubAnswers(401, """
                {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key %s"}}"""
                .formatted(KEY), 0);

        suggest("pay rent")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"))
                .andExpect(content().string(not(containsString(KEY))));

        assertThat(output.getAll())
                .contains("AI service error that needs a configuration fix")
                .contains("check ANTHROPIC_API_KEY")
                .contains("req_stub")
                .doesNotContain(KEY);
    }
}
