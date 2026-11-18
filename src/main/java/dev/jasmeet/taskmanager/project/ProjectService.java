package dev.jasmeet.taskmanager.project;

import dev.jasmeet.taskmanager.common.ConflictException;
import dev.jasmeet.taskmanager.common.FieldViolation;
import dev.jasmeet.taskmanager.common.NotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProjectService {

    static final String DUPLICATE_PROJECT_NAME = "DUPLICATE_PROJECT_NAME";

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        // Trim so " Home " and "Home" cannot both exist. Comparison stays case-sensitive (see ADR-011).
        Project project = new Project(request.name().trim(), request.description());
        try {
            // saveAndFlush, not save: the INSERT must run inside this try block. A flush left
            // for commit time would throw after this method returns, outside the catch, and become a 500.
            return ProjectResponse.from(projectRepository.saveAndFlush(project));
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateName(ex)) {
                throw new ConflictException(DUPLICATE_PROJECT_NAME,
                        "A project named '" + project.getName() + "' already exists.",
                        List.of(new FieldViolation("name", "already exists")));
            }
            throw ex; // any other integrity violation is a bug: let it reach the logged 500 handler
        }
    }

    public ProjectResponse get(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Project", id));
        return ProjectResponse.from(project);
    }

    public List<ProjectResponse> list() {
        return projectRepository.findAll(Sort.by("id")).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /**
     * True only for a violation of the name constraint. We match on the constraint name, not on
     * "any unique violation", so a unique constraint added later is not mislabelled as a duplicate name.
     */
    private static boolean isDuplicateName(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof ConstraintViolationException violation
                && bareConstraintName(violation.getConstraintName())
                        .equalsIgnoreCase(Project.NAME_UNIQUE_CONSTRAINT);
    }

    /**
     * H2 reports the name decorated: "PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F".
     * Keep the first word and drop the schema prefix. The result is still compared for equality, never
     * with contains(), so a constraint named "uk_projects_name_other" does not match.
     */
    private static String bareConstraintName(String reported) {
        if (reported == null || reported.isBlank()) {
            return "";
        }
        String firstWord = reported.trim().split("\\s+")[0];
        return firstWord.substring(firstWord.lastIndexOf('.') + 1);
    }
}
