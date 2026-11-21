package dev.jasmeet.taskmanager.task;

import dev.jasmeet.taskmanager.common.ConflictException;
import dev.jasmeet.taskmanager.common.FieldViolation;
import dev.jasmeet.taskmanager.common.InvalidRequestException;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

    private static final TaskFilter NO_FILTER = new TaskFilter(null, null, null, null, null);

    /** Matches any Specification. Typed here once so the generic cast is not repeated in every test. */
    @SuppressWarnings("unchecked")
    private static Specification<Task> anySpecification() {
        return any(Specification.class);
    }

    private static Project projectWithId(Long id, String name) {
        Project project = new Project(name, null);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private static Task taskWithId(Long id, String title, String description,
                                   LocalDate dueDate, Priority priority, Status status) {
        Task task = new Task(title, description, dueDate, priority, status);
        ReflectionTestUtils.setField(task, "id", id);
        // What Hibernate has set on a task loaded from the database that was never updated.
        ReflectionTestUtils.setField(task, "version", 0L);
        return task;
    }

    @Test
    void create_savesTaskWithStatusTodoAndReturnsResponse() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 7L);
            ReflectionTestUtils.setField(t, "version", 0L); // Hibernate gives a new row version 0
            return t;
        });

        TaskResponse response = taskService.create(
                new CreateTaskRequest("Write report", "Q4 numbers", due, Priority.HIGH));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.TODO);
        assertThat(response).isEqualTo(new TaskResponse(
                7L, "Write report", "Q4 numbers", due, Priority.HIGH, Status.TODO, null, null, 0L));
    }

    @Test
    void get_returnsTask() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(
                taskWithId(1L, "A", null, due, Priority.LOW, Status.DONE)));

        TaskResponse response = taskService.get(1L);

        assertThat(response).isEqualTo(new TaskResponse(
                1L, "A", null, due, Priority.LOW, Status.DONE, null, null, 0L));
    }

    @Test
    void get_missingId_throwsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.get(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task 99 not found");
    }

    @Test
    void list_requestsPageWithDefaultSortAndMapsToPageResponse() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        Task task = taskWithId(1L, "A", null, due, Priority.MEDIUM, Status.TODO);
        when(taskRepository.findAll(anySpecification(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(task), PageRequest.of(1, 2), 3));

        PageResponse<TaskResponse> response = taskService.list(NO_FILTER, 1, 2, List.of());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAll(anySpecification(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        // ADR-012 default: dueDate asc, then priority desc (through its rank column), then id asc.
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(
                Sort.Order.asc("dueDate"), Sort.Order.desc("priorityRank"), Sort.Order.asc("id")));

        assertThat(response.content()).containsExactly(TaskResponse.from(task));
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
    }

    @Test
    void list_customSortReplacesDefaultAndEndsWithId() {
        when(taskRepository.findAll(anySpecification(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        taskService.list(NO_FILTER, 0, 20, List.of("priority,desc"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAll(anySpecification(), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(
                Sort.Order.desc("priorityRank"), Sort.Order.asc("id")));
    }

    @Test
    void list_invalidSort_throwsAndNeverQueries() {
        assertThatThrownBy(() -> taskService.list(NO_FILTER, 0, 20, List.of("title")))
                .isInstanceOf(InvalidRequestException.class);

        verify(taskRepository, never()).findAll(anySpecification(), any(Pageable.class));
    }

    @Test
    void list_dueAfterLaterThanDueBefore_throwsNamingDueAfter_andNeverQueries() {
        TaskFilter backwards = new TaskFilter(null, null, null,
                LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 16));

        assertThatThrownBy(() -> taskService.list(backwards, 0, 20, List.of()))
                .isInstanceOfSatisfying(InvalidRequestException.class, ex ->
                        assertThat(ex.getFieldErrors()).containsExactly(
                                new FieldViolation("dueAfter", "must not be later than dueBefore")));

        verify(taskRepository, never()).findAll(anySpecification(), any(Pageable.class));
    }

    @Test
    void list_equalDates_areAValidRangeMeaningExactlyThatDay() {
        when(taskRepository.findAll(anySpecification(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        LocalDate day = LocalDate.of(2026, 11, 20);

        taskService.list(new TaskFilter(null, null, null, day, day), 0, 20, List.of());

        verify(taskRepository).findAll(anySpecification(), any(Pageable.class));
    }

    @Test
    void update_overwritesAllFieldsAndClearsMissingDescription() {
        Task existing = taskWithId(5L, "Old", "old description",
                LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        LocalDate newDue = LocalDate.of(2026, 12, 24);

        TaskResponse response = taskService.update(5L,
                new UpdateTaskRequest("New", null, newDue, Priority.HIGH, Status.IN_PROGRESS, 0L));

        assertThat(response).isEqualTo(new TaskResponse(
                5L, "New", null, newDue, Priority.HIGH, Status.IN_PROGRESS, null, null, 0L));
        // The entity is managed, so the service flushes it and must not call save.
        // Whether the change really persists is proven by the PUT integration test.
        verify(taskRepository).flush();
        verify(taskRepository, never()).save(any());
    }

    @Test
    void update_flushesBeforeBuildingTheResponse() {
        Task existing = taskWithId(5L, "Old", null, LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        // A mock cannot run Hibernate, so this stands in for it: the flush is what moves the version to 1.
        doAnswer(inv -> {
            ReflectionTestUtils.setField(existing, "version", 1L);
            return null;
        }).when(taskRepository).flush();

        TaskResponse response = taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, 0L));

        // 1 only if the response was built after the flush. Built before it, this would show 0.
        assertThat(response.version()).isEqualTo(1L);
    }

    @Test
    void update_staleVersion_throwsVersionConflict_andChangesNothing() {
        Task existing = taskWithId(5L, "Old", "old description",
                LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        ReflectionTestUtils.setField(existing, "version", 3L);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, 2L)))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(conflict.getFieldErrors()).isEmpty();
                });

        assertThat(existing.getTitle()).isEqualTo("Old");
        assertThat(existing.getStatus()).isEqualTo(Status.TODO);
        verify(taskRepository, never()).flush();
    }

    @Test
    void update_versionAhead_isAlsoAConflict() {
        // The client claims a version the row has not reached. It cannot have read that from us.
        Task existing = taskWithId(5L, "Old", null, LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, 1L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_versionsAbove127_compareByValue() {
        // Long.valueOf caches only -128 to 127. Above that these are two different objects with the same value,
        // so a comparison with == would wrongly report a conflict.
        Long loaded = Long.valueOf(1000L);
        Long sent = Long.valueOf(1000L);
        assertThat(loaded).isNotSameAs(sent);
        Task existing = taskWithId(5L, "Old", null, LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        ReflectionTestUtils.setField(existing, "version", loaded);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        TaskResponse response = taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, sent));

        assertThat(response.title()).isEqualTo("New");
    }

    @Test
    void update_flushLosesTheRace_mapsToTheSameConflict() {
        Task existing = taskWithId(5L, "Old", null, LocalDate.of(2026, 1, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        // Another request committed a change after our load, so the UPDATE matched no row.
        doThrow(new ObjectOptimisticLockingFailureException(Task.class, 5L)).when(taskRepository).flush();

        assertThatThrownBy(() -> taskService.update(5L,
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, 0L)))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(conflict.getFieldErrors()).isEmpty();
                });
    }

    @Test
    void update_missingId_throwsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.update(99L,
                new UpdateTaskRequest("T", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO, 0L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_removesTheRowWithoutLoadingTheTask() {
        when(taskRepository.deleteWithoutVersionCheck(3L)).thenReturn(1);

        taskService.delete(3L);

        verify(taskRepository).deleteWithoutVersionCheck(3L);
        // Not loaded, so there is no version to compare and no entity delete that Hibernate would guard.
        verify(taskRepository, never()).findById(any());
    }

    @Test
    void delete_noRowDeleted_throwsNotFound() {
        when(taskRepository.deleteWithoutVersionCheck(99L)).thenReturn(0);

        assertThatThrownBy(() -> taskService.delete(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task 99 not found");
    }

    // ---------- project assignment ----------

    @Test
    void assignProject_setsProjectAndReturnsItsIdAndName() {
        LocalDate due = LocalDate.of(2026, 12, 1);
        Task task = taskWithId(1L, "A", null, due, Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(projectWithId(10L, "Home")));

        TaskResponse response = taskService.assignProject(1L, 10L);

        assertThat(response).isEqualTo(new TaskResponse(
                1L, "A", null, due, Priority.LOW, Status.TODO, 10L, "Home", 0L));
        assertThat(task.getProject().getId()).isEqualTo(10L);
    }

    @Test
    void assignProject_flushesBeforeBuildingTheResponse() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(projectWithId(10L, "Home")));
        doAnswer(inv -> {
            ReflectionTestUtils.setField(task, "version", 1L);
            return null;
        }).when(taskRepository).flush();

        TaskResponse response = taskService.assignProject(1L, 10L);

        assertThat(response.version()).isEqualTo(1L);
    }

    @Test
    void assignProject_flushLosesTheRace_mapsToVersionConflict() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(projectWithId(10L, "Home")));
        doThrow(new ObjectOptimisticLockingFailureException(Task.class, 1L)).when(taskRepository).flush();

        assertThatThrownBy(() -> taskService.assignProject(1L, 10L))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(conflict.getFieldErrors()).isEmpty();
                });
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
    void unassignProject_flushLosesTheRace_mapsToVersionConflict() {
        Task task = taskWithId(1L, "A", null, LocalDate.of(2026, 12, 1), Priority.LOW, Status.TODO);
        task.moveTo(projectWithId(10L, "Home"));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new ObjectOptimisticLockingFailureException(Task.class, 1L)).when(taskRepository).flush();

        assertThatThrownBy(() -> taskService.unassignProject(1L))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT"));
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
                new UpdateTaskRequest("New", null, LocalDate.of(2026, 12, 24), Priority.HIGH, Status.DONE, 0L));

        assertThat(task.getProject()).isSameAs(home);
        assertThat(response.projectId()).isEqualTo(10L);
        assertThat(response.projectName()).isEqualTo("Home");
    }
}
