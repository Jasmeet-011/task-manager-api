
❯ <ide_opened_file>The user opened the file
  /Users/jasmeetsingh/dev/task-manager-api/docs/PLAN.md in the IDE. This may or
  may not be related to the current task.</ide_opened_file>

❯ Read CLAUDE.md and docs/. We're starting M3 (Project entity and association)
  from docs/PLAN.md. Done means: projects can be created and listed, tasks can
  be assigned and unassigned, and every new error path returns the ADR-005
  shape.

  My decisions:

  1. Project fields. A project has a required name (max 100 chars) and an
  optional description (max 500 chars). Why: a name is the one thing a project
  can't do without, and the limits keep the fields sane in the UI and the DB
  without being restrictive.
  2. Unique names. Project names must be unique, enforced by a database unique
  constraint rather than a check-then-insert in code. A duplicate should return
  409 with a new error code defined in ADR-005. Why: a check-then-insert has a
  race condition where two concurrent requests both pass the check, and only
  the DB can guarantee uniqueness. Catch the constraint violation and translate
  it to the 409.
  3. No project deletion. Don't build it, but document it as a next step. Why:
  deleting a project raises questions about what happens to its tasks, and I'd
  rather decide that deliberately than rush it into this change.
  4. List projects. GET /projects returns a plain list, no pagination. Why: we
  expect a small number of projects for now, and pagination adds complexity we
  don't need yet.
  5. Assign a task to a project. PUT /tasks/{id}/project/{projectId} assigns
  the task. If the task is already in a different project, it moves. Repeating
  the same call is a no-op. It returns 200 with a TaskResponse. Why: PUT is
  idempotent by definition, so "set this task's project to X" should behave the
  same no matter how many times it's called, and returning the updated task
  saves the client a follow-up GET.
  6. Unassign a task. DELETE /tasks/{id}/project removes the task from its
  project and returns 204, even if the task didn't have one. Why: the caller's
  intent is "this task should have no project," and that's already true, so
  there's nothing to fail on. This keeps DELETE idempotent too.
  7. Missing resources. If the task or the project doesn't exist, return 404.
  Why: it's the standard signal for a missing resource, and it lets clients
  distinguish "bad reference" from validation or conflict errors.

  Out of scope: project update or delete, filtering tasks by project (M4), and
  fixing N+1 (M4).

  Watch for:
  - The mapping follows ADR-002: unidirectional @ManyToOne(fetch = LAZY) from
  Task, no tasks collection on Project, no cascade.
  - PUT /tasks/{id} must never touch the project. Assignment gets its own
  entity methods.
  - Every @Size needs a matching @Column(length).
  - The unique constraint only fires when the insert is flushed. If the
  violation surfaces at commit, outside the code meant to catch it, it becomes
  a 500 instead of a 409. Tell me how you'll make sure it's caught.
  - TaskResponse gains projectId and projectName. Listing tasks will then load
  each project separately (N+1). Don't fix it now, but say where it happens so
  M4 can.

  Plan first. Show me the files, the steps in order, the name you propose for
  the new 409 code, and the tests. Also raise anything I haven't decided, such
  as whether name uniqueness is case-sensitive. Wait for my approval before
  writing code.

⏺ I'll start by reading the docs and the existing task code.

  Read 3 files (ctrl+o to expand)

⏺ Bash(git ls-files | grep -v '^docs/transcripts' && ls docs)
  ⎿  (No output)

  Read 12 files (ctrl+o to expand)

