package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.NotFoundException;
import dev.jasmeet.taskmanager.common.PageResponse;
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

    @InjectMocks
    TaskService taskService;

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
                7L, "Write report", "Q4 numbers", due, Priority.HIGH, Status.TODO));
    }

    @Test
    void get_returnsTask() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(
                taskWithId(1L, "A", null, due, Priority.LOW, Status.DONE)));

        TaskResponse response = taskService.get(1L);

        assertThat(response).isEqualTo(new TaskResponse(1L, "A", null, due, Priority.LOW, Status.DONE));
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
                5L, "New", null, newDue, Priority.HIGH, Status.IN_PROGRESS));
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
}
