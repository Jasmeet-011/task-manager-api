package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/** HTTP only: bind and validate input, call one service method, return a DTO. */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        TaskResponse created = taskService.create(request);
        // fromCurrentRequestUri() uses only the path, so a query string on the POST
        // (e.g. /tasks?foo=1) never leaks into the Location header.
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<TaskResponse> list(
            // A List binds both ?status=TODO&status=DONE and ?status=TODO,DONE. A bad value in either form
            // fails the conversion, and the handler turns that into a 400 naming the param.
            @RequestParam(required = false) List<Status> status,
            @RequestParam(required = false) List<Priority> priority,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dueAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate dueBefore,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            WebRequest webRequest) {
        // Read "sort" raw. A List<String> @RequestParam would split a single "priority,desc" on the comma,
        // but not when sort is repeated, so the two forms would parse differently.
        String[] sort = webRequest.getParameterValues("sort");
        TaskFilter filter = new TaskFilter(status, priority, projectId, dueAfter, dueBefore);
        return taskService.list(filter, page, size, sort == null ? List.of() : List.of(sort));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id) {
        return taskService.get(id);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/project/{projectId}")
    public TaskResponse assignProject(@PathVariable Long id, @PathVariable Long projectId) {
        return taskService.assignProject(id, projectId);
    }

    @DeleteMapping("/{id}/project")
    public ResponseEntity<Void> unassignProject(@PathVariable Long id) {
        taskService.unassignProject(id);
        return ResponseEntity.noContent().build();
    }
}
