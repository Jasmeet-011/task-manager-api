package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.NotFoundException;
import dev.jasmeet.taskmanager.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        // Every new task starts as TODO. The client cannot choose the initial status.
        Task task = new Task(
                request.title(),
                request.description(),
                request.dueDate(),
                request.priority(),
                Status.TODO);
        return TaskResponse.from(taskRepository.save(task));
    }

    public TaskResponse get(Long id) {
        return TaskResponse.from(findOrThrow(id));
    }

    public PageResponse<TaskResponse> list(int page, int size) {
        // Fixed sort by id keeps paging stable. Sorting and filters arrive in M4.
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id"));
        return PageResponse.from(taskRepository.findAll(pageRequest).map(TaskResponse::from));
    }

    @Transactional
    public TaskResponse update(Long id, UpdateTaskRequest request) {
        Task task = findOrThrow(id);
        task.update(
                request.title(),
                request.description(),
                request.dueDate(),
                request.priority(),
                request.status());
        // No explicit save: the entity is managed, so Hibernate flushes the changes at commit.
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(Long id) {
        taskRepository.delete(findOrThrow(id));
    }

    private Task findOrThrow(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Task", id));
    }
}
