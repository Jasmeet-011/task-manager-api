package dev.jasmeet.taskmanager.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP only, like every controller. It lives in ai/ and not in TaskController so that dependencies
 * go one way, ai to task: the AI feature uses Priority and Task's limits, and task/ never imports ai/.
 * The URL stays under /tasks because the result is a task draft.
 */
@RestController
@RequestMapping("/tasks")
public class TaskSuggestionController {

    private final TaskSuggestionService taskSuggestionService;

    public TaskSuggestionController(TaskSuggestionService taskSuggestionService) {
        this.taskSuggestionService = taskSuggestionService;
    }

    /**
     * Turns free text into an unsaved draft (ADR-003, ADR-013). 200, not 201: nothing is created.
     * AI failures are mapped by GlobalExceptionHandler to 502, 503 or 504.
     */
    @PostMapping("/suggest")
    public TaskDraft suggest(@Valid @RequestBody SuggestTaskRequest request) {
        return taskSuggestionService.suggest(request);
    }
}