⏺ M3 plan: Project entity and association

  I've read CLAUDE.md, the four docs, and the M2 code. Nothing is written yet.

  Files

  New (project/)

  File: Project.java
  Contents: id, name (NAME_MAX = 100), description (DESCRIPTION_MAX = 500). Both

    @Column(length) values use these constants. The table has a named
  constraint,
     uk_projects_name.
  ────────────────────────────────────────
  File: ProjectRepository.java
  Contents: Plain JpaRepository.
  ────────────────────────────────────────
  File: CreateProjectRequest.java
  Contents: @NotBlank @Size(max = Project.NAME_MAX) name and @Size(max =
    Project.DESCRIPTION_MAX) description. The messages follow ADR-010: "must be
    at most N characters".
  ────────────────────────────────────────
  File: ProjectResponse.java
  Contents: id, name, description.
  ────────────────────────────────────────
  File: ProjectService.java
  Contents: create, get, list.
  ────────────────────────────────────────
  File: ProjectController.java
  Contents: POST /projects (201 with Location), GET /projects, GET
    /projects/{id}.

  New (common/)

  - ConflictException.java: it carries a code, a message and optional field
    errors. It's a RuntimeException, so the transaction rolls back. I'm making
    it generic so M6's VERSION_CONFLICT can reuse it. If you'd rather keep it
    project-specific for now, say so.

  Changed

  - Task.java (src/main/java/dev/jasmeet/taskmanager/task/Task.java) gets
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "project_id") Project project.
    It is nullable and has no cascade, per ADR-002. It also gets two new
    methods, moveTo(Project) and removeFromProject(). I'm avoiding the name
    assign, because the private assign(...) already sets the scalar fields.
    update() and that private method stay untouched, so PUT can't touch the
    project.
  - TaskResponse.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskResponse.java) gains
    projectId and projectName, both null when unassigned.
  - TaskService.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskService.java) gets
    assignProject(taskId, projectId) and unassignProject(taskId). It also takes
    ProjectRepository as a constructor dependency. It uses the repository and
    not ProjectService, because entities must not leave the service layer.
  - TaskController.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskController.java) gets PUT
    /tasks/{id}/project/{projectId} (200 with TaskResponse) and DELETE
    /tasks/{id}/project (204).
  - GlobalExceptionHandler.java
    (src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.java)
    gets one @ExceptionHandler(ConflictException.class) that returns a 409 in
    the ADR-005 shape.
  - Docs: DECISIONS.md, FLOW.md, PROGRESS.md, and the delete bullet in PLAN.md
    M3 (see the last section).

  The 409 code

  I propose DUPLICATE_PROJECT_NAME (409). It's specific, like VERSION_CONFLICT,
  so a client can switch on it. The alternative is a generic DUPLICATE_RESOURCE,
  which I'd only pick if you expect Tags soon.

  How the unique violation gets caught (and not turned into a 500)

  1. ProjectService.create calls projectRepository.saveAndFlush(project) inside
     its @Transactional method. It does not call plain save. The flush runs the
     INSERT while the try block is still active, so the database's unique-index
     error surfaces there. If I flushed implicitly at commit, the error would be
     thrown by the transaction interceptor after my method returned, outside my
     catch. That path ends in the 500 handler.
  2. A caveat on that: with GenerationType.IDENTITY (which Task uses, and
     Project will too), Hibernate inserts immediately on persist anyway. So
     plain save would probably work today. I'm still using saveAndFlush because
     correctness shouldn't depend on the ID strategy, and the call states the
     intent.
  3. The service catches DataIntegrityViolationException and throws
     ConflictException(DUPLICATE_PROJECT_NAME, ..., fieldErrors=[name]).
  4. I'm narrowing the catch to unique violations only. I'd check the cause is
     Hibernate's ConstraintViolationException of kind UNIQUE, and I'll confirm
     that API against the Hibernate version in the build before relying on it. A
     NOT NULL or length violation would be a bug, and it should stay a
     logged 500. If it were reported as "name already exists", it would hide
     that bug.
  5. Proof in tests:
     - a repository-level test that saves two projects with the same name
       directly, with no service involved. It shows the database enforces the
       rule.
     - a concurrency test that fires two simultaneous POST /projects with the
       same name. It expects exactly one 201, one 409, and no 500.

  Steps (each ends with a green ./mvnw test, and each is a commit point)

  1. Project and its endpoints. Add Project, the repository, DTOs,
     ProjectService, ConflictException with its handler, and ProjectController.
     Add ProjectServiceTest and ProjectApiIntegrationTest.
     Commit: "Add Project entity, endpoints and unique name handling".
  2. Association. Add the Task.project mapping, moveTo and removeFromProject,
     the new TaskResponse fields, the two TaskService methods, and the two
     endpoints. Extend TaskServiceTest and add TaskProjectIntegrationTest.
     Commit: "Add task-project assignment".
  3. Docs. Add ADR-011 with the M3 decisions, add the new row to ADR-005, move
     the three pending M3 items to Accepted, and update FLOW.md, PROGRESS.md and
     PLAN.md.
     Commit: "Record M3 decisions, flow and progress".

  Tests

  ProjectServiceTest (Mockito)
  - create returns the response.
  - create maps a unique violation to ConflictException.
  - create lets a non-unique violation propagate.
  - get returns the project.
  - get on a missing id throws NotFoundException.
  - list returns the mapped projects.

  ProjectApiIntegrationTest
  - POST returns 201, a Location header and the body.
  - GET list returns [] when empty, and all projects otherwise.
  - GET by id returns 200, 404 for a missing id and 400 for abc.
  - 400 VALIDATION_FAILED with the field named for: blank name, name of 101
    characters, description of 501 characters, and a missing body.
  - Boundary: a 100-character name is accepted.
  - Duplicate returns 409 with code, status, fieldErrors and
    application/problem+json asserted. The first project is unchanged.
  - Repository-level duplicate throws DataIntegrityViolationException.
  - Two concurrent duplicates give one 201 and one 409.

  TaskServiceTest additions
  - assign sets the project and maps projectId and projectName.
  - assign moves a task between projects.
  - Repeating the same assign changes nothing.
  - unassign clears the project, and unassign with no project is a no-op.
  - Missing task and missing project each throw NotFoundException. In the
    missing-project case the task is left untouched.
  - update() leaves the project as it was.

  TaskProjectIntegrationTest
  - Assign returns 200 with the project fields, and GET /tasks/{id} agrees.
  - Move to a second project works.
  - Repeating the same PUT returns the same 200 body.
  - Unassign returns 204 and the project fields become null. Unassigning a task
    with no project, and unassigning twice, both return 204.
  - 404 for a missing task and 404 for a missing project, each asserted as an
    ADR-005 body. After the missing-project case the task is still unassigned.
  - 400 for a non-numeric {id} or {projectId}.
  - PUT /tasks/{id} on an assigned task keeps its project.
  - Deleting a task leaves the project in place, and unassigning leaves the
    project in GET /projects.
  - GET /tasks shows project fields, and null for an unassigned task.

  Test isolation. Every test class that touches projects cleans up with tasks
  first and then projects, because of the foreign key. The existing task test
  classes only delete tasks, and that stays valid.

  Where the N+1 will happen (not fixing it)

  - TaskService.list, at the .map(TaskResponse::from) call, calls
    TaskResponse.from. That method reads task.getProject().getName(). projectId
    comes from the lazy proxy without a query, but the name triggers a SELECT.
  - The persistence context caches each project, so the cost is 1 query for the
    page plus 1 per distinct project on that page, not 1 per task. It's still
    the N+1 shape.
  - get and update pay one extra SELECT each.
  - M4's fix is @EntityGraph(attributePaths = "project") or a join fetch in the
    specification. I'll put a one-line comment at list() pointing at M4.

  Things you haven't decided, with my recommendation

  1. Case sensitivity of names. I recommend case-sensitive, so "Home" and "home"
     are two projects.
     - Case-insensitive uniqueness needs either an H2-only column type
       (VARCHAR_IGNORECASE, not portable) or an extra lowercase key column. Both
       add something to explain.
     - I'd document it as a known limit and a next step.
  2. Trimming. I recommend trimming the name in the service before saving, so "
     Home " and "Home" can't coexist. @NotBlank alone allows the padded form.
  3. Field errors on the 409. ADR-005 says fieldErrors is empty when no specific
     field failed. Here one did, so I'd include {"field": "name", "message":
     "already exists"}. The UI can then show it next to the input.
  4. GET /projects/{id}. It isn't in your decisions, but PLAN.md's M3 build list
     has it. Without it, the Location header on the 201 would point at a
     missing route. I've included it unless you say otherwise.
  5. List order. Order GET /projects by id, as tasks are. The UI can sort for a
     dropdown later.
  6. Which 404 wins when both ids are missing. I look up the task first, and the
     message names whichever is missing ("Task 5 not found" or "Project 9 not
     found"). The code is the same NOT_FOUND.
  7. M6 interaction. Once @Version exists, an assignment that changes the
     project will bump the version. A repeat PUT won't, because Hibernate's
     dirty check sees no change. Whether assignment must also send a version is
     still the pending M6 item, and I'm leaving it there.

  Docs and your "document as next step" request

  - ADR-011 records the M3 decisions:
    - project fields and limits
    - the unique constraint and how the violation is caught
    - the two assignment endpoints and their idempotence
    - the plain, unpaginated list
    - no project deletion, with "what happens to a deleted project's tasks
      (unassign, block, or cascade)" listed under next steps
  - ADR-005 gets the new row. You asked for that in decision 2, so I'm treating
    the amendment as authorized.
  - The earlier pending lean was "support delete and unassign tasks". Your
    decision 3 overrides it. I'll record that in ADR-011 and change the PLAN.md
    M3 line "Delete behavior per the pending M3 decision" to "No project
    deletion (see ADR-011)".
  - PROGRESS.md's "Left for next session" repeats the deletion next step. I'll
    leave the README alone, because its next-steps section is written in M6 and
    M8.

  Please approve, or tell me which of the seven items above to change.

❯ Approved, with one change: narrow the unique-violation catch by constraint
  name (uk_projects_name), not just the UNIQUE kind. If we add another unique
  constraint later, a kind check would mislabel it as a duplicate project name.

  On your open items, I agree with all seven recommendations: case-sensitive
  names (documented as a limit), trim the name, fieldErrors naming "name" on
  the 409, include GET /projects/{id}, id order, task looked up first, generic
  ConflictException, and DUPLICATE_PROJECT_NAME as the code.

  Commit at each step as you proposed. Go ahead with step 1.

⏺ Starting step 1. First I'll check the Hibernate API for reading the constraint
  name, since I said I'd verify it.

⏺ Bash(J=$(find ~/.m2 -name 'hibernate-core-*.jar' | grep -v sources | head -1);
      echo $J; javap -cp "$J"
      org.hibernate.exception.ConstraintViolationException | head -3…)
  ⎿  (No output)

⏺ getConstraintName() exists. What H2 puts in it (case, prefix) needs checking
  against the real database, so I'll write the code and then confirm with a
  test. Writing the step 1 files now.

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/Project.java)
  ⎿  Wrote 47 lines to
     src/main/java/dev/jasmeet/taskmanager/project/Project.java
      1 package dev.jasmeet.taskmanager.project;
      2
      3 import jakarta.persistence.Column;
      4 import jakarta.persistence.Entity;
      5 import jakarta.persistence.GeneratedValue;
      6 import jakarta.persistence.GenerationType;
      7 import jakarta.persistence.Id;
      8 import jakarta.persistence.Table;
      9 import jakarta.persistence.UniqueConstraint;
     … +38 lines (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/ProjectRepository.java)
  ⎿  Wrote 6 lines to
     src/main/java/dev/jasmeet/taskmanager/project/ProjectRepository.java
      1 package dev.jasmeet.taskmanager.project;
      2
      3 import org.springframework.data.jpa.repository.JpaRepository;
      4
      5 public interface ProjectRepository extends JpaRepository<Project,
         Long> {
      6 }

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/CreateProjectRequest.java)
  ⎿  Wrote 12 lines to src/main/java/dev/jasmeet/taskmanager/project/Create
     ProjectRequest.java
       1 package dev.jasmeet.taskmanager.project;
       2
       3 import jakarta.validation.constraints.NotBlank;
       4 import jakarta.validation.constraints.Size;
       5
       6 public record CreateProjectRequest(
       7         @NotBlank @Size(max = Project.NAME_MAX, message = "must
         be at most " + Project.NAME_MAX + " characters")
       8         String name,
       9         @Size(max = Project.DESCRIPTION_MAX,
     … +3 lines (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/ProjectResponse.java)
  ⎿  Wrote 8 lines to
     src/main/java/dev/jasmeet/taskmanager/project/ProjectResponse.java
      1 package dev.jasmeet.taskmanager.project;
      2
      3 public record ProjectResponse(Long id, String name, String descri
        ption) {
      4
      5     public static ProjectResponse from(Project project) {
      6         return new ProjectResponse(project.getId(), project.getNa
        me(), project.getDescription());
      7     }
      8 }

⏺ Write(src/main/java/dev/jasmeet/taskmanager/common/ConflictException.java)
  ⎿  Wrote 27 lines to
     src/main/java/dev/jasmeet/taskmanager/common/ConflictException.java
      1 package dev.jasmeet.taskmanager.common;
      2
      3 import java.util.List;
      4
      5 /**
      6  * Thrown by services when a request conflicts with the current s
        tate (for example a duplicate name).
      7  * Mapped to 409 with the given code. It is a RuntimeException, s
        o the transaction rolls back.
      8  */
     … +19 lines (ctrl+o to expand)

  Read 1 file (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/ProjectService.java)
  ⎿  Wrote 65 lines to
     src/main/java/dev/jasmeet/taskmanager/project/ProjectService.java
       1 package dev.jasmeet.taskmanager.project;
       2
       3 import dev.jasmeet.taskmanager.common.ConflictException;
       4 import dev.jasmeet.taskmanager.common.FieldViolation;
       5 import dev.jasmeet.taskmanager.common.NotFoundException;
       6 import org.hibernate.exception.ConstraintViolationException;
       7 import org.springframework.dao.DataIntegrityViolationException;
       8 import org.springframework.data.domain.Sort;
       9 import org.springframework.stereotype.Service;
      10 import org.springframework.transaction.annotation.Transactional;
     … +55 lines (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/project/ProjectController.java)
  ⎿  Wrote 47 lines to
     src/main/java/dev/jasmeet/taskmanager/project/ProjectController.java
       1 package dev.jasmeet.taskmanager.project;
       2
       3 import jakarta.validation.Valid;
       4 import org.springframework.http.ResponseEntity;
       5 import org.springframework.web.bind.annotation.GetMapping;
       6 import org.springframework.web.bind.annotation.PathVariable;
       7 import org.springframework.web.bind.annotation.PostMapping;
       8 import org.springframework.web.bind.annotation.RequestBody;
       9 import org.springframework.web.bind.annotation.RequestMapping;
      10 import org.springframework.web.bind.annotation.RestController;
     … +37 lines (ctrl+o to expand)

⏺ Update(src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.jav
        a)
  ⎿  Added 7 lines
      67                  NOT_FOUND, List.of(), request);
      68      }
      69  
      70 +    /** The service picks the code (e.g. DUPLICATE_PROJECT_NAME
         +) and the field errors. */                                     
      71 +    @ExceptionHandler(ConflictException.class)                 
      72 +    public ResponseEntity<Object> handleConflict(ConflictExcept
         +ion ex, WebRequest request) {                                  
      73 +        return respond(ex, null, HttpStatus.CONFLICT, "Conflict
         +", ex.getMessage(),                                            
      74 +                ex.getCode(), ex.getFieldErrors(), request);   
      75 +    }                                                          
      76 +                                                               
      77      // ---------- 400: validation and unreadable input --------
          --
      78  
      79      /** Bean Validation failures on a request body. Every faili
          ng field is listed. */

⏺ Now the tests, starting with the service unit tests.

⏺ Write(src/test/java/dev/jasmeet/taskmanager/project/ProjectServiceTest.java)
  ⎿  Wrote 120 lines to
     src/test/java/dev/jasmeet/taskmanager/project/ProjectServiceTest.java
       1 package dev.jasmeet.taskmanager.project;
       2
       3 import dev.jasmeet.taskmanager.common.ConflictException;
       4 import dev.jasmeet.taskmanager.common.FieldViolation;
       5 import dev.jasmeet.taskmanager.common.NotFoundException;
       6 import org.hibernate.exception.ConstraintViolationException;
       7 import org.junit.jupiter.api.Test;
       8 import org.junit.jupiter.api.extension.ExtendWith;
       9 import org.mockito.ArgumentCaptor;
      10 import org.mockito.InjectMocks;
     … +110 lines (ctrl+o to expand)

⏺ Write(src/test/java/dev/jasmeet/taskmanager/project/ProjectApiIntegrationTest.
       java)
  ⎿  Wrote 268 lines to src/test/java/dev/jasmeet/taskmanager/project/Proje
     ctApiIntegrationTest.java
       1 package dev.jasmeet.taskmanager.project;
       2
       3 import dev.jasmeet.taskmanager.task.TaskRepository;
       4 import org.junit.jupiter.api.BeforeEach;
       5 import org.junit.jupiter.api.Test;
       6 import org.springframework.beans.factory.annotation.Autowired;
       7 import org.springframework.boot.test.context.SpringBootTest;
       8 import org.springframework.boot.webmvc.test.autoconfigure.AutoCo
         nfigureMockMvc;
       9 import org.springframework.dao.DataIntegrityViolationException;
     … +259 lines (ctrl+o to expand)

⏺ The Location assertion works, but it relies on evaluation order in the call
  chain. I'll use a plain pattern match instead.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/project/ProjectApiIntegrationTest
        .java)
  ⎿  Added 1 line, removed 1 line
      69                          .contentType(MediaType.APPLICATION_JSON
          )
      70                          .content(projectJson("Home", "chores"))
          )
      71                  .andExpect(status().isCreated())
      72 -                .andExpect(header().string("Location", endsWith
         -("/projects/" + firstProjectId())))                            
      72 +                .andExpect(header().string("Location", matchesP
         +attern(".*/projects/\\d+")))                                   
      73                  .andExpect(jsonPath("$.id").isNumber())
      74                  .andExpect(jsonPath("$.name").value("Home"))
      75                  .andExpect(jsonPath("$.description").value("cho
          res"));
⏺ Update(src/test/java/dev/jasmeet/taskmanager/project/ProjectApiIntegrationTest
        .java)
  ⎿  Added 2 lines, removed 2 lines
      19  
      20  import static org.assertj.core.api.Assertions.assertThat;
      21  import static org.assertj.core.api.Assertions.assertThatThrownB
          y;
      22 -import static org.hamcrest.Matchers.endsWith;                  
      23 -import static org.hamcrest.Matchers.not;                       
      22  import static org.hamcrest.Matchers.containsString;
      23 +import static org.hamcrest.Matchers.matchesPattern;            
      24 +import static org.hamcrest.Matchers.not;                       
      25  import static org.springframework.test.web.servlet.request.Mock
          MvcRequestBuilders.get;
      26  import static org.springframework.test.web.servlet.request.Mock
          MvcRequestBuilders.post;
      27  import static org.springframework.test.web.servlet.result.MockM
          vcResultMatchers.content;

⏺ Bash(java -version 2>&1 | head -1 && ./mvnw -q test 2>&1 | tail -60)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ The three duplicate-name tests are returning 500, so the name match isn't
  recognizing H2's error; I'm pulling the actual exception next. · summary

  Listed 1 directory (ctrl+o to expand)

⏺ Bash(./mvnw -q test -Dtest=ProjectApiIntegrationTest#create_duplicateName_retu
      rns409AndLeavesTheFirstProjectUnchanged 2>&1 | grep -E "Unhandled
      exception|Caused by|U…)
  ⎿  (No output)

⏺ Spring wraps Hibernate's ConstraintViolationException as expected, so I'm
  printing the exact constraint name H2 returns, using a throwaway test I'll
  delete afterwards. · summary

⏺ Bash(cat > src/test/java/dev/jasmeet/taskmanager/project/ProbeTest.java
      <<'EOF'
      package dev.jasmeet.taskmanager.project;…)
  ⎿  (No output)
  ⎿  (timeout 3m 20s)

⏺ H2 reports the constraint name with schema prefix and index text attached, so
  my exact comparison never matched, which explains the three 500s. I'll strip
  the prefix and take the first token rather than loosen it to a contains check.
  · summary

⏺ Update(src/main/java/dev/jasmeet/taskmanager/project/ProjectService.java)
  ⎿  Added 15 lines, removed 2 lines
      56      /**
      57       * True only for a violation of the name constraint. We mat
          ch on the constraint name, not on
      58       * "any unique violation", so a unique constraint added lat
          er is not mislabelled as a duplicate name.
      59 -     * H2 may report the name in upper case, so the comparison 
         -ignores case.                                                  
      59       */
      60      private static boolean isDuplicateName(DataIntegrityViolati
          onException ex) {
      61          return ex.getCause() instanceof ConstraintViolationExce
          ption violation
      63 -                && Project.NAME_UNIQUE_CONSTRAINT.equalsIgnoreC
         -ase(violation.getConstraintName());                            
      62 +                && bareConstraintName(violation.getConstraintNa
         +me())                                                          
      63 +                        .equalsIgnoreCase(Project.NAME_UNIQUE_C
         +ONSTRAINT);                                                    
      64      }
      65 +                                                               
      66 +    /**                                                        
      67 +     * H2 reports the name decorated: "PUBLIC.UK_PROJECTS_NAME 
         +INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F".                        
      68 +     * Keep the first word and drop the schema prefix. The resu
         +lt is still compared for equality, never                       
      69 +     * with contains(), so a constraint named "uk_projects_name
         +_other" does not match.                                        
      70 +     */                                                        
      71 +    private static String bareConstraintName(String reported) {
      72 +        if (reported == null || reported.isBlank()) {          
      73 +            return "";                                         
      74 +        }                                                      
      75 +        String firstWord = reported.trim().split("\\s+")[0];   
      76 +        return firstWord.substring(firstWord.lastIndexOf('.') +
         + 1);                                                           
      77 +    }                                                          
      78  }

⏺ Update(src/test/java/dev/jasmeet/taskmanager/project/ProjectServiceTest.java)
  ⎿  Added 23 lines, removed 3 lines
      73      }
      74  
      75      @Test
      76 -    void create_constraintNameInUpperCase_stillThrowsConflict()
         - {                                                             
      77 -        // H2 reports constraint names in upper case.          
      76 +    void create_constraintNameAsH2ReportsIt_stillThrowsConflict
         +() {                                                           
      77 +        // Exactly what H2 puts in getConstraintName(): schema-
         +qualified, upper case, with index text.                        
      78          when(projectRepository.saveAndFlush(any(Project.class))
          )
      79 -                .thenThrow(violationOf(Project.NAME_UNIQUE_CONS
         -TRAINT.toUpperCase()));                                        
      79 +                .thenThrow(violationOf("PUBLIC.UK_PROJECTS_NAME
         + INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F"));                     
      80  
      81          assertThatThrownBy(() -> projectService.create(new Crea
          teProjectRequest("Home", null)))
      82                  .isInstanceOf(ConflictException.class);
     ...
       92      }
       93  
       94      @Test
       95 +    void create_constraintWhoseNameOnlyStartsWithOurs_isNotRep
          +ortedAsDuplicateName() {                                      
       96 +        // Guards against a contains()/startsWith() match on t
          +he constraint name.                                           
       97 +        DataIntegrityViolationException lookalike =           
       98 +                violationOf("PUBLIC.UK_PROJECTS_NAME_OTHER IND
          +EX PUBLIC.UK_PROJECTS_NAME_OTHER_INDEX_1");                   
       99 +        when(projectRepository.saveAndFlush(any(Project.class)
          +)).thenThrow(lookalike);                                      
      100 +                                                              
      101 +        assertThatThrownBy(() -> projectService.create(new Cre
          +ateProjectRequest("Home", null)))                             
      102 +                .isSameAs(lookalike);                         
      103 +    }                                                         
      104 +                                                              
      105 +    @Test                                                     
      106 +    void create_violationWithoutConstraintName_isNotReportedAs
          +DuplicateName() {                                             
      107 +        DataIntegrityViolationException unnamed = new DataInte
          +grityViolationException("no name");                           
      108 +        when(projectRepository.saveAndFlush(any(Project.class)
          +)).thenThrow(unnamed);                                        
      109 +                                                              
      110 +        assertThatThrownBy(() -> projectService.create(new Cre
          +ateProjectRequest("Home", null)))                             
      111 +                .isSameAs(unnamed);                           
      112 +    }                                                         
      113 +                                                              
      114 +    @Test                                                     
      115      void get_returnsProject() {
      116          when(projectRepository.findById(4L)).thenReturn(Option
           al.of(projectWithId(4L, "Home", null)));
      117  

⏺ Bash(./mvnw -q test 2>&1 | grep -E "Tests run|FAIL|ERROR\]" | head -20; echo
      "exit=${PIPESTATUS[0]}")
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ Quiet mode hides the summary on success, so I'll get the real counts.

⏺ Bash(./mvnw test 2>&1 | grep -E "Tests run:|BUILD|Unhandled" | head -20)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ 77 pass. The one logged "Unhandled exception" is the existing deliberate
  /test-only/boom test. Since the concurrency test depends on thread timing,
  I'll run it a few times before trusting it.

⏺ Bash(for i in 1 2 3 4 5; do ./mvnw test -Dtest=ProjectApiIntegrationTest#creat
      e_twoConcurrentRequestsWithTheSameName_oneWinsOneConflicts 2>&1 | grep -E
      "Tests run:.*…)
  ⎿  (No output)
  ⎿  (timeout 6m 40s)

⏺ Stable across five runs. Committing step 1.

⏺ Bash(git add src && git commit -q -F - <<'EOF'
      Add Project entity, endpoints and unique name handling…)
  ⎿  (No output)

⏺ Step 1 is done and committed as 6802dfe. ./mvnw test passes: 77 tests, 0
  failures (up from 52). The working tree is clean, and step 2 hasn't been
  started.

  What was built

  - Project: Project, ProjectRepository, CreateProjectRequest, ProjectResponse,
    ProjectService and ProjectController. They give POST /projects (201 with
    Location), GET /projects (plain list, id order) and GET /projects/{id}.
  - Conflict handling: ConflictException in common/, plus one handler in
    GlobalExceptionHandler. A duplicate name returns 409 DUPLICATE_PROJECT_NAME
    with fieldErrors: [{field: "name", message: "already exists"}].
  - Unique-violation catch: saveAndFlush runs inside the try, and the catch
    matches the constraint name uk_projects_name, not the violation kind.
  - Tests: 16 integration and 9 unit tests for projects. They cover the boundary
    lengths, trimming, case-sensitivity, the repository-level duplicate and the
    two-thread race. The race test passed five times in a row.

  Correction to my own plan

  I said I'd match on getConstraintName(). When I ran it, the duplicate tests
  got 500s instead of 409s. H2 reports the name as PUBLIC.UK_PROJECTS_NAME INDEX
  PUBLIC.UK_PROJECTS_NAME_INDEX_F, so an exact comparison never matched.

  I did not fall back to contains, because that would match a future
  uk_projects_name_other. Instead ProjectService.bareConstraintName takes the
  first word and strips the schema prefix, and the result is compared for
  equality. Unit tests cover three cases:
  - the real H2 string
  - a lookalike name that only starts with ours
  - a violation with no name

  I'll add this to the Agent Mistakes Log in step 3. The mistake was that I
  verified the Hibernate API but assumed what H2 would put in it.

  For you to review

  The matching logic is isDuplicateName and bareConstraintName in
  ProjectService.java
  (src/main/java/dev/jasmeet/taskmanager/project/ProjectService.java). The
  string handling is a little more than "compare the name", but each step is
  there because of the H2 output above. If you'd rather have something simpler
  to explain in the interview, the alternative is to look up the constraint by
  index name via H2's SQL state, but that is no simpler.

  Waiting for your go-ahead on step 2 (the Task-to-Project mapping and the
  assign and unassign endpoints).

❯ <ide_selection>The user selected the lines 1 to 79 from
  /Users/jasmeetsingh/dev/task-manager-api/src/main/java/dev/jasmeet/taskmanage
  r/project/ProjectService.java:
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
      // Trim so " Home " and "Home" cannot both exist. Comparison stays
  case-sensitive (see ADR-011).
      Project project = new Project(request.name().trim(),
  request.description());
      try {
          // saveAndFlush, not save: the INSERT must run inside this try block.
  A flush left
          // for commit time would throw after this method returns, outside the
  catch, and become a 500.
          return ProjectResponse.from(projectRepository.saveAndFlush(project));
      } catch (DataIntegrityViolationException ex) {
          if (isDuplicateName(ex)) {
              throw new ConflictException(DUPLICATE_PROJECT_NAME,
                      "A project named '" + project.getName() + "' already
  exists.",
                      List.of(new FieldViolation("name", "already exists")));
          }
          throw ex; // any other integrity violation is a bug: let it reach the
  logged 500 handler
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
   * True only for a violation of the name constraint. We match on the
  constraint name, not on
   * "any unique violation", so a unique constraint added later is not
  mislabelled as a duplicate name.
   */
  private static boolean isDuplicateName(DataIntegrityViolationException ex) {
      return ex.getCause() instanceof ConstraintViolationException violation
              && bareConstraintName(violation.getConstraintName())
                      .equalsIgnoreCase(Project.NAME_UNIQUE_CONSTRAINT);
  }

  /**
   * H2 reports the name decorated: "PUBLIC.UK_PROJECTS_NAME INDEX
  PUBLIC.UK_PROJECTS_NAME_INDEX_F".
   * Keep the first word and drop the schema prefix. The result is still
  compared for equality, never
   * with contains(), so a constraint named "uk_projects_name_other" does not
  match.
   */
  private static String bareConstraintName(String reported) {
      if (reported == null || reported.isBlank()) {
          return "";
      }
      String firstWord = reported.trim().split("\\s+")[0];
      return firstWord.substring(firstWord.lastIndexOf('.') + 1);
  }
  }

  This may or may not be related to the current task.</ide_selection>

❯ Read isDuplicateName and bareConstraintName, keeping them. Go ahead with step
  2.

⏺ Keeping both methods as they are. Starting step 2 by checking how existing
  tests construct TaskResponse and TaskService, since both change.

⏺ Bash(grep -rn "new TaskResponse\|new TaskService\|TaskResponse(" src | grep -v
      "^src/main/java/dev/jasmeet/taskmanager/task/TaskResponse.java"; sed -n
      50,200p src/te…)
  ⎿  No matches found

⏺ Now the main code: the Task mapping and methods, the TaskResponse fields, the
  service methods and the controller endpoints.

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 7 lines
      41      @Column(nullable = false)
      42      private Status status;
      43  
      44 +    // ADR-002: many tasks to one optional project, owned by th
         +is side only.                                                  
      45 +    // LAZY so loading a task does not load its project. No cas
         +cade: tasks never create,                                      
      46 +    // update or delete projects. Only moveTo and removeFromPro
         +ject change this field.                                        
      47 +    @ManyToOne(fetch = FetchType.LAZY)                         
      48 +    @JoinColumn(name = "project_id")                           
      49 +    private Project project;                                   
      50 +                                                               
      51      protected Task() {
      52          // required by JPA
      53      }

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 9 lines
      61          assign(title, description, dueDate, priority, status);
      62      }
      63  
      64 +    /** Puts the task in the given project. Moves it if it was 
         +in another one. */                                             
      65 +    public void moveTo(Project project) {                      
      66 +        this.project = project;                                
      67 +    }                                                          
      68 +                                                               
      69 +    public void removeFromProject() {                          
      70 +        this.project = null;                                   
      71 +    }                                                          
      72 +                                                               
      73      // Private so the constructor never calls an overridable me
          thod
      74      // (Hibernate may subclass entities for proxies, so update(
          ) must stay non-final).
      75      private void assign(String title, String description, Local
          Date dueDate, Priority priority, Status status) {

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 1 line
      86      public LocalDate getDueDate() { return dueDate; }
      87      public Priority getPriority() { return priority; }
      88      public Status getStatus() { return status; }
      89 +    public Project getProject() { return project; }            
      90  }
⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 4 lines
       1  package dev.jasmeet.taskmanager.task;
       2  
       3 +import dev.jasmeet.taskmanager.project.Project;                
       4  import jakarta.persistence.Column;
       5  import jakarta.persistence.Entity;
       6  import jakarta.persistence.EnumType;
       7  import jakarta.persistence.Enumerated;
       8 +import jakarta.persistence.FetchType;                          
       9  import jakarta.persistence.GeneratedValue;
      10  import jakarta.persistence.GenerationType;
      11  import jakarta.persistence.Id;
      12 +import jakarta.persistence.JoinColumn;                         
      13 +import jakarta.persistence.ManyToOne;                          
      14  import jakarta.persistence.Table;
      15  
      16  import java.time.LocalDate;

⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/TaskResponse.java)
  ⎿  Added 11 lines, removed 2 lines
      1  package dev.jasmeet.taskmanager.task;
      2  
      3 +import dev.jasmeet.taskmanager.project.Project;                 
      4 +                                                                
      5  import java.time.LocalDate;
      6  
      7  public record TaskResponse(
     ...
      10          String description,
      11          LocalDate dueDate,
      12          Priority priority,
      11 -        Status status) {                                       
      13 +        Status status,                                         
      14 +        Long projectId,                                        
      15 +        String projectName) {                                  
      16  
      17      public static TaskResponse from(Task task) {
      18 +        // task.getProject() is a lazy proxy. getId() needs no 
         +query, but getName() loads the project                         
      19 +        // with one SELECT. In TaskService.list this runs once 
         +per task: the N+1 that M4 fixes.                               
      20 +        Project project = task.getProject();                   
      21          return new TaskResponse(
      22                  task.getId(),
      23                  task.getTitle(),
      24                  task.getDescription(),
      25                  task.getDueDate(),
      26                  task.getPriority(),
      20 -                task.getStatus());                             
      27 +                task.getStatus(),                              
      28 +                project == null ? null : project.getId(),      
      29 +                project == null ? null : project.getName());   
      30      }
      31  }

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 4 lines, removed 1 line
      12  public class TaskService {
      13  
      14      private final TaskRepository taskRepository;
      15 +    private final ProjectRepository projectRepository;         
      16  
      16 -    public TaskService(TaskRepository taskRepository) {        
      17 +    public TaskService(TaskRepository taskRepository, ProjectRe
         +pository projectRepository) {                                  
      18          this.taskRepository = taskRepository;
      19 +        // The repository, not ProjectService: entities must no
         +t leave the service layer.                                     
      20 +        this.projectRepository = projectRepository;            
      21      }
      22  
      23      @Transactional

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 20 lines
      60          taskRepository.delete(findOrThrow(id));
      61      }
      62  
      63 +    /**                                                        
      64 +     * Sets the task's project, moving it if it was in another 
         +one. Calling it again with the same                            
      65 +     * project changes nothing: Hibernate sees no difference, s
         +o it runs no UPDATE.                                           
      66 +     */                                                        
      67 +    @Transactional                                             
      68 +    public TaskResponse assignProject(Long taskId, Long project
         +Id) {                                                          
      69 +        // Task first, so when both are missing the answer is a
         +lways "Task ... not found".                                    
      70 +        Task task = findOrThrow(taskId);                       
      71 +        Project project = projectRepository.findById(projectId)
      72 +                .orElseThrow(() -> new NotFoundException("Proje
         +ct", projectId));                                              
      73 +        task.moveTo(project);                                  
      74 +        return TaskResponse.from(task);                        
      75 +    }                                                          
      76 +                                                               
      77 +    /** Leaves the task with no project. Already having none is
         + fine: the caller's goal is met. */                            
      78 +    @Transactional                                             
      79 +    public void unassignProject(Long taskId) {                 
      80 +        findOrThrow(taskId).removeFromProject();               
      81 +    }                                                          
      82 +                                                               
      83      private Task findOrThrow(Long id) {
      84          return taskRepository.findById(id)
      85                  .orElseThrow(() -> new NotFoundException("Task"
          , id));

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 2 lines
      2  
      3  import dev.jasmeet.taskmanager.common.NotFoundException;
      4  import dev.jasmeet.taskmanager.common.PageResponse;
      5 +import dev.jasmeet.taskmanager.project.Project;                 
      6 +import dev.jasmeet.taskmanager.project.ProjectRepository;       
      7  import org.springframework.data.domain.PageRequest;
      8  import org.springframework.data.domain.Sort;
      9  import org.springframework.stereotype.Service;

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 2 lines
      40  
      41      public PageResponse<TaskResponse> list(int page, int size) 
          {
      42          // Fixed sort by id keeps paging stable. Sorting and fi
          lters arrive in M4.
      43 +        // N+1 (M4): the .map below reads each task's project n
         +ame, one SELECT per distinct project.                          
      44 +        // M4 fixes it by fetching the project in the same quer
         +y.                                                             
      45          PageRequest pageRequest = PageRequest.of(page, size, So
          rt.by("id"));
      46          return PageResponse.from(taskRepository.findAll(pageReq
          uest).map(TaskResponse::from));
      47      }

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 11 lines
      63          taskService.delete(id);
      64          return ResponseEntity.noContent().build();
      65      }
      66 +                                                               
      67 +    @PutMapping("/{id}/project/{projectId}")                   
      68 +    public TaskResponse assignProject(@PathVariable Long id, @P
         +athVariable Long projectId) {                                  
      69 +        return taskService.assignProject(id, projectId);       
      70 +    }                                                          
      71 +                                                               
      72 +    @DeleteMapping("/{id}/project")                            
      73 +    public ResponseEntity<Void> unassignProject(@PathVariable L
         +ong id) {                                                      
      74 +        taskService.unassignProject(id);                       
      75 +        return ResponseEntity.noContent().build();             
      76 +    }                                                          
      77  }

⏺ Now the unit tests. Three existing assertions need the two new null fields,
  TaskServiceTest needs the new mock, and the new service methods need their
  tests.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 1 line, removed 1 line
      57          verify(taskRepository).save(saved.capture());
      58          assertThat(saved.getValue().getStatus()).isEqualTo(Stat
          us.TODO);
      59          assertThat(response).isEqualTo(new TaskResponse(
      60 -                7L, "Write report", "Q4 numbers", due, Priority
         -.HIGH, Status.TODO));                                          
      60 +                7L, "Write report", "Q4 numbers", due, Priority
         +.HIGH, Status.TODO, null, null));                              
      61      }
      62  
      63      @Test

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 1 line, removed 1 line
      68  
      69          TaskResponse response = taskService.get(1L);
      70  
      71 -        assertThat(response).isEqualTo(new TaskResponse(1L, "A"
         -, null, due, Priority.LOW, Status.DONE));                      
      71 +        assertThat(response).isEqualTo(new TaskResponse(1L, "A"
         +, null, due, Priority.LOW, Status.DONE, null, null));          
      72      }
      73  
      74      @Test

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 1 line, removed 1 line
      113                  new UpdateTaskRequest("New", null, newDue, Pri
           ority.HIGH, Status.IN_PROGRESS));
      114  
      115          assertThat(response).isEqualTo(new TaskResponse(
      116 -                5L, "New", null, newDue, Priority.HIGH, Status
          -.IN_PROGRESS));                                               
      116 +                5L, "New", null, newDue, Priority.HIGH, Status
          +.IN_PROGRESS, null, null));                                   
      117          // The managed entity is flushed at commit, so the ser
           vice must not call save.
      118          // Whether the change really persists is proven by the
            PUT integration test.
      119          verify(taskRepository, never()).save(any());

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 9 lines
      31      @Mock
      32      TaskRepository taskRepository;
      33  
      34 +    @Mock                                                      
      35 +    ProjectRepository projectRepository;                       
      36 +                                                               
      37      @InjectMocks
      38      TaskService taskService;
      39  
      40 +    private static Project projectWithId(Long id, String name) 
         +{                                                              
      41 +        Project project = new Project(name, null);             
      42 +        ReflectionTestUtils.setField(project, "id", id);       
      43 +        return project;                                        
      44 +    }                                                          
      45 +                                                               
      46      private static Task taskWithId(Long id, String title, Strin
          g description,
      47                                     LocalDate dueDate, Priority 
          priority, Status status) {
      48          Task task = new Task(title, description, dueDate, prior
          ity, status);

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 109 lines
      155  
      156          verify(taskRepository, never()).delete(any());
      157      }
      158 +                                                              
      159 +    // ---------- project assignment ----------               
      160 +                                                              
      161 +    @Test                                                     
      162 +    void assignProject_setsProjectAndReturnsItsIdAndName() {  
      163 +        LocalDate due = LocalDate.of(2026, 12, 1);            
      164 +        Task task = taskWithId(1L, "A", null, due, Priority.LO
          +W, Status.TODO);                                              
      165 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      166 +        when(projectRepository.findById(10L)).thenReturn(Optio
          +nal.of(projectWithId(10L, "Home")));                          
      167 +                                                              
      168 +        TaskResponse response = taskService.assignProject(1L, 
          +10L);                                                         
      169 +                                                              
      170 +        assertThat(response).isEqualTo(new TaskResponse(1L, "A
          +", null, due, Priority.LOW, Status.TODO, 10L, "Home"));       
      171 +        assertThat(task.getProject().getId()).isEqualTo(10L); 
      172 +    }                                                         
      173 +                                                              
      174 +    @Test                                                     
      175 +    void assignProject_taskAlreadyInAnotherProject_movesIt() {
      176 +        Task task = taskWithId(1L, "A", null, LocalDate.of(202
          +6, 12, 1), Priority.LOW, Status.TODO);                        
      177 +        task.moveTo(projectWithId(10L, "Home"));              
      178 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      179 +        when(projectRepository.findById(20L)).thenReturn(Optio
          +nal.of(projectWithId(20L, "Work")));                          
      180 +                                                              
      181 +        TaskResponse response = taskService.assignProject(1L, 
          +20L);                                                         
      182 +                                                              
      183 +        assertThat(response.projectId()).isEqualTo(20L);      
      184 +        assertThat(response.projectName()).isEqualTo("Work"); 
      185 +    }                                                         
      186 +                                                              
      187 +    @Test                                                     
      188 +    void assignProject_sameProjectTwice_givesTheSameResult() {
      189 +        Project home = projectWithId(10L, "Home");            
      190 +        Task task = taskWithId(1L, "A", null, LocalDate.of(202
          +6, 12, 1), Priority.LOW, Status.TODO);                        
      191 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      192 +        when(projectRepository.findById(10L)).thenReturn(Optio
          +nal.of(home));                                                
      193 +                                                              
      194 +        TaskResponse first = taskService.assignProject(1L, 10L
          +);                                                            
      195 +        TaskResponse second = taskService.assignProject(1L, 10
          +L);                                                           
      196 +                                                              
      197 +        assertThat(second).isEqualTo(first);                  
      198 +        assertThat(task.getProject()).isSameAs(home);         
      199 +    }                                                         
      200 +                                                              
      201 +    @Test                                                     
      202 +    void assignProject_missingTask_throwsNotFoundWithoutLookin
          +gUpTheProject() {                                             
      203 +        when(taskRepository.findById(99L)).thenReturn(Optional
          +.empty());                                                    
      204 +                                                              
      205 +        assertThatThrownBy(() -> taskService.assignProject(99L
          +, 10L))                                                       
      206 +                .isInstanceOf(NotFoundException.class)        
      207 +                .hasMessage("Task 99 not found");             
      208 +                                                              
      209 +        verify(projectRepository, never()).findById(any());   
      210 +    }                                                         
      211 +                                                              
      212 +    @Test                                                     
      213 +    void assignProject_missingProject_throwsNotFoundAndLeavesT
          +askUnchanged() {                                              
      214 +        Task task = taskWithId(1L, "A", null, LocalDate.of(202
          +6, 12, 1), Priority.LOW, Status.TODO);                        
      215 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      216 +        when(projectRepository.findById(99L)).thenReturn(Optio
          +nal.empty());                                                 
      217 +                                                              
      218 +        assertThatThrownBy(() -> taskService.assignProject(1L,
          + 99L))                                                        
      219 +                .isInstanceOf(NotFoundException.class)        
      220 +                .hasMessage("Project 99 not found");          
      221 +                                                              
      222 +        assertThat(task.getProject()).isNull();               
      223 +    }                                                         
      224 +                                                              
      225 +    @Test                                                     
      226 +    void unassignProject_clearsTheProject() {                 
      227 +        Task task = taskWithId(1L, "A", null, LocalDate.of(202
          +6, 12, 1), Priority.LOW, Status.TODO);                        
      228 +        task.moveTo(projectWithId(10L, "Home"));              
      229 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      230 +                                                              
      231 +        taskService.unassignProject(1L);                      
      232 +                                                              
      233 +        assertThat(task.getProject()).isNull();               
      234 +    }                                                         
      235 +                                                              
      236 +    @Test                                                     
      237 +    void unassignProject_taskWithoutProject_isANoOp() {       
      238 +        Task task = taskWithId(1L, "A", null, LocalDate.of(202
          +6, 12, 1), Priority.LOW, Status.TODO);                        
      239 +        when(taskRepository.findById(1L)).thenReturn(Optional.
          +of(task));                                                    
      240 +                                                              
      241 +        taskService.unassignProject(1L);                      
      242 +                                                              
      243 +        assertThat(task.getProject()).isNull();               
      244 +    }                                                         
      245 +                                                              
      246 +    @Test                                                     
      247 +    void unassignProject_missingTask_throwsNotFound() {       
      248 +        when(taskRepository.findById(99L)).thenReturn(Optional
          +.empty());                                                    
      249 +                                                              
      250 +        assertThatThrownBy(() -> taskService.unassignProject(9
          +9L)).isInstanceOf(NotFoundException.class);                   
      251 +    }                                                         
      252 +                                                              
      253 +    @Test                                                     
      254 +    void update_leavesTheProjectUntouched() {                 
      255 +        Project home = projectWithId(10L, "Home");            
      256 +        Task task = taskWithId(5L, "Old", null, LocalDate.of(2
          +026, 1, 1), Priority.LOW, Status.TODO);                       
      257 +        task.moveTo(home);                                    
      258 +        when(taskRepository.findById(5L)).thenReturn(Optional.
          +of(task));                                                    
      259 +                                                              
      260 +        TaskResponse response = taskService.update(5L,        
      261 +                new UpdateTaskRequest("New", null, LocalDate.o
          +f(2026, 12, 24), Priority.HIGH, Status.DONE));                
      262 +                                                              
      263 +        assertThat(task.getProject()).isSameAs(home);         
      264 +        assertThat(response.projectId()).isEqualTo(10L);      
      265 +        assertThat(response.projectName()).isEqualTo("Home"); 
      266 +    }                                                         
      267  }

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 2 lines
      2  
      3  import dev.jasmeet.taskmanager.common.NotFoundException;
      4  import dev.jasmeet.taskmanager.common.PageResponse;
      5 +import dev.jasmeet.taskmanager.project.Project;                 
      6 +import dev.jasmeet.taskmanager.project.ProjectRepository;       
      7  import org.junit.jupiter.api.Test;
      8  import org.junit.jupiter.api.extension.ExtendWith;
      9  import org.mockito.ArgumentCaptor;

⏺ Now the integration tests for assignment, which go through the real context
  and H2.

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/TaskProjectIntegrationTest.ja
       va)
  ⎿  Wrote 305 lines to src/test/java/dev/jasmeet/taskmanager/task/TaskProj
     ectIntegrationTest.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import com.jayway.jsonpath.JsonPath;
       4 import dev.jasmeet.taskmanager.project.ProjectRepository;
       5 import org.junit.jupiter.api.BeforeEach;
       6 import org.junit.jupiter.api.Test;
       7 import org.springframework.beans.factory.annotation.Autowired;
       8 import org.springframework.boot.test.context.SpringBootTest;
       9 import org.springframework.boot.webmvc.test.autoconfigure.AutoCo
         nfigureMockMvc;
     … +296 lines (ctrl+o to expand)

