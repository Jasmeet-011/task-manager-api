package dev.jasmeet.taskmanager.common;

import dev.jasmeet.taskmanager.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every error path must return the ADR-005 body. These tests assert on the whole body shape. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorHandlingIntegrationTest.ThrowingController.class)
class ErrorHandlingIntegrationTest {

    /** Test-only endpoints: one that blows up (catch-all 500), one with a required param (safety net). */
    @RestController
    static class ThrowingController {
        @GetMapping("/test-only/boom")
        String boom() {
            throw new RuntimeException("secret db detail");
        }

        // No handler overrides MissingServletRequestParameterException, so it reaches the safety net.
        @GetMapping("/test-only/needs-param")
        String needsParam(@RequestParam String q) {
            return q;
        }
    }

    @Autowired
    MockMvc mockMvc;

    // ---------- helpers ----------

    /** Asserts the parts of the ADR-005 body every error must have. */
    private static ResultActions expectProblem(ResultActions actions, int httpStatus, String code, String instance)
            throws Exception {
        return actions
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    private ResultActions postTask(String json) throws Exception {
        return mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putTask(String json) throws Exception {
        return mockMvc.perform(put("/tasks/1").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static final String VALID_CREATE = """
            {"title": "T", "dueDate": "2026-12-01", "priority": "LOW"}""";

    // ---------- 400 VALIDATION_FAILED: Bean Validation ----------

    @Test
    void missingTitle_namesTheField() throws Exception {
        expectProblem(postTask("""
                {"dueDate": "2026-12-01", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"));
    }

    @Test
    void blankTitle_isRejected() throws Exception {
        expectProblem(postTask("""
                {"title": "   ", "dueDate": "2026-12-01", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    }

    @Test
    void everyFailingFieldIsListed() throws Exception {
        expectProblem(postTask("{}"), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        containsInAnyOrder("title", "dueDate", "priority")));
    }

    @Test
    void putWithoutStatus_namesStatus() throws Exception {
        expectProblem(putTask("""
                {"title": "T", "dueDate": "2026-12-01", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks/1")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
    }

    @Test
    void titleOverLimit_isRejectedBeforeTheDatabase() throws Exception {
        String json = """
                {"title": "%s", "dueDate": "2026-12-01", "priority": "LOW"}""".formatted("t".repeat(Task.TITLE_MAX + 1));
        expectProblem(postTask(json), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be at most 200 characters"));
    }

    @Test
    void descriptionOverLimit_isRejectedBeforeTheDatabase() throws Exception {
        String json = """
                {"title": "T", "description": "%s", "dueDate": "2026-12-01", "priority": "LOW"}"""
                .formatted("d".repeat(Task.DESCRIPTION_MAX + 1));
        expectProblem(postTask(json), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be at most 2000 characters"));
    }

    // ---------- 400 VALIDATION_FAILED: unreadable field values ----------

    @Test
    void unknownPriority_namesTheFieldAndListsTheAllowedValues() throws Exception {
        expectProblem(postTask("""
                {"title": "T", "dueDate": "2026-12-01", "priority": "URGENT"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("priority"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be one of LOW, MEDIUM, HIGH"));
    }

    @Test
    void lowercasePriority_isRejected() throws Exception {
        expectProblem(postTask("""
                {"title": "T", "dueDate": "2026-12-01", "priority": "high"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("priority"));
    }

    @Test
    void numericPriority_isRejectedNotMappedByPosition() throws Exception {
        // "priority": 1 must NOT silently become MEDIUM (enum ordinal).
        expectProblem(postTask("""
                {"title": "T", "dueDate": "2026-12-01", "priority": 1}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("priority"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be one of LOW, MEDIUM, HIGH"));
    }

    @Test
    void unknownStatusOnPut_namesTheField() throws Exception {
        expectProblem(putTask("""
                {"title": "T", "dueDate": "2026-12-01", "priority": "LOW", "status": "FINISHED"}"""),
                400, "VALIDATION_FAILED", "/tasks/1")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be one of TODO, IN_PROGRESS, DONE"));
    }

    @Test
    void malformedDate_namesTheField() throws Exception {
        expectProblem(postTask("""
                {"title": "T", "dueDate": "15/11/2026", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be a valid date in yyyy-MM-dd format"));
    }

    @Test
    void impossibleCalendarDate_isRejected() throws Exception {
        expectProblem(postTask("""
                {"title": "T", "dueDate": "2026-02-30", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dueDate"));
    }

    @Test
    void wrongJsonTypeForAField_namesTheField() throws Exception {
        expectProblem(postTask("""
                {"title": {"a": 1}, "dueDate": "2026-12-01", "priority": "LOW"}"""), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    }

    // ---------- 400 MALFORMED_REQUEST ----------

    @Test
    void invalidJsonSyntax_isMalformedRequest() throws Exception {
        expectProblem(postTask("{\"title\": "), 400, "MALFORMED_REQUEST", "/tasks")
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    @Test
    void notJsonAtAll_isMalformedRequest() throws Exception {
        expectProblem(postTask("this is not json"), 400, "MALFORMED_REQUEST", "/tasks");
    }

    @Test
    void emptyBody_isMalformedRequest() throws Exception {
        expectProblem(mockMvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)),
                400, "MALFORMED_REQUEST", "/tasks");
    }

    // ---------- 400 VALIDATION_FAILED: path and query params ----------

    @Test
    void nonNumericId_namesTheParam() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks/abc")), 400, "VALIDATION_FAILED", "/tasks/abc")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be a valid number"));
    }

    @Test
    void nonNumericIdOnPutAndDelete_alsoReturn400() throws Exception {
        expectProblem(mockMvc.perform(put("/tasks/abc").contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE)),
                400, "VALIDATION_FAILED", "/tasks/abc");
        expectProblem(mockMvc.perform(delete("/tasks/abc")), 400, "VALIDATION_FAILED", "/tasks/abc");
    }

    @Test
    void sizeAboveMax_namesSize() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("size", "101")), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void sizeZero_namesSize() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("size", "0")), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void negativePage_namesPage() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("page", "-1")), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
    }

    @Test
    void nonNumericPage_namesPage() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("page", "abc")), 400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be a valid number"));
    }

    // ---------- 400 VALIDATION_FAILED: sort ----------

    @Test
    void sortOnAFieldThatIsNotAllowed_namesSort() throws Exception {
        // title and id are real columns but not whitelisted. priorityRank is the internal column behind "priority".
        for (String field : new String[] {"title", "id", "priorityRank"}) {
            expectProblem(mockMvc.perform(get("/tasks").param("sort", field)), 400, "VALIDATION_FAILED", "/tasks")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("unknown sort field '" + field + "', must be one of dueDate, priority"));
        }
    }

    @Test
    void sortWithABadDirection_namesSort() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("sort", "priority,sideways")),
                400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("invalid sort direction 'sideways', must be asc or desc"));
    }

    @Test
    void malformedSortValues_nameSort() throws Exception {
        for (String value : new String[] {"priority,desc,asc", ",desc", "priority,"}) {
            expectProblem(mockMvc.perform(get("/tasks").param("sort", value)), 400, "VALIDATION_FAILED", "/tasks")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
        }
    }

    @Test
    void sameSortFieldTwice_namesSort() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("sort", "priority,asc", "priority,desc")),
                400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    void oneBadSortAmongGoodOnes_isStillRejected() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks").param("sort", "priority,desc", "title")),
                400, "VALIDATION_FAILED", "/tasks")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    // ---------- 404 ----------

