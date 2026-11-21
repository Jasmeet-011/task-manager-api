package dev.jasmeet.taskmanager.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /**
     * The list endpoint. The entity graph loads each task's project in the same SELECT (a LEFT JOIN),
     * so building the response does not run one query per project (the N+1 measured in M3).
     * The project stays LAZY everywhere else.
     */
    @Override
    @EntityGraph(attributePaths = "project")
    Page<Task> findAll(Specification<Task> spec, Pageable pageable);

    /**
     * Deletes the row with one DELETE statement, whatever its version. Returns how many rows were deleted
     * (0 or 1).
     *
     * <p>A bulk delete bypasses the persistence context: no entity is loaded, and none that is already loaded
     * is removed or notified. That is safe here because Task has no cascades and no lifecycle callbacks, so
     * there is nothing Hibernate would otherwise do on a remove, and TaskService.delete loads no Task in
     * the same transaction, so no stale copy of it can be left behind.
     */
    @Modifying
    @Query("delete from Task t where t.id = :id")
    int deleteWithoutVersionCheck(@Param("id") Long id);
}