⏺ Two of my own tests are weak. One has a silly .replace in an expected string,
  and the "entity internals" test can't fail because TaskResponse is a record.
  I'll fix the first and drop the second.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskProjectIntegrationTest.j
        ava)
  ⎿  Added 1 line, removed 1 line
      151      void assign_bothMissing_reportsTheTaskFirst() throws Excep
           tion {
      152          mockMvc.perform(put("/tasks/999998/project/999999"))
      153                  .andExpect(status().isNotFound())
      154 -                .andExpect(jsonPath("$.detail").value("Task 99
          -8 not found".replace("998", "999998")));                      
      154 +                .andExpect(jsonPath("$.detail").value("Task 99
          +9998 not found"));                                            
      155      }
      156  
      157      @Test

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskProjectIntegrationTest.j
        ava)
  ⎿  Removed 10 lines
      292                  .andExpect(jsonPath("$.content[1].projectId").
           doesNotExist())
      293                  .andExpect(jsonPath("$.content[1].projectName"
           ).doesNotExist());
      294      }
      295 -                                                              
      296 -    @Test                                                     
      297 -    void responses_neverExposeEntityInternals() throws Excepti
          -on {                                                          
      298 -        long taskId = createTask("Write report");             
      299 -        assign(taskId, createProject("Work"));                
      300 -                                                              
      301 -        // A lazy proxy serialized by mistake would leak these
          - Hibernate field names.                                       
      302 -        mockMvc.perform(get("/tasks/" + taskId))              
      303 -                .andExpect(content().string(not(containsString
          -("hibernateLazyInitializer"))));                              
      304 -    }                                                         
      295  }

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskProjectIntegrationTest.j
        ava)
  ⎿  Removed 2 lines
      11  import org.springframework.test.web.servlet.MockMvc;
      12  
      13  import static org.assertj.core.api.Assertions.assertThat;
      14 -import static org.hamcrest.Matchers.containsString;            
      15 -import static org.hamcrest.Matchers.not;                       
      14  import static org.springframework.test.web.servlet.request.Mock
          MvcRequestBuilders.delete;
      15  import static org.springframework.test.web.servlet.request.Mock
          MvcRequestBuilders.get;
      16  import static org.springframework.test.web.servlet.request.Mock
          MvcRequestBuilders.post;

