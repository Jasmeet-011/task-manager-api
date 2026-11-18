package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.NotFoundException;
import dev.jasmeet.taskmanager.common.PageResponse;
import dev.jasmeet.taskmanager.project.Project;
import dev.jasmeet.taskmanager.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    TaskRepository taskRepository;

    @Mock
    ProjectRepository projectRepository;

    @InjectMocks
    TaskService taskService;

    private static Project projectWithId(Long id, String name) {
        Project project = new Project(name, null);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private static Task taskWithId(Long id, String title, String description,
                                   LocalDate dueDate, Priority priority, Status status) {
        Task task = new Task(title, description, dueDate, priority, status);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    @Test
    void create_savesTaskWithStatusTodoAndReturnsResponse() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 7L);
            return t;
        });

        TaskResponse response = taskService.create(
                new CreateTaskRequest("Write report", "Q4 numbers", due, Priority.HIGH));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.TODO);
        assertThat(response).isEqualTo(new TaskResponse(
                7L, "Write report", "Q4 numbers", due, Priority.HIGH, Status.TODO, null, null));
    }

    @Test
    void get_returnsTask() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(
                taskWithId(1L, "A", null, due, Priority.LOW, Status.DONE)));

        TaskResponse response = taskService.get(1L);

        assertThat(response).isEqualTo(new TaskResponse(1L, "A", null, due, Priority.LOW, Status.DONE, null, null));
    }

    @Test
    void get_missingId_throwsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.get(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task 99 not found");
    }

    @Test
    void list_requestsPageSortedByIdAndMapsToPageResponse() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        Task task = taskWithId(1L, "A", null, due, Priority.MEDIUM, Status.TODO);
        when(taskRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(1, 2), 3));

        PageResponse<TaskResponse> response = taskService.list(1, 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("id"));

        assertThat(response.content()).containsExactly(TaskResponse.from(task));
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
    }

    @Test
    void update_overwritesAllFieldsAndClearsMissingDescription() {
        Task existing = taskWithId(5L, "Old", "old description",
                LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        LocalDate newDue = LocalDate.of(2026, 12, 24);

        TaskResponse response = taskService.update(5L,
                new UpdateTaskRequest("New", null, newDue, Priority.HIGH, Status.IN_PROGRESS));

        assertThat(response).isEqualTo(new TaskResponse(
                5L, "New", null, newDue, Priority.HIGH, Status.IN_PROGRESS, null, null));
        // The managed entity is flushed at commit, so the service must not call save.
        // Whether the change really persists is proven by the PUT integration test.
        verify(taskRepository, never()).save(any());
    }

    @Test
    void update_missingId_throwsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.update(99L,
                new UpdateTaskRequest("T", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_removesTask() {
        Task existing = taskWithId(3L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(existing));

        taskService.delete(3L);

        verify(taskRepository).delete(existing);
    }

    @Test
    void delete_missingId_throwsNotFoundAndDeletesNothing() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.delete(99L)).isInstanceOf(NotFoundException.class);

        verify(taskRepository, never()).delete(any());
    }

    // ---------- project assignment ----------

    @Test
    void assignProject_setsProjectAndReturnsItsIdAndName() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        Task task = taskWithId(1L, "A", null, due, Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(projectWithId(10L, "Home")));

        TaskResponse response = taskService.assignProject(1L, 10L);

        assertThat(response).isEqualTo(new TaskResponse(1L, "A", null, due, Priority.LOW, Status.TODO, 10L, "Home"));
        assertThat(task.getProject().getId()).isEqualTo(10L);
    }

    @Test
    void assignProject_taskAlreadyInAnotherProject_movesIt() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        task.moveTo(projectWithId(10L, "Home"));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(20L)).thenReturn(Optional.of(projectWithId(20L, "Work")));

        TaskResponse response = taskService.assignProject(1L, 20L);

        assertThat(response.projectId()).isEqualTo(20L);
        assertThat(response.projectName()).isEqualTo("Work");
    }

    @Test
    void assignProject_sameProjectTwice_givesTheSameResult() {
        Project home = projectWithId(10L, "Home");
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(home));

        TaskResponse first = taskService.assignProject(1L, 10L);
        TaskResponse second = taskService.assignProject(1L, 10L);

        assertThat(second).isEqualTo(first);
        assertThat(task.getProject()).isSameAs(home);
    }

    @Test
    void assignProject_missingTask_throwsNotFoundWithoutLookingUpTheProject() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.assignProject(99L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task 99 not found");

        verify(projectRepository, never()).findById(any());
    }

    @Test
    void assignProject_missingProject_throwsNotFoundAndLeavesTaskUnchanged() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.assignProject(1L, 99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project 99 not found");

        assertThat(task.getProject()).isNull();
    }

    @Test
    void unassignProject_clearsTheProject() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        task.moveTo(projectWithId(10L, "Home"));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        taskService.unassignProject(1L);

        assertThat(task.getProject()).isNull();
    }

    @Test
    void unassignProject_taskWithoutProject_isANoOp() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        taskService.unassignProject(1L);

        assertThat(task.getProject()).isNull();
    }

    @Test
    void unassignProject_missingTask_throwsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.unassignProject(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_leavesTheProjectUntouched() {
        Project home = projectWithId(10L, "Home");
        Task task = taskWithId(5L, "Old", null, LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        task.moveTo(home);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));

        TaskResponse response = taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE));

        assertThat(task.getProject()).isSameAs(home);
        assertThat(response.projectId()).isEqualTo(10L);
        assertThat(response.projectName()).isEqualTo("Home");
    }
}