    @Test
    void missingTask_returns404WithTheSameShape() throws Exception {
        expectProblem(mockMvc.perform(get("/tasks/999999")), 404, "NOT_FOUND", "/tasks/999999")
                .andExpect(jsonPath("$.detail").value("Task 999999 not found"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    @Test
    void unknownRoute_returns404WithTheSameShape() throws Exception {
        expectProblem(mockMvc.perform(get("/nope")), 404, "NOT_FOUND", "/nope");
    }

    // ---------- 405 and 415 ----------

    @Test
    void wrongMethod_returns405WithAllowHeader() throws Exception {
        MvcResult result = expectProblem(mockMvc.perform(patch("/tasks/1")), 405, "METHOD_NOT_ALLOWED", "/tasks/1")
                .andReturn();

        String allow = result.getResponse().getHeader("Allow");
        assertThat(allow).isNotNull();
        assertThat(allow).contains("GET", "PUT", "DELETE");
        assertThat(allow).doesNotContain("PATCH");
    }

    @Test
    void wrongContentType_returns415() throws Exception {
        expectProblem(mockMvc.perform(post("/tasks").contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "UNSUPPORTED_MEDIA_TYPE", "/tasks");
    }

    // ---------- safety net: a Spring exception no handler overrides ----------

    @Test
    void missingRequiredParam_getsTypeCodeAndFieldErrorsFromTheSafetyNet() throws Exception {
        expectProblem(mockMvc.perform(get("/test-only/needs-param")),
                400, "VALIDATION_FAILED", "/test-only/needs-param")
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    @Test
    void unacceptableAcceptHeader_returns406WithClientErrorCode() throws Exception {
        // No ADR-005 code exists for 406, so the safety net uses the CLIENT_ERROR fallback.
        expectProblem(mockMvc.perform(get("/tasks").accept(MediaType.APPLICATION_XML)),
                406, "CLIENT_ERROR", "/tasks")
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    // ---------- 500 ----------

    @Test
    void unexpectedException_returnsGeneric500_withoutLeakingAnything() throws Exception {
        MvcResult result = expectProblem(mockMvc.perform(get("/test-only/boom")),
                500, "INTERNAL_ERROR", "/test-only/boom")
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("secret db detail")
                .doesNotContain("RuntimeException")
                .doesNotContain("stackTrace")
                .doesNotContain("dev.jasmeet")
                .doesNotContain("\tat ");
    }
}