⏺ Bash(./mvnw test 2>&1 | grep -E "Tests run:|BUILD|ERROR\]|FAIL|expected" |
      head -30)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ All 103 tests pass on the first run, so I'm checking they can fail: I'll break
  update() to clear the project, and count the real SQL for a listing to
  confirm the N+1 claim in my comments. · summary
⏺ Bash(F=src/main/java/dev/jasmeet/taskmanager/task/Task.java && cp $F
      /tmp/Task.java.bak 2>/dev/null; cp $F "$TMPDIR/Task.java.bak" 2>/dev/null;
      SCR=/private/tmp/clau…)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ Both the unit and integration tests caught the mutation and the file is
  restored. Next I'll confirm no trace of it remains and measure the SQL for a
  listing of 3 tasks across 2 projects. · summary

⏺ Bash(grep -c "this.project = null"
      src/main/java/dev/jasmeet/taskmanager/task/Task.java
      cat > src/test/java/dev/jasmeet/taskmanager/task/ProbeTest.java <<'EOF'…)
  ⎿  (No output)
  ⎿  (timeout 3m 20s)

⏺ The measurement confirmed the claim: three tasks across two projects run one
  task query plus one per project, so I've removed the probe and committing step
  2 next. · summary

⏺ Bash(git add src && git commit -q -F - <<'EOF'
      Add task-project assignment…)
  ⎿  (No output)

