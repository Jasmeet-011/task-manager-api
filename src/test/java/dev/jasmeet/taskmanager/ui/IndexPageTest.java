package dev.jasmeet.taskmanager.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for the single page (ADR-015). The assignment asks for no UI tests, and there is no
 * browser here, so the page's behaviour is checked by hand. These two checks cover what can be
 * checked cheaply: the page is served at /, and it never puts text into the page as HTML.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IndexPageTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void root_isServedByTheIndexPage() throws Exception {
        // MockMvc does not follow a forward, so this checks the welcome-page mapping and the next test the file.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void indexHtml_isServedAsHtmlWithTheTaskList() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"task-rows\"")));
    }

    /**
     * Task titles, project names and AI drafts are untrusted, so the page must only use textContent and
     * .value. This cannot prove the page is safe, but it fails the moment someone writes HTML into it.
     */
    @Test
    void indexHtml_neverBuildsHtmlFromText() throws IOException {
        String page = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);

        Pattern banned = Pattern.compile(
                "innerHTML|outerHTML|insertAdjacentHTML|document\\.write|\\beval\\s*\\(|new\\s+Function\\s*\\(",
                Pattern.CASE_INSENSITIVE);

        assertThat(banned.matcher(page).find())
                .as("index.html must set text with textContent or .value, never as HTML")
                .isFalse();
    }
}
