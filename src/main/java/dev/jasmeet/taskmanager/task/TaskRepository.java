package dev.jasmeet.taskmanager.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /**
     * The list endpoint. The entity graph loads each task's project in the same SELECT (a LEFT JOIN),
     * so building the response does not run one query per project (the N+1 measured in M3).
     * The project stays LAZY everywhere else.
     */
    @Override
    @EntityGraph(attributePaths = "project")
    Page<Task> findAll(Specification<Task> spec, Pageable pageable);
}