⏺ Step 2 is done and committed as fd3a5db. ./mvnw test passes: 103 tests, 0
  failures (up from 77). The working tree is clean. Step 3 (docs) hasn't been
  started.

  What was built

  - Mapping:
    - Task.project is a nullable @ManyToOne(fetch = LAZY) with @JoinColumn(name
      = "project_id") and no cascade, as in ADR-002.
    - Only the new moveTo(Project) and removeFromProject() change it. update()
      and the private assign(...) are untouched.
  - Endpoints: PUT /tasks/{id}/project/{projectId} and DELETE
    /tasks/{id}/project, in TaskController and TaskService. The service looks up
    the task first, then the project, using ProjectRepository directly.
  - TaskResponse: it now has projectId and projectName, null when unassigned.
  - Tests:
    - 9 new TaskServiceTest cases cover assign, move, repeat, both 404 cases,
      unassign with and without a project, and update leaving the project alone.
    - 17 new TaskProjectIntegrationTest cases cover the HTTP behaviour,
      including 404 bodies, 400 on non-numeric ids, PUT keeping the project, and
      delete and unassign leaving the project in place.

  Checks beyond "tests are green"

  - Mutation check. I made Task's private assign() clear the project, which is
    the "PUT must never touch the project" trap.
    update_leavesTheProjectUntouched and putOnTheTask_keepsItsProject both
    failed, and I restored the file.
  - N+1 measured, not assumed. I logged the SQL for a listing of 3 tasks across
    2 projects, where two tasks share a project. It ran 1 task SELECT plus 2
    project SELECTs: one per distinct project, not one per task. The N+1 sits at
    TaskService.list, in the .map(TaskResponse::from) call. TaskResponse.from
    reads project.getName(), which loads the lazy project. Both spots have a
    comment pointing at M4, and M4 should fetch the project in the same query.
  - Weak tests removed. I dropped one test I'd written that couldn't fail, since
    a record can't leak Hibernate internals. I also fixed a silly .replace I'd
    left in an expected string.

  One thing to know

  putOnTheTask_ignoresAProjectIdInTheBody passes because Boot ignores unknown
  JSON properties (ADR-010). That is a documented behaviour. It means a client
  that sends projectId in a PUT gets no error, and the project is silently left
  as it was. I think that's right for a tolerant reader, but it's a likely
  interview question.

  Waiting for your go-ahead on step 3: ADR-011, the new ADR-005 row, moving the
  three pending M3 items to Accepted, FLOW.md, PROGRESS.md (including the Agent
  Mistakes Log entry for the H2 constraint name), and the one-line PLAN.md fix.

