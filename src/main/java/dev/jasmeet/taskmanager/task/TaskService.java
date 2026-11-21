package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.ConflictException;
import dev.jasmeet.taskmanager.common.InvalidRequestException;
import dev.jasmeet.taskmanager.common.NotFoundException;
import dev.jasmeet.taskmanager.common.PageResponse;
import dev.jasmeet.taskmanager.project.Project;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class TaskService {

    static final String VERSION_CONFLICT = "VERSION_CONFLICT";

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;

    public TaskService(TaskRepository taskRepository, ProjectRepository projectRepository) {
        this.taskRepository = taskRepository;
        // The repository, not ProjectService: entities must not leave the service layer.
        this.projectRepository = projectRepository;
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

    /** @param sort the raw values of the "sort" param. TaskSort validates them and adds the default and the id tiebreaker. */
    public PageResponse<TaskResponse> list(TaskFilter filter, int page, int size, List<String> sort) {
        // Both dates are inclusive, so equal dates mean "due exactly that day". A backwards range is
        // almost certainly swapped dates, and an empty list would hide the mistake.
        if (filter.dueAfter() != null && filter.dueBefore() != null && filter.dueAfter().isAfter(filter.dueBefore())) {
            throw new InvalidRequestException("dueAfter", "must not be later than dueBefore");
        }
        PageRequest pageRequest = PageRequest.of(page, size, TaskSort.resolve(sort));
        // The repository fetches the project in the same query, so the mapping below runs no extra SELECTs.
        return PageResponse.from(
                taskRepository.findAll(TaskSpecifications.from(filter), pageRequest).map(TaskResponse::from));
    }

    @Transactional
    public TaskResponse update(Long id, UpdateTaskRequest request) {
        Task task = findOrThrow(id);
        // Compare the version the client last saw with the row we just loaded. @Version cannot do this: it
        // only guards the time between this load and the flush, and never sees the client's number.
        // Objects.equals, not ==: two Long objects above 127 are equal in value but not the same object.
        if (!Objects.equals(task.getVersion(), request.version())) {
            throw versionConflict(id);
        }
        task.update(
                request.title(),
                request.description(),
                request.dueDate(),
                request.priority(),
                request.status());
        // No save (the entity is managed), but an explicit flush: @Version only increments when the UPDATE
        // runs. Left to commit time, the response below would show the old version and the client's next
        // PUT would get a false 409. The flush is also where a write that slipped in after our load is
        // detected, and flushOrConflict turns that into the same 409 as the comparison above.
        flushOrConflict(id);
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(Long id) {
        taskRepository.delete(findOrThrow(id));
    }

    /**
     * Sets the task's project, moving it if it was in another one. Calling it again with the same
     * project changes nothing: Hibernate sees no difference, so it runs no UPDATE.
     */
    @Transactional
    public TaskResponse assignProject(Long taskId, Long projectId) {
        // Task first, so when both are missing the answer is always "Task ... not found".
        Task task = findOrThrow(taskId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project", projectId));
        task.moveTo(project);
        // Flush so the response carries the new version. If the project did not change, Hibernate runs no
        // UPDATE and the version stays the same.
        taskRepository.flush();
        return TaskResponse.from(task);
    }

    /** Leaves the task with no project. Already having none is fine: the caller's goal is met. */
    @Transactional
    public void unassignProject(Long taskId) {
        findOrThrow(taskId).removeFromProject();
    }

    /**
     * Writes the pending changes now. Hibernate runs the UPDATE as "... WHERE id = ? AND version = <loaded>",
     * so if another request committed a change after our load, no row matches and it throws. That is the
     * same situation as a stale client version, so it gets the same 409. Without this catch it would
     * be a 500. The flush goes through the repository proxy, which turns Hibernate's exception into
     * Spring's ObjectOptimisticLockingFailureException.
     */
    private void flushOrConflict(Long id) {
        try {
            taskRepository.flush();
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw versionConflict(id);
        }
    }

    // fieldErrors is empty (ADR-005): the client's version was a stale snapshot, not an invalid value.
    private static ConflictException versionConflict(Long id) {
        return new ConflictException(VERSION_CONFLICT,
                "Task " + id + " was changed by someone else since you loaded it. Fetch it again and retry.",
                List.of());
    }

    private Task findOrThrow(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Task", id));
    }
}
