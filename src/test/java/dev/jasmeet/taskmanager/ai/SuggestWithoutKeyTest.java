package dev.jasmeet.taskmanager.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The app as a reviewer runs it cold: no key, nothing mocked. The base URL points at a closed local
 * port, so if a request were ever sent it would fail as 502, not 503. A 503 shows the key was checked
 * before any request.
 */
@SpringBootTest(properties = {"ai.anthropic.api-key=", "ai.anthropic.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
class SuggestWithoutKeyTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void noKey_is503NotConfigured_andTellsTheOperatorWhatToSet() throws Exception {
        mockMvc.perform(post("/tasks/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"pay rent by Friday\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.instance").value("/tasks/suggest"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.detail", containsString("ANTHROPIC_API_KEY")));
    }
}
