package dev.jasmeet.taskmanager.ai;

import dev.jasmeet.taskmanager.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /tasks/suggest through the real Spring context, with the AiClient mocked. The real
 * TaskSuggestionService runs, so its validation is exercised through HTTP too. The key is set to empty
 * explicitly, so no test here can reach the real API even if ANTHROPIC_API_KEY is set in the shell.
 */
@SpringBootTest(properties = "ai.anthropic.api-key=")
@AutoConfigureMockMvc
class TaskSuggestionApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    AiClient aiClient;

    private void modelReturns(String json) {
        when(aiClient.callTool(any())).thenReturn(jsonMapper.readTree(json));
    }

    private ResultActions suggest(String body) throws Exception {
        return mockMvc.perform(post("/tasks/suggest").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- 200 ----------

    @Test
    void returnsTheDraft_withExactlyTheFourFields_andSavesNothing() throws Exception {
        long tasksBefore = taskRepository.count();
        modelReturns("""
                {"title": "Pay rent", "description": "Transfer to the landlord.",
                 "dueDate": "2026-11-27", "priority": "HIGH"}""");

        suggest("""
                {"text": "pay rent by Friday, urgent"}""")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // STRICT: no other field, so no "status".
                .andExpect(content().json("""
                        {"title": "Pay rent", "description": "Transfer to the landlord.",
                         "dueDate": "2026-11-27", "priority": "HIGH"}""", JsonCompareMode.STRICT));

        assertThat(taskRepository.count()).isEqualTo(tasksBefore);
    }

    @Test
    void fieldsTheNoteDidNotGive_areReturnedAsNull() throws Exception {
        modelReturns("""
                {"title": "Call mum", "description": null, "dueDate": null, "priority": null}""");

        suggest("""
                {"text": "call mum"}""")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"title": "Call mum", "description": null, "dueDate": null, "priority": null}""",
                        JsonCompareMode.STRICT));
    }

    @Test
    void textOfExactly500Characters_isAccepted() throws Exception {
        modelReturns("""
                {"title": "Long note", "description": null, "dueDate": null, "priority": null}""");

        suggest("{\"text\": \"" + "x".repeat(500) + "\"}").andExpect(status().isOk());
    }

    // ---------- 400 ----------

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\": null}", "{\"text\": \"\"}", "{\"text\": \"   \"}"})
    void missingOrBlankText_is400_andTheModelIsNotCalled(String body) throws Exception {
        suggest(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("text"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"));

        verifyNoInteractions(aiClient);
    }

    @Test
    void textOver500Characters_is400_andTheModelIsNotCalled() throws Exception {
        suggest("{\"text\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("text"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be at most 500 characters"));

        verifyNoInteractions(aiClient);
    }

    @Test
    void bodyThatIsNotJson_is400MalformedRequest() throws Exception {
        suggest("pay rent")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(aiClient);
    }

    // ---------- AI failures, each with the full ADR-005 body ----------

    static Stream<Arguments> aiFailures() {
        return Stream.of(
                Arguments.of(new AiNotConfiguredException(), 503, "AI_NOT_CONFIGURED", "AI service not configured"),
                Arguments.of(new AiTimeoutException("secret upstream detail", null), 504, "AI_TIMEOUT", "AI service timeout"),
                Arguments.of(new AiUpstreamException("secret upstream detail", true, null), 502, "AI_UPSTREAM_ERROR", "AI service error"),
                Arguments.of(new AiOutputInvalidException("secret upstream detail", "{}"), 502, "AI_OUTPUT_INVALID", "Invalid AI output"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("aiFailures")
    void aiFailure_mapsToItsStatusAndCode_withAFixedDetail(
            RuntimeException failure, int status, String code, String title) throws Exception {
        when(aiClient.callTool(any())).thenThrow(failure);

        suggest("""
                {"text": "pay rent"}""")
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.instance").value("/tasks/suggest"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                // The exception message is for the server log only.
                .andExpect(content().string(not(containsString("secret upstream detail"))));
    }

    @Test
    void modelOutputThatFailsValidation_is502OutputInvalid() throws Exception {
        modelReturns("""
                {"title": "Pay rent", "description": null, "dueDate": null, "priority": "URGENT"}""");

        suggest("""
                {"text": "pay rent, urgent"}""")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_OUTPUT_INVALID"))
                .andExpect(content().string(not(containsString("URGENT"))));
    }
}
