package dev.jasmeet.taskmanager.project;

import dev.jasmeet.taskmanager.common.ConflictException;
import dev.jasmeet.taskmanager.common.FieldViolation;
import dev.jasmeet.taskmanager.common.NotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    ProjectRepository projectRepository;

    @InjectMocks
    ProjectService projectService;

    private static Project projectWithId(Long id, String name, String description) {
        Project project = new Project(name, description);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violation", new SQLException("boom"), constraintName));
    }

    @Test
    void create_trimsNameSavesAndReturnsResponse() {
        when(projectRepository.saveAndFlush(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 4L);
            return p;
        });

        ProjectResponse response = projectService.create(new CreateProjectRequest("  Home  ", "chores"));

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Home");
        assertThat(response).isEqualTo(new ProjectResponse(4L, "Home", "chores"));
    }

    @Test
    void create_nameConstraintViolation_throwsConflictWithNameFieldError() {
        when(projectRepository.saveAndFlush(any(Project.class)))
                .thenThrow(violationOf(Project.NAME_UNIQUE_CONSTRAINT));

        assertThatThrownBy(() -> projectService.create(new CreateProjectRequest("Home", null)))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DUPLICATE_PROJECT_NAME");
                    assertThat(ex.getFieldErrors()).containsExactly(new FieldViolation("name", "already exists"));
                });
    }

    @Test
    void create_constraintNameAsH2ReportsIt_stillThrowsConflict() {
        // Exactly what H2 puts in getConstraintName(): schema-qualified, upper case, with index text.
        when(projectRepository.saveAndFlush(any(Project.class)))
                .thenThrow(violationOf("PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F"));

        assertThatThrownBy(() -> projectService.create(new CreateProjectRequest("Home", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_otherConstraintViolation_isNotReportedAsDuplicateName() {
        DataIntegrityViolationException other = violationOf("uk_projects_something_else");
        when(projectRepository.saveAndFlush(any(Project.class))).thenThrow(other);

        assertThatThrownBy(() -> projectService.create(new CreateProjectRequest("Home", null)))
                .isSameAs(other);
    }

    @Test
    void create_constraintWhoseNameOnlyStartsWithOurs_isNotReportedAsDuplicateName() {
        // Guards against a contains()/startsWith() match on the constraint name.
        DataIntegrityViolationException lookalike =
                violationOf("PUBLIC.UK_PROJECTS_NAME_OTHER INDEX PUBLIC.UK_PROJECTS_NAME_OTHER_INDEX_1");
        when(projectRepository.saveAndFlush(any(Project.class))).thenThrow(lookalike);

        assertThatThrownBy(() -> projectService.create(new CreateProjectRequest("Home", null)))
                .isSameAs(lookalike);
    }

    @Test
    void create_violationWithoutConstraintName_isNotReportedAsDuplicateName() {
        DataIntegrityViolationException unnamed = new DataIntegrityViolationException("no name");
        when(projectRepository.saveAndFlush(any(Project.class))).thenThrow(unnamed);

        assertThatThrownBy(() -> projectService.create(new CreateProjectRequest("Home", null)))
                .isSameAs(unnamed);
    }

    @Test
    void get_returnsProject() {
        when(projectRepository.findById(4L)).thenReturn(Optional.of(projectWithId(4L, "Home", null)));

        assertThat(projectService.get(4L)).isEqualTo(new ProjectResponse(4L, "Home", null));
    }

    @Test
    void get_missingProject_throwsNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.get(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project 99 not found");
    }

    @Test
    void list_returnsAllProjectsOrderedById() {
        when(projectRepository.findAll(Sort.by("id"))).thenReturn(List.of(
                projectWithId(1L, "Home", null),
                projectWithId(2L, "Work", "office")));

        assertThat(projectService.list()).containsExactly(
                new ProjectResponse(1L, "Home", null),
                new ProjectResponse(2L, "Work", "office"));
    }
}