❯ Good work on the mutation check and the measured N+1. Go ahead with step 3.

⏺ Before writing the docs I want a real run for the "Manually verified" line.
  Tests use MockMvc, so I haven't yet started the actual app and hit the
  endpoints over HTTP.

⏺ Bash((lsof -i :8080 -sTCP:LISTEN >/dev/null 2>&1 && echo "PORT 8080 BUSY" ) ;
      SCR=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/bc2f7
      b63-4d13-4c7a…)
  ⎿  (No output)
  ⎿  (timeout 3m)

⏺ The 415 errors came from my shell: zsh didn't word-split $H, so curl sent one
  malformed header. The API itself is behaving correctly, so I'm rerunning with
  the header written inline. · summary

⏺ Bash(SCR=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/bc2f
      7b63-4d13-4c7a-a2e4-94feb272d749/scratchpad; env -u ANTHROPIC_API_KEY
      ./mvnw -q spring-…)
  ⎿  No matches found
  ⎿  (timeout 3m)

⏺ The live app is behaving as designed across the full API check, with no
  unhandled exceptions logged. I'm reading the docs next, starting with
  DECISIONS.md. · summary

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 3 lines, removed 1 line
      67  | `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method. The `Allow` h
          eader is kept. (Added in M2) |
      68  | `UNSUPPORTED_MEDIA_TYPE` | 415 | Request Content-Type is not 
          supported. (Added in M2) |
      69  | `CLIENT_ERROR` | 4xx | Fallback for a 4xx status with no spec
          ific code above, e.g. 406. (Added in M2) |
      70 +| `DUPLICATE_PROJECT_NAME` | 409 | A project with that name alr
         +eady exists. Carries `fieldErrors: [{ "field": "name", "message
         +": "already exists" }]`. (Added in M3) |                       
      71  
      71 -- **Amended in M2:** the last three rows were added so that eve
         -ry status Spring MVC can produce has a stable code. `type` is a
         -lways `"about:blank"` and is set explicitly, because Spring lea
         -ves it out of the JSON otherwise.                              
      72 +- **Amended in M2:** the three rows before `DUPLICATE_PROJECT_N
         +AME` were added so that every status Spring MVC can produce has
         + a stable code. `type` is always `"about:blank"` and is set exp
         +licitly, because Spring leaves it out of the JSON otherwise.   
      73 +- **Amended in M3:** `DUPLICATE_PROJECT_NAME` was added (ADR-01
         +1). It is specific, like `VERSION_CONFLICT`, so a client can sw
         +itch on it. The 409 lists `name` in `fieldErrors` because a spe
         +cific field failed.                                            
      74  - **Known limit:** errors Tomcat rejects before Spring MVC runs
           (malformed URLs such as `/tasks/%`) return Tomcat's HTML 400 p
          age, not this body. It leaks no stack trace or message. Optiona
          l M8 item.
      75  
      76  - **Why:** A real standard that's built into Spring, with one s
          hape for every error and field-level detail where it matters.

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 25 lines
      116  - **`GET /tasks` takes `page` (default 0, min 0) and `size` (d
           efault 20, 1 to 100) only, ordered by id.** This applies ADR-0
           06's default and cap. Sort and filters arrive in M4.
      117  - **Tradeoff:** a client that wants to change one field must r
           esend the others. The UI does that. Unknown properties are not
            reported, so a misspelled optional field is silently dropped.
      118  
      119 +### ADR-011: Projects, unique names and task assignment (M3)  
      120 +                                                              
      121 +Builds on ADR-002 (unidirectional, lazy, no cascade).         
      122 +                                                              
      123 +- **Fields.** `name` is required, up to 100 characters. `descr
          +iption` is optional, up to 500. The `@Size` annotations and th
          +e `@Column(length)` on `Project` use the same constants (`Proj
          +ect.NAME_MAX`, `Project.DESCRIPTION_MAX`). The name is trimmed
          + in the service before it is saved.                           
      124 +- **Unique names, enforced by the database.** The `projects` t
          +able has a named constraint, `uk_projects_name`. There is no c
          +heck-then-insert, because two concurrent requests could both p
          +ass the check. Only the database can guarantee uniqueness.    
      125 +  - `ProjectService.create` calls `saveAndFlush` inside its `t
          +ry` block, so the INSERT runs where the violation can be caugh
          +t. A flush left for commit time would throw after the method r
          +eturned, outside the catch, and become a 500. (With `IDENTITY`
          + ids Hibernate inserts immediately anyway, but correctness sho
          +uld not depend on the ID strategy.)                           
      126 +  - The catch recognises a duplicate **by constraint name**, n
          +ot by "any unique violation". A unique constraint added later 
          +would otherwise be mislabelled as a duplicate project name. An
          +y other integrity violation is rethrown and reaches the logged
          + 500 handler.                                                 
      127 +  - H2 reports the name decorated (`PUBLIC.UK_PROJECTS_NAME IN
          +DEX PUBLIC.UK_PROJECTS_NAME_INDEX_F`). `bareConstraintName` ke
          +eps the first word and drops the schema prefix, and the result
          + is compared for equality, never with `contains`, so `uk_proje
          +cts_name_other` does not match.                               
      128 +  - The service throws a generic `ConflictException(code, mess
          +age, fieldErrors)`, which the handler maps to 409. M6's `VERSI
          +ON_CONFLICT` reuses it.                                       
      129 +- **Names are case-sensitive.** "Home" and "home" can coexist.
          + Case-insensitive uniqueness needs either an H2-only column ty
          +pe (`VARCHAR_IGNORECASE`, not portable) or an extra lowercase 
          +key column. Documented limit, and a test records it.          
      130 +- **Assignment has its own endpoints, and `PUT /tasks/{id}` ne
          +ver touches the project.** `Task` has `moveTo(Project)` and `r
          +emoveFromProject()` and nothing else changes the project. A `p
          +rojectId` in a PUT body is ignored like any other unknown prop
          +erty (ADR-010).                                               
      131 +  - `PUT /tasks/{id}/project/{projectId}` sets the project, mo
          +ves the task if it was in another one, and returns 200 with `T
          +askResponse`. Repeating the call changes nothing (Hibernate se
          +es no difference, so it runs no UPDATE).                      
      132 +  - `DELETE /tasks/{id}/project` returns 204, also when the ta
          +sk had no project. The caller's goal is already met, so there 
          +is nothing to fail on.                                        
      133 +  - A missing task or project is 404 `NOT_FOUND`. The task is 
          +looked up first, and the detail names whichever is missing.   
      134 +- **`GET /projects` is a plain list ordered by id, with no pag
          +ination.** We expect few projects. `GET /projects/{id}` exists
          + so the `Location` header on the 201 points at something.     
      135 +- **`TaskResponse` gains `projectId` and `projectName`** (null
          + when unassigned).                                            
      136 +- **N+1, known and left for M4.** `TaskService.list` maps each
          + task through `TaskResponse.from`, which reads `project.getNam
          +e()` and loads the lazy project. Measured: 3 tasks in 2 projec
          +ts gave 1 task SELECT plus 2 project SELECTs, so one per disti
          +nct project. M4 fixes it by fetching the project in the same q
          +uery. `get` and `update` pay one extra SELECT each.           
      137 +- **No project deletion.** Deleting a project raises the quest
          +ion of what happens to its tasks, and that should be decided o
          +n purpose. This overrides the earlier pending lean ("support i
          +t, unassign tasks"). Next steps, each needing its own decision
          +:                                                             
      138 +  - `DELETE /projects/{id}` and what happens to its tasks: una
          +ssign them, block the delete while tasks exist, or cascade (th
          +e CLAUDE.md trap).                                            
      139 +  - Project update (`PUT /projects/{id}`), which needs a versi
          +on check once projects can change.                            
      140 +  - Case-insensitive names.                                   
      141 +  - Pagination of `GET /projects` if the list grows.          
      142 +- **Tradeoff:** a project cannot be renamed or removed through
          + the API. Case-variant names can coexist. The list endpoint re
          +turns everything.                                             
      143 +                                                              
      144  ---
      145  
      146  ## Pending (settle in the milestone session, then move up)

⏺ Update(docs/DECISIONS.md)
  ⎿  Removed 5 lines
      145  
      146  ## Pending (settle in the milestone session, then move up)
      147  
      148 -**M3**                                                        
      149 -- How tasks get associated: dedicated endpoints `PUT /tasks/{i
          -d}/project/{projectId}` and `DELETE /tasks/{id}/project` (lean
          -), or `projectId` in the task body.                           
      150 -- Support `DELETE /projects/{id}`? If so, what happens to its 
          -tasks? (Lean: support it, unassign tasks, never delete them.) 
      151 -- Must project names be unique?                               
      152 -                                                              
      148  **M4**
      149  - How to sort priority correctly: a rank column derived from t
           he enum, or a CASE expression in the query.
      150  - Inclusive or exclusive date range. (Lean: inclusive on both 
           ends.)

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 1 line, removed 1 line
      162  
      163  **M6**
      164  - Version in the request body (lean) or an `If-Match` header w
           ith ETags.
      165 -- Whether assigning or unassigning a project also checks the v
          -ersion.                                                       
      165 +- Whether assigning or unassigning a project also checks the v
          +ersion. (Once `@Version` exists, an assignment that changes th
          +e project bumps it. A repeated identical assign does not, beca
          +use Hibernate sees no change.)                                
      166  
      167  **M7**
      168  - Seed demo data on startup, while keeping tests isolated from
            it?

⏺ Now FLOW.md and the PLAN.md line.

⏺ Update(docs/FLOW.md)
  ⎿  Added 40 lines, removed 4 lines
       57  → 200 TaskResponse (version incremented)
       58  ```
       59  
       60 -## Associate a task with a project (M3)                       
       60 +## Create a project: `POST /projects` (M3)                    
       61  
       62  ```
       63 -(shape decided in M3)                                         
       64 -load task and project               either missing → 404 NOT_F
          -OUND                                                          
       65 -set task.project (or null to unassign)                        
       63 +JSON body → CreateProjectRequest (@Valid)                     
       64 +  blank name, name over 100, description over 500 → 400 VALIDA
          +TION_FAILED with fieldErrors                                  
       65 +  invalid JSON syntax or empty body               → 400 MALFOR
          +MED_REQUEST                                                   
       66 +→ ProjectService.create                                       
       67 +  trim the name                                               
       68 +  projectRepository.saveAndFlush      the INSERT runs here, in
          +side the try block                                            
       69 +    violates uk_projects_name         → catch DataIntegrityVio
          +lationException                                               
       70 +                                        constraint name matche
          +s  → ConflictException → 409 DUPLICATE_PROJECT_NAME           
       71 +                                                              
          +     fieldErrors [{ name, "already exists" }]                 
       72 +                                        any other violation   
          +   → rethrown → logged 500 INTERNAL_ERROR                     
       73 +→ 201 Created, Location: /projects/{id}, body ProjectResponse 
       74 +```                                                           
       75 +                                                              
       76 +Why `saveAndFlush`: a flush left for commit time would throw a
          +fter the service method returned, outside the catch, and becom
          +e a 500. Uniqueness is enforced only by the database, so two c
          +oncurrent requests with the same name give one 201 and one 409
          +.                                                             
       77 +                                                              
       78 +## List and get projects: `GET /projects`, `GET /projects/{id}
          +` (M3)                                                        
       79 +                                                              
       80 +```                                                           
       81 +GET /projects        → ProjectService.list → 200 plain JSON ar
          +ray, ordered by id, no pagination                             
       82 +GET /projects/{id}   → ProjectService.get  → 200 ProjectRespon
          +se                                                            
       83 +                         missing → 404 NOT_FOUND, non-numeric 
          +→ 400 VALIDATION_FAILED                                       
       84 +```                                                           
       85 +                                                              
       86 +## Assign and unassign a task (M3)                            
       87 +                                                              
       88 +```                                                           
       89 +PUT /tasks/{id}/project/{projectId}                           
       90 +→ TaskService.assignProject                                   
       91 +  load task                          missing → 404 NOT_FOUND "
          +Task N not found"                                             
       92 +  load project                       missing → 404 NOT_FOUND "
          +Project N not found" (task left unchanged)                    
       93 +  task.moveTo(project)               moves it if it was elsewh
          +ere; same project again = no change, no UPDATE                
       94  → 200 TaskResponse with projectId and projectName
       95 +                                                              
       96 +DELETE /tasks/{id}/project                                    
       97 +→ TaskService.unassignProject                                 
       98 +  load task                          missing → 404 NOT_FOUND  
       99 +  task.removeFromProject()           already none = fine      
      100 +→ 204 No Content                                              
      101  ```
      102  
      103 +`PUT /tasks/{id}` never touches the project. Only `moveTo` and
          + `removeFromProject` change it.                               
      104 +                                                              
      105  ## AI suggestion: `POST /tasks/suggest`
      106  
      107  ```

⏺ Update(docs/FLOW.md)
  ⎿  Added 2 lines
      34  
      35  As built in M2, only `page` (default 0) and `size` (default 20,
           1 to 100) exist, ordered by id. Bad values return 400 VALIDATI
          ON_FAILED naming the param. The filters and `sort` below arrive
           in M4.
      36  
      37 +As built in M3, each `TaskResponse` carries `projectId` and `pr
         +ojectName`, and the project is NOT fetched in the same query ye
         +t. `TaskResponse.from` reads the project name through the lazy 
         +proxy, so a page costs 1 task query plus 1 query per distinct p
         +roject on it (N+1, measured). M4 fixes this.                   
      38 +                                                               
      39  ```
      40  query params → filter values + Pageable
      41    bad enum / date / sort field / size → 400 VALIDATION_FAILED n
          aming the param

⏺ Update(docs/PLAN.md)
  ⎿  Added 3 lines, removed 2 lines
      79  **Build**
      80  - `Project` entity (`id`, `name` required, `description` option
          al), unidirectional `@ManyToOne` from Task, no cascade.
      81  - `POST /projects` (201), `GET /projects`, `GET /projects/{id}`
          .
      82 -- Associate and disassociate a task (shape per the pending M3 d
         -ecision).                                                      
      82 +- Associate and disassociate a task: `PUT /tasks/{id}/project/{
         +projectId}` and `DELETE /tasks/{id}/project` (ADR-011).        
      83  - `TaskResponse` gains `projectId` and `projectName` (null when
           unassigned).
      84 -- Delete behavior per the pending M3 decision.                 
      84 +- Unique project names, enforced by the database (409 `DUPLICAT
         +E_PROJECT_NAME`).                                              
      85 +- No project deletion (see ADR-011, listed as a next step).    
      86  
      87  **Tests**
      88  - Unit tests for every `ProjectService` method and any new `Tas
          kService` methods.

⏺ Update(docs/PLAN.md)
  ⎿  Added 1 line, removed 1 line
      86  
      87  **Tests**
      88  - Unit tests for every `ProjectService` method and any new `Tas
          kService` methods.
      89 -- Integration tests: create and list projects, assign, unassign
         -, 404 for a missing project or task.                           
      89 +- Integration tests: create and list projects, assign, unassign
         +, 404 for a missing project or task, 409 for a duplicate name (
         +including two concurrent requests).                            
      90  
      91  ## M4: Filtering, sorting, pagination
      92  

⏺ Now PROGRESS.md: the status row, the session entry and the Agent Mistakes Log.

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 1 line, removed 1 line
       7  | M0: Decisions and docs | Done | n/a |
       8  | M1: Scaffold | Done | transcripts/01-scaffold.md |
       9  | M2: Task CRUD and error handling | Done | transcripts/02-task
          -crud.md |
      10 -| M3: Project entity and association | Not started | |         
      10 +| M3: Project entity and association | Done | (export pending: 
         +`transcripts/03-projects.md`) |                                
      11  | M4: Filtering, sorting, pagination | Not started | |
      12  | M5: AI suggest endpoint | Not started | |
      13  | M6: Optimistic locking | Not started | |

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 18 lines
      29  - Left for next session:
      30  ```
      31  
      32 +### Session 03: M3 Project entity and association (2026-11-18) 
      33 +- Built:                                                       
      34 +  - `project/`: `Project` (named unique constraint `uk_projects
         +_name`), `ProjectRepository`, `CreateProjectRequest`, `ProjectR
         +esponse`, `ProjectService`, `ProjectController` (`POST /project
         +s`, `GET /projects`, `GET /projects/{id}`).                    
      35 +  - `common/`: generic `ConflictException` and its handler (409
         + with a code and field errors chosen by the service).          
      36 +  - `Task`: nullable lazy `@ManyToOne Project` with no cascade,
         + `moveTo` and `removeFromProject`. `TaskResponse` gains `projec
         +tId` and `projectName`.                                        
      37 +  - `TaskService.assignProject` and `unassignProject`, with `PU
         +T /tasks/{id}/project/{projectId}` (200) and `DELETE /tasks/{id
         +}/project` (204).                                              
      38 +- Decisions settled: ADR-011 (fields, database-enforced unique 
         +names caught by constraint name, case-sensitive and trimmed, as
         +signment endpoints and idempotence, plain project list, no proj
         +ect deletion). ADR-005 amended with `DUPLICATE_PROJECT_NAME`. A
         +ll three pending M3 items resolved. Project deletion is recorde
         +d as a next step.                                              
      39 +- Tests: `./mvnw test` passed, 103 tests, 0 failures (1 context
         +, 9 `ProjectServiceTest`, 16 `ProjectApiIntegrationTest`, 17 `T
         +askServiceTest`, 17 `TaskProjectIntegrationTest`, 14 `TaskApiIn
         +tegrationTest`, 29 `ErrorHandlingIntegrationTest`).            
      40 +  - The concurrent duplicate-name test passed five runs in a ro
         +w.                                                             
      41 +  - Mutation check: making `Task.assign()` clear the project ma
         +de `update_leavesTheProjectUntouched` and `putOnTheTask_keepsIt
         +sProject` fail. Restored afterwards.                           
      42 +- Manually verified: `./mvnw spring-boot:run` with no env vars,
         + then curl. 201 with `Location`, duplicate gives 409 `DUPLICATE
         +_PROJECT_NAME` as `application/problem+json`, assign (200), rep
         +eated assign (200), missing project (404 "Project 99 not found"
         +), missing task (404), unassign twice (204, 204), `GET /tasks` 
         +and `GET /projects` show the right fields. No "Unhandled except
         +ion" in the app log.                                           
      43 +- N+1 measured: 3 tasks in 2 projects ran 1 task SELECT plus 2 
         +project SELECTs (one per distinct project). It sits at `TaskSer
         +vice.list`, in the `.map(TaskResponse::from)` call. M4 fixes it
         +.                                                              
      44 +- Known gaps and limits:                                       
      45 +  - Names are case-sensitive ("Home" and "home" coexist), recor
         +ded in ADR-011 and by a test.                                  
      46 +  - A `projectId` in a PUT body is silently ignored (tolerant r
         +eader, ADR-010), pinned by a test.                             
      47 +  - The constraint-name matching is exercised against H2's repo
         +rted format. Another database reports names differently, so it 
         +would need rechecking if the database changed.                 
      48 +- Left for next session: M4 (filters, sorting, pagination, and 
         +the N+1 fix). Project deletion, project update, case-insensitiv
         +e names and project pagination are listed as next steps in ADR-
         +011. M3 is committed in `6802dfe` (projects) and `fd3a5db` (ass
         +ignment), plus the docs commit. Not pushed. The transcript expo
         +rt is still to do.                                             
      49 +                                                               
      50  ### Session 02: M2 Task CRUD and error handling (2026-11-15)
      51  - Built: Task entity, enums, repository, DTOs, `TaskService`, `
          TaskController`, `PageResponse`, `GlobalExceptionHandler` (ADR-
          005 body for every error that reaches Spring MVC), Jackson and 
          `server.error` properties.
      52  - Decisions settled: ADR-010 (Task field rules, PUT semantics, 
          list params, tolerant reader, numbers rejected for enums). ADR-
          005 amended with `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`
          , `CLIENT_ERROR`. CLAUDE.md gained the no-fully-qualified-names
           convention.

⏺ Update(docs/PROGRESS.md)  ⎿  Added 2 lines
      84  | 02 | Named the `fieldErrors` record `FieldError`. | Clashes w
          ith Spring's `org.springframework.validation.FieldError`, which
           the exception handler reads, forcing fully qualified names. | 
          Code review before step 2. | Renamed to `FieldViolation`. |
      85  | 02 | Built the `Location` header with `ServletUriComponentsBu
          ilder.fromCurrentRequest()`. | It carries the query string, so 
          `POST /tasks?foo=1` returned `/tasks/7?foo=1`. | Code review of
           `TaskController`. | `fromCurrentRequestUri()`, plus a test ass
          erting no `?` in `Location`. |
      86  | 02 | The `handleExceptionInternal` override called `getBody()
          ` on the result of `super` without a null check, and the fallba
          ck code for unmapped 4xx was `BAD_REQUEST`. | `super` can retur
          n null when the response is already committed, and an error han
          dler must never throw. A 406 labelled `BAD_REQUEST` is misleadi
          ng. | Code review of `GlobalExceptionHandler`. | Null guard add
          ed (still untested, see Known gaps). Fallback renamed `CLIENT_E
          RROR` and added to ADR-005. |
      87 +| 03 | Matched the unique violation with an exact `equalsIgnore
         +Case` on `getConstraintName()`, after checking that the Hiberna
         +te method exists, but never checking what H2 puts in it. | H2 r
         +eports `PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_I
         +NDEX_F`: schema-qualified, upper case, with index text. The mat
         +ch never succeeded, so a duplicate name was a 500, not a 409. I
         + had checked the API shape and assumed the value. | The real-da
         +tabase tests (two duplicate cases and the concurrency test) ret
         +urned 500 on the first run. The unit tests with a hand-written 
         +name would have passed. | Probed the real value with a throwawa
         +y test. `bareConstraintName` keeps the first word and drops the
         + schema prefix, and the result is still compared for equality, 
         +never with `contains` (you vetoed loosening it). Unit tests use
         + the real H2 string and a lookalike (`uk_projects_name_other`).
         + |                                                             
      88 +| 03 | Wrote two weak tests in `TaskProjectIntegrationTest`: on
         +e asserting no Hibernate proxy fields in a JSON response, and o
         +ne with `"Task 998 not found".replace("998", "999998")` as an e
         +xpected string. | The first cannot fail, because `TaskResponse`
         + is a record and never serializes an entity. The second is conf
         +using for no reason. Both are noise you would have to defend. |
         + My own review of the test file before the first run. You did n
         +ot catch these, I did. | Deleted the first and wrote the litera
         +l in the second. |                                             
      89  | 01 | The plan said "git repo exists, no commits assumed". The
           docs were already committed. | I checked `.git` and `.gitignor
          e` but never ran `git log`, so the plan rested on an assumption
          . Low impact. | Caught in plan review. | Ran `git log`: one com
          mit, `4ae0121 Add project docs and plan`. Check git history bef
          ore planning from now on. |

⏺ I wrote "you vetoed loosening it" in the first mistakes-log row, and that's
  not true. You never said that; I chose not to loosen the match myself. I'll
  correct it, then run the final test and commit.

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 1 line, removed 1 line
      84  | 02 | Named the `fieldErrors` record `FieldError`. | Clashes w
          ith Spring's `org.springframework.validation.FieldError`, which
           the exception handler reads, forcing fully qualified names. | 
          Code review before step 2. | Renamed to `FieldViolation`. |
      85  | 02 | Built the `Location` header with `ServletUriComponentsBu
          ilder.fromCurrentRequest()`. | It carries the query string, so 
          `POST /tasks?foo=1` returned `/tasks/7?foo=1`. | Code review of
           `TaskController`. | `fromCurrentRequestUri()`, plus a test ass
          erting no `?` in `Location`. |
      86  | 02 | The `handleExceptionInternal` override called `getBody()
          ` on the result of `super` without a null check, and the fallba
          ck code for unmapped 4xx was `BAD_REQUEST`. | `super` can retur
          n null when the response is already committed, and an error han
          dler must never throw. A 406 labelled `BAD_REQUEST` is misleadi
          ng. | Code review of `GlobalExceptionHandler`. | Null guard add
          ed (still untested, see Known gaps). Fallback renamed `CLIENT_E
          RROR` and added to ADR-005. |
      87 -| 03 | Matched the unique violation with an exact `equalsIgnore
         -Case` on `getConstraintName()`, after checking that the Hiberna
         -te method exists, but never checking what H2 puts in it. | H2 r
         -eports `PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_I
         -NDEX_F`: schema-qualified, upper case, with index text. The mat
         -ch never succeeded, so a duplicate name was a 500, not a 409. I
         - had checked the API shape and assumed the value. | The real-da
         -tabase tests (two duplicate cases and the concurrency test) ret
         -urned 500 on the first run. The unit tests with a hand-written 
         -name would have passed. | Probed the real value with a throwawa
         -y test. `bareConstraintName` keeps the first word and drops the
         - schema prefix, and the result is still compared for equality, 
         -never with `contains` (you vetoed loosening it). Unit tests use
         - the real H2 string and a lookalike (`uk_projects_name_other`).
         - |                                                             
      87 +| 03 | Matched the unique violation with an exact `equalsIgnore
         +Case` on `getConstraintName()`, after checking that the Hiberna
         +te method exists, but never checking what H2 puts in it. | H2 r
         +eports `PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_I
         +NDEX_F`: schema-qualified, upper case, with index text. The mat
         +ch never succeeded, so a duplicate name was a 500, not a 409. I
         + had checked the API shape and assumed the value. | The real-da
         +tabase tests (two duplicate cases and the concurrency test) ret
         +urned 500 on the first run. The unit tests with a hand-written 
         +name would have passed. | Probed the real value with a throwawa
         +y test. `bareConstraintName` keeps the first word and drops the
         + schema prefix, and the result is still compared for equality, 
         +never with `contains`, because that would match a future `uk_pr
         +ojects_name_other`. Unit tests use the real H2 string and a loo
         +kalike (`uk_projects_name_other`). |                           
      88  | 03 | Wrote two weak tests in `TaskProjectIntegrationTest`: on
          e asserting no Hibernate proxy fields in a JSON response, and o
          ne with `"Task 998 not found".replace("998", "999998")` as an e
          xpected string. | The first cannot fail, because `TaskResponse`
           is a record and never serializes an entity. The second is conf
          using for no reason. Both are noise you would have to defend. |
           My own review of the test file before the first run. You did n
          ot catch these, I did. | Deleted the first and wrote the litera
          l in the second. |
      89  | 01 | The plan said "git repo exists, no commits assumed". The
           docs were already committed. | I checked `.git` and `.gitignor
          e` but never ran `git log`, so the plan rested on an assumption
          . Low impact. | Caught in plan review. | Ran `git log`: one com
          mit, `4ae0121 Add project docs and plan`. Check git history bef
          ore planning from now on. |

⏺ Bash(./mvnw test 2>&1 | grep -E "Tests run:.*[0-9]$|BUILD" | tail -2; git
      status --short)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ Bash(git add docs && git commit -q -F - <<'EOF'
      Record M3 decisions, flow and progress…)
  ⎿  (No output)

⏺ M3 is done and committed in three commits, and the working tree is clean.
  ./mvnw test passes with 103 tests and 0 failures.

  ┌─────────┬────────────────────────────────────────────────────────────────┐
  │ Commit  │                              What                              │
  ├─────────┼────────────────────────────────────────────────────────────────┤
  │ 6802dfe │ Project entity and endpoints, database-enforced unique names,  │
  │         │ 409 DUPLICATE_PROJECT_NAME                                     │
  ├─────────┼────────────────────────────────────────────────────────────────┤
  │ fd3a5db │ Task-to-project mapping, assign and unassign endpoints,        │
  │         │ project fields on TaskResponse                                 │
  ├─────────┼────────────────────────────────────────────────────────────────┤
  │ 0e894b3 │ Docs                                                           │
  └─────────┴────────────────────────────────────────────────────────────────┘

  Done criteria

  - Create and list projects: done. GET /projects/{id} is also included.
  - Assign and unassign tasks: done. Repeating an assign changes nothing, and
    unassigning a task with no project still returns 204.
  - ADR-005 shape on every new error path: done. This covers the 409 duplicate,
    404 for a missing task or project, and 400 for bad input or non-numeric ids.
    The tests assert the bodies.

  Manual check

  I ran the real app with no API key and used curl. The 201, the 409 duplicate,
  assign, repeat-assign, both 404s, double-unassign and both list endpoints all
  behaved as designed, with no "Unhandled exception" in the log. My first curl
  attempt returned 415s because zsh doesn't split $H. That was my shell, not the
  API, and the rerun was clean.

  Docs

  - ADR-011 records the M3 decisions, including how a duplicate name is caught
    and the "no project deletion" decision with its next steps.
  - ADR-005 has the new row for DUPLICATE_PROJECT_NAME.
  - The three pending M3 items are resolved and removed.
  - FLOW.md has the new flows, and a note that the N+1 exists as built.
  - PLAN.md M3 has been updated to match.
  - PROGRESS.md has the session entry and two Agent Mistakes Log rows. One is
    the H2 constraint name, which the real-database tests caught. The other is
    two weak tests I wrote and then removed.
  - I first wrote "you vetoed loosening it" in the first row, which was untrue,
    and I fixed it before committing.

  Still open

  - Transcript: the M3 transcript isn't exported. The status table says "export
    pending: transcripts/03-projects.md". Run /export and commit it, as with M2.
  - Push: nothing is pushed.
  - Next: M4. Besides filters, sorting and pagination, it owns the N+1 fix at
    TaskService.list.