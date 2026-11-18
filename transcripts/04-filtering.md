
❯ Read CLAUDE.md and docs/. We're starting M4 (filtering, sorting and
  pagination) from docs/PLAN.md. M2 built paging and M3 built projects, and
  this extends GET /tasks on top of both.

  Done means:
  - GET /tasks accepts status, priority, projectId, dueAfter, dueBefore and
  sort, all combinable with the existing page and size.
  - Priority sorts by meaning (LOW < MEDIUM < HIGH) through normal Pageable
  sorting, backed by a priority_rank column.
  - Default and custom sorts behave exactly as in decision 7, and paging is
  stable across pages.
  - Every bad input returns 400 in the ADR-005 shape, naming the param.
  - The task list loads projects without N+1.
  - The spec's required test passes: several filters combined with pagination.
  - The M2 paging test still passes, and the full suite is green.

  My decisions (if you think one is wrong, tell me why before building):

  1. Priority sorting uses a rank column. A hidden numeric priority_rank
  (LOW=1, MEDIUM=2, HIGH=3), derived from the enum inside the entity whenever
  priority is set, so it can't drift. Why: text sorts alphabetically (HIGH,
  LOW, MEDIUM). A rank column works directly with Spring's paging and sorting,
  unlike a CASE expression. ORDINAL storage is a trap, because reordering the
  enum silently changes stored data. The rank is internal: it never appears in
  TaskResponse, and clients sort with "priority", which you translate to the
  rank column.
  2. The date range is inclusive on both ends.
  dueAfter=2026-11-16&dueBefore=2026-11-22 includes both edge dates. Either
  param works alone, and equal dates mean "due exactly that day". Format is
  yyyy-MM-dd. Why: it matches how people think about date ranges, and the param
  names come from the spec.
  3. An impossible range returns 400. dueAfter later than dueBefore returns 400
  VALIDATION_FAILED with a clear message. Why: it's almost certainly a client
  mistake, like swapped dates, and an empty list would hide it.
  4. status and priority accept multiple values.
  ?status=TODO&status=IN_PROGRESS&priority=HIGH means (TODO or IN_PROGRESS) and
  HIGH: OR within a filter, AND across filters. Why: "show my open tasks" is
  the most common real query, and Spring binds lists for free.
  5. projectId filter. ?projectId=3 returns that project's tasks. An unknown id
  returns an empty list, not a 404. Why: it's a filter, not a lookup, so "no
  tasks match" is a correct answer.
  6. Sorting uses Spring's format, e.g. ?sort=priority,desc. Allowed fields are
  dueDate and priority, and any other field or an invalid direction returns
  400. Multiple sorts are allowed. Why: the spec requires both fields, and the
  whitelist stops clients sorting on internal columns.
  7. Default sort. With no sort param: dueDate ascending, then priority
  descending, then id ascending. A client-supplied sort replaces the default,
  and id is always appended as the final tiebreaker. This updates ADR-010's
  id-only sort. Why: it answers "what should I do next": deadlines first,
  urgency breaks ties, id keeps paging stable.

  Out of scope: filtering for tasks with no project, text search, sorting by
  other fields, and any migration or backfill (the DB is in-memory with
  create-drop, so there are no existing rows).

  Watch for:
  - Without id as the last sort key, tasks with equal values can repeat or
  vanish across pages.
  - Fetch the project in the same query as the tasks, and confirm it with the
  SQL log the way you measured the N+1 in M3.
  - A bad value inside a multi-value list (?status=TODO&status=FOO) returns 400
  naming status.
  - If the M2 paging test needs changing, tell me why before changing it.

  Plan first. Read the current controller, service, repository, entity and the
  M2 paging test, then show me:
  1. Files to change or add, in order, with commit points.
  2. How priority_rank is set and how "priority" maps to it in sorting.
  3. How filters are composed and how the project is fetched without N+1.
  4. How the whitelist, default sort and id tiebreaker are applied to the
  incoming Pageable.
  5. Where each validation lives (range, bad enum, bad date, bad sort field or
  direction) and how each maps to ADR-005.
  6. The tests, including the required combined-filters-plus-paging test.
  7. Anything in the codebase that conflicts with these decisions.
  Docs at the end: a new ADR for M4, update ADR-010, FLOW.md and PROGRESS.md.
  Wait for my approval before writing code.

⏺ I'll start with the docs the project requires, then the code you listed.

  Searched for 1 pattern, read 11 files (ctrl+o to expand)

⏺ Bash(git log --oneline | head -20 && git status --short && grep -n -i
      "page\|size\|sort"
      src/test/java/dev/jasmeet/taskmanager/task/TaskApiIntegrationTest.java
      src/t…)
  ⎿  (No output)

⏺ Two M2 tests will be affected, so I'm checking a few framework behaviours
  before planning. I'm probing library behaviour in a scratch directory only and
  not touching the project.

⏺ Bash(cat src/main/java/dev/jasmeet/taskmanager/task/Priority.java
      src/main/java/dev/jasmeet/taskmanager/project/Project.java | head -60; sed
      -n 140,185p src/test/jav…)
  ⎿  (No output)

  Listed 1 directory (ctrl+o to expand)

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad; mkdir -p $S/probe && cd
      $S/probe
      M=~/.m2/rep…)
  ⎿  Error: Exit code 1
     (eval):3: no matches found: /Users/jasmeetsingh/.m2/repository/org/springfr
     amework/spring-jcl/7.0.10/*.jar
         var r = new SortHandlerMethodArgumentResolver();
                     ^
       symbol:   class SortHandlerMethodArgumentResolver
       location: class Probe
     3 errors
     Error: Could not find or load main class Probe
     Caused by: java.lang.ClassNotFoundException: Probe

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad/probe; cd $S
      M=~/.m2/repository…)
  ⎿  (No output)

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad/probe
      M=~/.m2/repository…)
  ⎿  (No output)

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad/probe
      M=~/.m2/repository…)
  ⎿  (No output)

⏺ Still probing how Spring Data's sort parser treats a bad direction like
  priority,foo. The parse method is protected and the name I guessed didn't
  match, so I'm checking the real signature.

⏺ Bash(M=~/.m2/repository; J=$M/org/springframework/data/spring-data-commons/4.1
      .2/spring-data-commons-4.1.2.jar
      javap -p -cp $J org.springframework.data.web.SortHandl…)
  ⎿  (No output)

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad/probe2
      mkdir -p $S/org/springframework/data/w…)
  ⎿  (No output)

⏺ The probe shows Spring's sort parser is lenient, which affects the plan. I'm
  next checking the Specification API in this Spring Data JPA version and any
  code relying on enum ordinals. · summary

⏺ Bash(cd /Users/jasmeetsingh/dev/task-manager-api; M=~/.m2/repository; J=$(find
      $M/org/springframework/data/spring-data-jpa/ -name
      "spring-data-jpa-4*.jar" ! -name "*…)
  ⎿  (No output)

⏺ Plan for M4 below. I haven't written any code. The only things I created were
  throwaway probes in the scratchpad, to check how Spring parses sort. None of
  your seven decisions looks wrong.

  1. Files and commit points

  The commits are ordered so each one is green on its own.

  C1: Priority rank column
  - Priority.java (src/main/java/dev/jasmeet/taskmanager/task/Priority.java):
    explicit ranks, LOW(1), MEDIUM(2), HIGH(3).
  - Task.java (src/main/java/dev/jasmeet/taskmanager/task/Task.java): new
    priorityRank field (details in section 2).
  - Test: a small integration test proving the rank follows the priority on
    create and on update.

  C2: Sorting
  - New task/TaskSort.java: whitelist, parser, default sort and id tiebreaker.
  - New common/InvalidRequestException.java, with a handler in
    GlobalExceptionHandler.java
    (src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.java)
    (400 VALIDATION_FAILED).
  - TaskController.list and TaskService.list gain sort.
  - Tests: TaskSortTest, priority-order and stable-paging integration tests, and
    the 400 cases.

  C3: Filters and the N+1 fix
  - New task/TaskFilter.java: a record holding the five filter values.
  - New task/TaskSpecifications.java: one small spec per filter.
  - TaskRepository.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskRepository.java): adds
    JpaSpecificationExecutor<Task> and a findAll(spec, pageable) override with
    @EntityGraph(attributePaths = "project").
  - Controller and service take the filters. The range check lives in the
    service.
  - handleTypeMismatch fix, described in section 5.
  - Tests: the required combined-filters-plus-paging test, per-filter tests, the
    400 cases, and an N+1 regression test.

  C4: Docs
  - New ADR-012.
  - ADR-010's GET /tasks bullet updated.
  - FLOW.md updated to what was built.
  - PROGRESS.md updated.
  - Pending M4 items moved to Accepted.

  2. priority_rank

  - Priority gets an explicit constructor value and a rank() accessor. It does
    not use ordinal(), so reordering the enum can't change stored data.
  - Task.assign(...) is the single private method that both the constructor and
    update() call, and it sets this.priorityRank = priority.rank(). The rank
    therefore changes whenever the priority changes. There is no setter and no
    getter, and TaskResponse is untouched.
  - The column is mapped as @Column(name = "priority_rank", nullable = false).
  - Sort mapping: TaskSort holds Map.of("dueDate", "dueDate", "priority",
    "priorityRank"). The client says priority and the Pageable carries
    priorityRank. A client sending priorityRank or id gets a 400, because only
    the map keys are allowed.

  3. Filters and the project fetch

  - Each spec is a static method in TaskSpecifications:
    - statusIn(list)
    - priorityIn(list)
    - inProject(id)
    - dueOnOrAfter(date)
    - dueOnOrBefore(date)
  - Each spec returns unrestricted() when its value is null or empty, and the
    service combines them with Specification.allOf(...). That gives AND across
    filters, and IN (...) gives OR within one.
  - The project fetch is a repository @EntityGraph, not a fetch join inside a
    spec. A fetch join in a spec breaks the count query unless you add a
    result-type guard. The graph is applied to the main query and the join is
    to-one, so paging stays in SQL.
  - I'll confirm the count query works with the graph in the first test run,
    because I haven't verified that yet. The fallback is a guarded fetch join.
  - N+1 proof, two ways:
    - SQL log: I'll run the app with logging.level.org.hibernate.SQL=DEBUG and
      curl a list with 3 projects, as in M3. I expect one SELECT with a LEFT
      JOIN, plus a count when the page is full.
    - Regression test: a Hibernate-statistics test asserts that the statement
      count is the same for 1 project and for 3 distinct projects.
  - get, update and assignProject still pay one extra SELECT each. That was
    already known in ADR-011, and I'm leaving it alone as out of scope.

  4. Whitelist, default sort, tiebreaker

  The service calls TaskSort.resolve(rawSort) and builds PageRequest.of(page,
  size, sort).

  - No sort given: dueDate ASC, priorityRank DESC, id ASC.
  - Sort given: each entry is validated and translated, the default is dropped,
    and id ASC is appended last. id can't be requested, so it never appears
    twice.
  - page and size: they stay as the M2 validated @RequestParam ints, which keeps
    their 400 behaviour.

  One deviation to approve. I would not use Spring's Pageable or Sort resolver
  for parsing. It is lenient. This is what Spring Data 4.1.2 returned in my
  probe:

  ┌───────────────────────┬─────────────────────────────────────┐
  │         Input         │         Spring parses it as         │
  ├───────────────────────┼─────────────────────────────────────┤
  │ priority,foo          │ priority ASC, foo ASC               │
  ├───────────────────────┼─────────────────────────────────────┤
  │ dueDate,desc,priority │ dueDate ASC, desc ASC, priority ASC │
  ├───────────────────────┼─────────────────────────────────────┤
  │ ,desc                 │ unsorted (silently ignored)         │
  └───────────────────────┴─────────────────────────────────────┘

  A plain Pageable argument would also clamp size and ignore page=abc, which
  would break the existing M2 error tests.

  Instead, TaskSort is a strict parser of about 20 lines. It accepts field or
  field,asc|desc (direction case-insensitive), and it is repeatable. The result
  still goes through a normal PageRequest and Sort.

  One catch: @RequestParam List<String> splits a single priority,desc on the
  comma, but not when sort is repeated. To avoid that the controller reads the
  raw values through WebRequest.getParameterValues("sort"). A test with a single
  sort=priority,desc will prove the problem is avoided.

  5. Where each validation lives

  Every case is 400 VALIDATION_FAILED and names the param. ADR-005 needs no
  amendment.

  ┌──────────────────────────┬──────────────────────────┬───────────────────┐
  │           Case           │          Where           │ fieldErrors.field │
  ├──────────────────────────┼──────────────────────────┼───────────────────┤
  │ Bad enum, including      │ Spring conversion in the │ status or         │
  │ status=TODO&status=FOO   │  controller →            │ priority          │
  │                          │ handleTypeMismatch       │                   │
  ├──────────────────────────┼──────────────────────────┼───────────────────┤
  │ Bad or impossible date   │ same,                    │ dueAfter or       │
  │ (abc, 2026-13-01)        │ @DateTimeFormat(iso =    │ dueBefore         │
  │                          │ DATE)                    │                   │
  ├──────────────────────────┼──────────────────────────┼───────────────────┤
  │ projectId=abc, bad page  │ same, plus the existing  │ the param name    │
  │ or size                  │ @Min and @Max            │                   │
  ├──────────────────────────┼──────────────────────────┼───────────────────┤
  │ dueAfter later than      │ TaskService.list throws  │ dueAfter          │
  │ dueBefore                │ InvalidRequestException  │                   │
  ├──────────────────────────┼──────────────────────────┼───────────────────┤
  │ Unknown sort field, bad  │ TaskSort throws          │                   │
  │ direction, extra parts,  │ InvalidRequestException  │ sort              │
  │ duplicate field          │                          │                   │
  └──────────────────────────┴──────────────────────────┴───────────────────┘

  Fix needed in handleTypeMismatch. For a List<Status> parameter,
  getRequiredType() is List, so the message would read "has an invalid value"
  instead of "must be one of TODO, IN_PROGRESS, DONE". I'll unwrap the element
  type, and a test asserts the message text.

  Limit. Type errors are reported one param at a time, the first one that fails,
  as in M2. Only the range and sort checks run after binding.

  6. Tests

  Required test. combinedFilters_withPagination:
  - Eight or so tasks across two projects, with decoys that are one day outside
    each edge, and others with the wrong status, wrong priority or wrong
    project.
  - Request: status=TODO&status=IN_PROGRESS&priority=HIGH&projectId=X&dueAfter&d
    ueBefore&page=0&size=2.
  - It asserts content and order, totalElements and totalPages, then page 1, and
    that both edge dates are included.

  Other integration tests:
  - Each filter alone.
  - Multi-value OR.
  - Unknown projectId returns 200 with an empty list.
  - Equal dates mean exactly that day.
  - Priority sort ascending and descending, with data inserted LOW, HIGH,
    MEDIUM, so an alphabetical sort would fail.
  - Default order.
  - Custom sort replaces the default.
  - Multi-sort.
  - Stable paging: tied rows show up exactly once across pages.
  - An updated priority moves the row when sorting.
  - priorityRank is absent from the response JSON.
  - The N+1 test.

  400 tests in ErrorHandlingIntegrationTest, using its expectProblem helper:
  - bad enum, and the bad-value-in-a-list case
  - bad date
  - reversed range
  - sort on title, id and priorityRank
  - bad direction
  - duplicate sort field
  - projectId=abc

  Unit tests: TaskSortTest, and TaskServiceTest checks the Pageable sent to the
  repository and that the repository is not called on a reversed range.

  M2 paging tests:
  - The integration test getList_pagesInIdOrder needs no change. All three of
    its tasks have the same date and priority, so the default sort falls through
    to id.
  - The unit test list_requestsPageSortedByIdAndMapsToPageResponse must change.
    It asserts Sort.by("id") and mocks findAll(Pageable), and both are removed
    by decision 7 and the move to specs. I'll update it to assert the new
    default sort and findAll(spec, pageable), not delete it.

  7. Conflicts with the codebase

  - Priority.java: its comment says "Declaration order is the rank... M4 sorting
    relies on it". That is the ordinal trap your decision 1 rules out, so I'll
    replace it with explicit ranks.
  - ADR-010: its "ordered by id" line is superseded by decision 7. I'll update
    it, as you said.
  - Pending items in DECISIONS.md: all of them are covered by your decisions.
    - The null-dueDate placement item is moot, because dueDate is nullable =
      false.
    - "Size above max" was already settled as 400 in ADR-010.
  - Stale comments about the N+1 in TaskService and TaskResponse get removed.

  Three things to confirm

  1. Strict TaskSort parser, reading raw sort values, as described in section 4.
     I recommend it.
  2. Blank means not supplied: ?sort=, ?status= and ?dueAfter= are treated as
     absent, which is Spring's behaviour for the other params. Anything
     non-blank must be strictly valid. I recommend it.
  3. Range error field: one entry naming dueAfter, with a message like "must not
     be later than dueBefore". The alternative is listing both params.

  One naming point for the interview: dueAfter and dueBefore are inclusive here
  although the names suggest exclusive. I'll state that in ADR-012, the README
  and the error messages.

  Approve, or tell me what to change, and I'll start C1.

❯ Approved, including all three: the strict TaskSort parser, blank meaning not
  supplied, and the range error naming dueAfter. Good catch on the
  Priority.java comment.

  One addition: Spring also binds the comma form ?status=TODO,IN_PROGRESS for
  lists. Test that it works the same as the repeated form, and document both.

  Start C1.

⏺ Starting C1. I'm reading the existing integration test setup first so the new
  test follows the same conventions.

  Read 1 file (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/Priority.java)
  ⎿  Added 18 lines, removed 2 lines
       1  package dev.jasmeet.taskmanager.task;
       2  
       3 -/** Declaration order is the rank (LOW < MEDIUM < HIGH). M4 sor
         -ting relies on it. */                                          
       3 +/**                                                            
       4 + * The rank is written out for each constant, never taken from 
         +ordinal(). Task stores it in the                               
       5 + * priority_rank column so priority can be sorted by meaning (L
         +OW < MEDIUM < HIGH) and not                                    
       6 + * alphabetically. Reordering or inserting constants here canno
         +t change what is stored.                                       
       7 + */                                                            
       8  public enum Priority {
       5 -    LOW, MEDIUM, HIGH                                          
       9 +    LOW(1),                                                    
      10 +    MEDIUM(2),                                                 
      11 +    HIGH(3);                                                   
      12 +                                                               
      13 +    private final int rank;                                    
      14 +                                                               
      15 +    Priority(int rank) {                                       
      16 +        this.rank = rank;                                      
      17 +    }                                                          
      18 +                                                               
      19 +    public int rank() {                                        
      20 +        return rank;                                           
      21 +    }                                                          
      22  }

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 6 lines
      41      @Column(nullable = false)
      42      private Priority priority;
      43  
      44 +    // Internal sort key: Priority.rank() (LOW=1, MEDIUM=2, HIG
         +H=3). The priority column holds text, which                    
      45 +    // sorts alphabetically (HIGH, LOW, MEDIUM). Only assign() 
         +writes this field, in the same step as                         
      46 +    // priority, so the two cannot drift apart. No getter, and 
         +it is never part of TaskResponse.                              
      47 +    @Column(name = "priority_rank", nullable = false)          
      48 +    private int priorityRank;                                  
      49 +                                                               
      50      @Enumerated(EnumType.STRING)
      51      @Column(nullable = false)
      52      private Status status;

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/Task.java)
  ⎿  Added 1 line
      87          this.description = description;
      88          this.dueDate = dueDate;
      89          this.priority = priority;
      90 +        this.priorityRank = priority.rank();                   
      91          this.status = status;
      92      }
      93  

⏺ Bash(cd /Users/jasmeetsingh/dev/task-manager-api/src/main/java/dev/jasmeet/tas
      kmanager/task && grep -n "record\|^        [A-Za-z]"
      CreateTaskRequest.java UpdateTaskR…)
  ⎿  (No output)

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/PriorityRankIntegrationTest.j
       ava)  ⎿  Wrote 91 lines to src/test/java/dev/jasmeet/taskmanager/task/PriorityR
     ankIntegrationTest.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import org.junit.jupiter.api.BeforeEach;
       4 import org.junit.jupiter.api.Test;
       5 import org.springframework.beans.factory.annotation.Autowired;
       6 import org.springframework.boot.test.context.SpringBootTest;
       7 import org.springframework.data.domain.Sort;
       8 import org.springframework.jdbc.core.JdbcTemplate;
       9
      10 import java.time.LocalDate;
     … +81 lines (ctrl+o to expand)

⏺ Bash(cd /Users/jasmeetsingh/dev/task-manager-api && ./mvnw -q test 2>&1 | tail
      -40)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ The quiet-mode output was mostly log noise, so I'm reading the Surefire
  summary instead.

⏺ Bash(./mvnw test > /private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manage
      r-api/a50f6cf4-054e-4553-8ddc-cc9c120ff918/scratchpad/c1.log 2>&1; echo
      exit=$?; grep -…)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ The suite is green at 107 tests. Before calling C1 done I'm checking that the
  new tests actually fail when the rank assignment is removed.

⏺ Bash(F=src/main/java/dev/jasmeet/taskmanager/task/Task.java && cp $F
      /private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6cf4-
      054e-4553-8ddc-cc9c120…)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ C1 is done and tested but not committed. ./mvnw test passes with 107 tests and
  no failures: the previous 103 plus 4 new ones.

  What changed
  - Priority.java (src/main/java/dev/jasmeet/taskmanager/task/Priority.java):
    explicit LOW(1), MEDIUM(2), HIGH(3) with a rank() accessor. The old comment
    saying "declaration order is the rank" is replaced with one explaining why
    ordinal is never used.
  - Task.java (src/main/java/dev/jasmeet/taskmanager/task/Task.java): new
    priority_rank column, nullable = false, with no getter. assign() sets it in
    the same line block as priority. Since the constructor and update() both go
    through assign(), nothing else can change the rank.
  - PriorityRankIntegrationTest.java (src/test/java/dev/jasmeet/taskmanager/task
    /PriorityRankIntegrationTest.java): 4 tests against H2, with no web layer
    involved.
    - A direct SQL read confirms the stored ranks are 1, 2 and 3.
    - Sorting by priorityRank gives LOW, MEDIUM, HIGH when the rows were
      inserted HIGH, LOW, MEDIUM. Alphabetical order would be HIGH, LOW, MEDIUM,
      so this catches that trap.
    - Updating the priority changes the stored rank.
    - Updating other fields while keeping the same priority leaves the rank
      unchanged.

  Mutation check: I removed the rank line from assign(). All 4 new tests failed,
  and the sort test showed the order HIGH, LOW, MEDIUM. I restored the line,
  and the diff shows only the intended changes.

  One thing for you: the full test log contains stack traces. They come from
  existing tests that deliberately trigger logged 500s. The run exits 0 with
  BUILD SUCCESS.

  Proposed commit: Add priority rank column for sorting by meaning. I'll start
  C2 (sorting) once you've committed or told me to go ahead. Say so if you'd
  rather I wait.

❯ <ide_opened_file>The user opened the file
  /Users/jasmeetsingh/dev/task-manager-api/docs/PLAN.md in the IDE. This may or
  may not be related to the current task.</ide_opened_file>

❯ Commit it and go ahead with C2.

⏺ Bash(git add src/main/java/dev/jasmeet/taskmanager/task/Priority.java
      src/main/java/dev/jasmeet/taskmanager/task/Task.java
      src/test/java/dev/jasmeet/taskmanager/task…)
  ⎿  (No output)

  Read 3 files (ctrl+o to expand)

⏺ C1 is committed as 5c56930. Now C2: the strict TaskSort parser, the exception,
  the handler, and the controller and service wiring.

⏺ Write(/Users/jasmeet/placeholder)
  ⎿  Error: EACCES: permission denied, mkdir '/Users/jasmeet'

⏺ That write was a mistaken stray call to a wrong path and it failed, so nothing
  was created. Continuing with the real files.

⏺ Write(src/main/java/dev/jasmeet/taskmanager/common/InvalidRequestException.jav
       a)
  ⎿  Wrote 26 lines to src/main/java/dev/jasmeet/taskmanager/common/Invalid
     RequestException.java
       1 package dev.jasmeet.taskmanager.common;
       2
       3 import java.util.List;
       4
       5 /**
       6  * Thrown by services when a request is invalid in a way only th
         e service can tell (for example a
       7  * date range that is backwards, or a sort field that is not all
         owed). Mapped to 400 VALIDATION_FAILED
       8  * with the given field errors. Spring's own checks (types, @Val
     … +19 lines (ctrl+o to expand)

⏺ Update(src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.jav
        a)
  ⎿  Added 6 lines
      74                  ex.getCode(), ex.getFieldErrors(), request);
      75      }
      76  
      77 +    /** A service rejected the request (e.g. a sort field that 
         +is not allowed). Same 400 as Bean Validation. */               
      78 +    @ExceptionHandler(InvalidRequestException.class)           
      79 +    public ResponseEntity<Object> handleInvalidRequest(InvalidR
         +equestException ex, WebRequest request) {                      
      80 +        return validationFailed(ex, null, ex.getFieldErrors(), 
         +request);                                                      
      81 +    }                                                          
      82 +                                                               
      83      // ---------- 400: validation and unreadable input --------
          --
      84  
      85      /** Bean Validation failures on a request body. Every faili
          ng field is listed. */

⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/TaskSort.java)
  ⎿  Wrote 127 lines to
     src/main/java/dev/jasmeet/taskmanager/task/TaskSort.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import dev.jasmeet.taskmanager.common.FieldViolation;
       4 import dev.jasmeet.taskmanager.common.InvalidRequestException;
       5 import org.springframework.data.domain.Sort;
       6 import org.springframework.data.domain.Sort.Direction;
       7 import org.springframework.data.domain.Sort.Order;
       8
       9 import java.util.ArrayList;
      10 import java.util.Arrays;
     … +117 lines (ctrl+o to expand)

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 4 lines, removed 5 lines
      38          return TaskResponse.from(findOrThrow(id));
      39      }
      40  
      41 -    public PageResponse<TaskResponse> list(int page, int size) 
         -{                                                              
      42 -        // Fixed sort by id keeps paging stable. Sorting and fi
         -lters arrive in M4.                                            
      43 -        // N+1 (M4): the .map below reads each task's project n
         -ame, one SELECT per distinct project.                          
      44 -        // M4 fixes it by fetching the project in the same quer
         -y.                                                             
      45 -        PageRequest pageRequest = PageRequest.of(page, size, So
         -rt.by("id"));                                                  
      41 +    /** @param sort the raw values of the "sort" param. TaskSor
         +t validates them and adds the default and the id tiebreaker. */
      42 +    public PageResponse<TaskResponse> list(int page, int size, 
         +List<String> sort) {                                           
      43 +        // N+1 (fixed later in M4): the .map below reads each t
         +ask's project name, one SELECT per distinct project.           
      44 +        PageRequest pageRequest = PageRequest.of(page, size, Ta
         +skSort.resolve(sort));                                         
      45          return PageResponse.from(taskRepository.findAll(pageReq
          uest).map(TaskResponse::from));
      46      }
      47  

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 2 lines, removed 1 line
       5  import dev.jasmeet.taskmanager.project.Project;
       6  import dev.jasmeet.taskmanager.project.ProjectRepository;
       7  import org.springframework.data.domain.PageRequest;
       8 -import org.springframework.data.domain.Sort;                   
       8  import org.springframework.stereotype.Service;
       9  import org.springframework.transaction.annotation.Transactional
          ;
      10  
      11 +import java.util.List;                                         
      12 +                                                               
      13  @Service
      14  @Transactional(readOnly = true)
      15  public class TaskService {

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 6 lines, removed 2 lines
      44      @GetMapping
      45      public PageResponse<TaskResponse> list(
      46              @RequestParam(defaultValue = "0") @Min(0) int page,
      47 -            @RequestParam(defaultValue = "20") @Min(1) @Max(100
         -) int size) {                                                  
      48 -        return taskService.list(page, size);                   
      47 +            @RequestParam(defaultValue = "20") @Min(1) @Max(100
         +) int size,                                                    
      48 +            WebRequest webRequest) {                           
      49 +        // Read "sort" raw. A List<String> @RequestParam would 
         +split a single "priority,desc" on the comma,                   
      50 +        // but not when sort is repeated, so the two forms woul
         +d parse differently.                                           
      51 +        String[] sort = webRequest.getParameterValues("sort"); 
      52 +        return taskService.list(page, size, sort == null ? List
         +.of() : List.of(sort));                                        
      53      }
      54  
      55      @GetMapping("/{id}")

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 2 lines
      14  import org.springframework.web.bind.annotation.RequestMapping;
      15  import org.springframework.web.bind.annotation.RequestParam;
      16  import org.springframework.web.bind.annotation.RestController;
      17 +import org.springframework.web.context.request.WebRequest;     
      18  import org.springframework.web.servlet.support.ServletUriCompon
          entsBuilder;
      19  
      20  import java.net.URI;
      21 +import java.util.List;                                         
      22  
      23  /** HTTP only: bind and validate input, call one service method
          , return a DTO. */
      24  @RestController

⏺ Now the tests. First the M2 unit test that has to change, since it asserted
  Sort.by("id").

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 5 lines, removed 3 lines
       92      }
       93  
       94      @Test
       95 -    void list_requestsPageSortedByIdAndMapsToPageResponse() { 
       95 +    void list_requestsPageWithDefaultSortAndMapsToPageResponse
          +() {                                                          
       96          LocalDate due = LocalDate.of(2026, 12, 1);
       97          Task task = taskWithId(1L, "A", null, due, Priority.ME
           DIUM, Status.TODO);
       98          when(taskRepository.findAll(any(Pageable.class)))
       99                  .thenReturn(new PageImpl<>(List.of(task), Page
           Request.of(1, 2), 3));
      100  
      101 -        PageResponse<TaskResponse> response = taskService.list
          -(1, 2);                                                       
      101 +        PageResponse<TaskResponse> response = taskService.list
          +(1, 2, List.of());                                            
      102  
      103          ArgumentCaptor<Pageable> pageable = ArgumentCaptor.for
           Class(Pageable.class);
      104          verify(taskRepository).findAll(pageable.capture());
      105          assertThat(pageable.getValue().getPageNumber()).isEqua
           lTo(1);
      106          assertThat(pageable.getValue().getPageSize()).isEqualT
           o(2);
      107 -        assertThat(pageable.getValue().getSort()).isEqualTo(So
          -rt.by("id"));                                                 
      107 +        // ADR-012 default: dueDate asc, then priority desc (t
          +hrough its rank column), then id asc.                         
      108 +        assertThat(pageable.getValue().getSort()).isEqualTo(So
          +rt.by(                                                        
      109 +                Sort.Order.asc("dueDate"), Sort.Order.desc("pr
          +iorityRank"), Sort.Order.asc("id")));                         
      110  
      111          assertThat(response.content()).containsExactly(TaskRes
           ponse.from(task));
      112          assertThat(response.page()).isEqualTo(1);

⏺ Bash(sed -n 100,135p
      src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java; sed -n
      218,252p src/test/java/dev/jasmeet/taskmanager/common/ErrorHandlingInteg…)
  ⎿  (No output)

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 20 lines
      116      }
      117  
      118      @Test
      119 +    void list_customSortReplacesDefaultAndEndsWithId() {      
      120 +        when(taskRepository.findAll(any(Pageable.class))).then
          +Return(new PageImpl<>(List.of()));                            
      121 +                                                              
      122 +        taskService.list(0, 20, List.of("priority,desc"));    
      123 +                                                              
      124 +        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.for
          +Class(Pageable.class);                                        
      125 +        verify(taskRepository).findAll(pageable.capture());   
      126 +        assertThat(pageable.getValue().getSort()).isEqualTo(So
          +rt.by(                                                        
      127 +                Sort.Order.desc("priorityRank"), Sort.Order.as
          +c("id")));                                                    
      128 +    }                                                         
      129 +                                                              
      130 +    @Test                                                     
      131 +    void list_invalidSort_throwsAndNeverQueries() {           
      132 +        assertThatThrownBy(() -> taskService.list(0, 20, List.
          +of("title")))                                                 
      133 +                .isInstanceOf(InvalidRequestException.class); 
      134 +                                                              
      135 +        verify(taskRepository, never()).findAll(any(Pageable.c
          +lass));                                                       
      136 +    }                                                         
      137 +                                                              
      138 +    @Test                                                     
      139      void update_overwritesAllFieldsAndClearsMissingDescription
           () {
      140          Task existing = taskWithId(5L, "Old", "old description
           ",
      141                  LocalDate.of(2026, 1, 1), Priority.LOW, Status
           .TODO);
⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 1 line
      1  package dev.jasmeet.taskmanager.task;
      2  
      3 +import dev.jasmeet.taskmanager.common.InvalidRequestException;  
      4  import dev.jasmeet.taskmanager.common.NotFoundException;
      5  import dev.jasmeet.taskmanager.common.PageResponse;
      6  import dev.jasmeet.taskmanager.project.Project;

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/TaskSortTest.java)
  ⎿  Wrote 128 lines to
     src/test/java/dev/jasmeet/taskmanager/task/TaskSortTest.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import dev.jasmeet.taskmanager.common.FieldViolation;
       4 import dev.jasmeet.taskmanager.common.InvalidRequestException;
       5 import org.junit.jupiter.api.Test;
       6 import org.springframework.data.domain.Sort;
       7 import org.springframework.data.domain.Sort.Order;
       8
       9 import java.util.Arrays;
      10 import java.util.List;
     … +118 lines (ctrl+o to expand)

⏺ I used a fully qualified name in that helper, which CLAUDE.md prohibits.
  Fixing it with an import.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskSortTest.java)
  ⎿  Added 1 line, removed 1 line
      70      // ---------- invalid input: always InvalidRequestException
           naming "sort" ----------
      71  
      72      private static List<FieldViolation> violationsFor(String...
           rawSort) {
      73 -        InvalidRequestException thrown = org.junit.jupiter.api.
         -Assertions.assertThrows(                                       
      73 +        InvalidRequestException thrown = assertThrows(         
      74                  InvalidRequestException.class, () -> TaskSort.r
          esolve(List.of(rawSort)));
      75          return thrown.getFieldErrors();
      76      }

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskSortTest.java)
  ⎿  Added 1 line
      11  
      12  import static org.assertj.core.api.Assertions.assertThat;
      13  import static org.assertj.core.api.Assertions.assertThatThrownB
          y;
      14 +import static org.junit.jupiter.api.Assertions.assertThrows;   
      15  
      16  class TaskSortTest {
      17  

⏺ Now the integration tests: ordering, stable paging and the 400 cases.

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/TaskSortIntegrationTest.java)
  ⎿  Wrote 172 lines to src/test/java/dev/jasmeet/taskmanager/task/TaskSort
     IntegrationTest.java
      1 package dev.jasmeet.taskmanager.task;
      2
      3 import org.junit.jupiter.api.BeforeEach;
      4 import org.junit.jupiter.api.Test;
      5 import org.springframework.beans.factory.annotation.Autowired;
      6 import org.springframework.boot.test.context.SpringBootTest;
      7 import org.springframework.boot.webmvc.test.autoconfigure.AutoCon
        figureMockMvc;
      8 import org.springframework.test.web.servlet.MockMvc;
      9 import org.springframework.test.web.servlet.ResultActions;
     … +163 lines (ctrl+o to expand)

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskSortIntegrationTest.java
        )
  ⎿  Added 1 line, removed 1 line
      153          for (int page = 0; page < 3; page++) {
      154              String body = list("sort", "priority,desc", "page"
           , String.valueOf(page), "size", "3")
      155                      .andReturn().getResponse().getContentAsStr
           ing();
      156 -            List<Integer> ids = com.jayway.jsonpath.JsonPath.r
          -ead(body, "$.content[*].id");                                 
      156 +            List<Integer> ids = JsonPath.read(body, "$.content
          +[*].id");                                                     
      157              seen.addAll(ids);
      158          }
      159  

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskSortIntegrationTest.java
        )
  ⎿  Added 1 line
      1  package dev.jasmeet.taskmanager.task;
      2  
      3 +import com.jayway.jsonpath.JsonPath;                            
      4  import org.junit.jupiter.api.BeforeEach;
      5  import org.junit.jupiter.api.Test;
      6  import org.springframework.beans.factory.annotation.Autowired;

⏺ Update(src/test/java/dev/jasmeet/taskmanager/common/ErrorHandlingIntegrationTe
        st.java)
  ⎿  Added 44 lines
      247                  .andExpect(jsonPath("$.fieldErrors[0].message"
           ).value("must be a valid number"));
      248      }
      249  
      250 +    // ---------- 400 VALIDATION_FAILED: sort ----------      
      251 +                                                              
      252 +    @Test                                                     
      253 +    void sortOnAFieldThatIsNotAllowed_namesSort() throws Excep
          +tion {                                                        
      254 +        // title and id are real columns but not whitelisted. 
          +priorityRank is the internal column behind "priority".        
      255 +        for (String field : new String[] {"title", "id", "prio
          +rityRank"}) {                                                 
      256 +            expectProblem(mockMvc.perform(get("/tasks").param(
          +"sort", field)), 400, "VALIDATION_FAILED", "/tasks")          
      257 +                    .andExpect(jsonPath("$.fieldErrors[0].fiel
          +d").value("sort"))                                            
      258 +                    .andExpect(jsonPath("$.fieldErrors[0].mess
          +age")                                                         
      259 +                            .value("unknown sort field '" + fi
          +eld + "', must be one of dueDate, priority"));                
      260 +        }                                                     
      261 +    }                                                         
      262 +                                                              
      263 +    @Test                                                     
      264 +    void sortWithABadDirection_namesSort() throws Exception { 
      265 +        expectProblem(mockMvc.perform(get("/tasks").param("sor
          +t", "priority,sideways")),                                    
      266 +                400, "VALIDATION_FAILED", "/tasks")           
      267 +                .andExpect(jsonPath("$.fieldErrors[0].field").
          +value("sort"))                                                
      268 +                .andExpect(jsonPath("$.fieldErrors[0].message"
          +)                                                             
      269 +                        .value("invalid sort direction 'sidewa
          +ys', must be asc or desc"));                                  
      270 +    }                                                         
      271 +                                                              
      272 +    @Test                                                     
      273 +    void malformedSortValues_nameSort() throws Exception {    
      274 +        for (String value : new String[] {"priority,desc,asc",
          + ",desc", "priority,"}) {                                     
      275 +            expectProblem(mockMvc.perform(get("/tasks").param(
          +"sort", value)), 400, "VALIDATION_FAILED", "/tasks")          
      276 +                    .andExpect(jsonPath("$.fieldErrors[0].fiel
          +d").value("sort"));                                           
      277 +        }                                                     
      278 +    }                                                         
      279 +                                                              
      280 +    @Test                                                     
      281 +    void sameSortFieldTwice_namesSort() throws Exception {    
      282 +        expectProblem(mockMvc.perform(get("/tasks").param("sor
          +t", "priority,asc", "priority,desc")),                        
      283 +                400, "VALIDATION_FAILED", "/tasks")           
      284 +                .andExpect(jsonPath("$.fieldErrors[0].field").
          +value("sort"));                                               
      285 +    }                                                         
      286 +                                                              
      287 +    @Test                                                     
      288 +    void oneBadSortAmongGoodOnes_isStillRejected() throws Exce
          +ption {                                                       
      289 +        expectProblem(mockMvc.perform(get("/tasks").param("sor
          +t", "priority,desc", "title")),                               
      290 +                400, "VALIDATION_FAILED", "/tasks")           
      291 +                .andExpect(jsonPath("$.fieldErrors[0].field").
          +value("sort"));                                               
      292 +    }                                                         
      293 +                                                              
      294      // ---------- 404 ----------
      295  
      296      @Test

⏺ Bash(L=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad/c2.log; ./mvnw test > $L 2>&1;
      echo exit=$?;…)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ All 138 tests passed on the first run, so I'm running three mutation checks on
  the rank mapping, the id tiebreaker, and the claim about Spring splitting
  comma-separated sort values. · summary

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad
      T=src/main/java/dev/jasmeet/taskmanager/task/…)
  ⎿  (No output)
  ⎿  (timeout 6m 40s)

⏺ Mutations A and C were caught as expected, and C showed that a List<String>
  binding turns sort=priority,desc into 400s. I'm checking whether the paging
  integration test also fails without the id tiebreaker for B, since that output
  was cut off. · summary

⏺ Bash(S=/private/tmp/claude-501/-Users-jasmeetsingh-dev-task-manager-api/a50f6c
      f4-054e-4553-8ddc-cc9c120ff918/scratchpad;
      T=src/main/java/dev/jasmeet/taskmanager/task…)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ The paging integration test fails without the tiebreaker, so it isn't masked
  by H2's row order. I've restored both files. One last full run to confirm the
  final state.

⏺ Bash(./mvnw test 2>&1 | grep -E "Tests run:.*[0-9]+$|BUILD|FAIL" | tail -4;
      git status --short)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ C1 is committed as 5c56930. C2 (sorting) is built and tested but not
  committed. ./mvnw test passes with 138 tests and no failures, up from 107
  after C1.

  What changed
  - TaskSort.java (src/main/java/dev/jasmeet/taskmanager/task/TaskSort.java):
    the strict parser.
    - priority maps to the priorityRank column.
    - The default is dueDate asc, priorityRank desc, id asc.
    - A client sort replaces the default, and id asc is always last.
    - All bad entries are collected and reported together, each naming sort.
    - Blank values count as not supplied.
  - InvalidRequestException.java
    (src/main/java/dev/jasmeet/taskmanager/common/InvalidRequestException.java):
    thrown by services and mapped to 400 VALIDATION_FAILED by a new handler in
    GlobalExceptionHandler.java
    (src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.java).
  - TaskService.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskService.java): list now
    takes the raw sort values.
  - TaskController.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskController.java): reads sort
    raw via WebRequest.

  Tests
  - TaskSortTest has 14 unit cases.
  - TaskSortIntegrationTest has 10 tests: default order, priority ascending and
    descending, a changed priority moving in the sort, client sort replacing the
    default, repeated sort params, stable paging over 7 tied rows, and no
    priorityRank in the JSON.
  - 5 new 400 tests in ErrorHandlingIntegrationTest cover disallowed fields
    (title, id, priorityRank), a bad direction, malformed values, a duplicate
    field, and one bad entry among good ones.
  - TaskServiceTest gained 2 tests.

  The M2 paging tests
  - Changed: TaskServiceTest.list_requestsPageSortedByIdAndMapsToPageResponse.
    It asserted Sort.by("id") and called list(page, size), which decision 7
    removes. I renamed it and made it assert the new default sort and the new
    signature.
  - Unchanged: the integration test getList_pagesInIdOrder and the M2 error
    tests. They pass as they were, because their rows tie on date and priority
    and fall through to id.

  Mutation checks (each restored afterwards, final run is green):
  - Sorting on the priority text column: 5 integration tests failed.
  - Removing the id tiebreaker: 5 unit tests failed, plus
    tiedRows_appearExactlyOnceAcrossPages. H2's natural row order does not hide
    the bug.
  - Binding sort as List<String>: the tests failed with 400s, which confirms the
    comma-splitting concern from the plan.

  I also wrote a stray file to a wrong path (/Users/jasmeet/placeholder). It
  failed on permissions and created nothing.

  Not done yet:
  - ADR-012, the ADR-010 update, FLOW.md and PROGRESS.md come in C4.
  - A comment in TaskService.list still describes the N+1, which C3 fixes.
  - The comma form ?status=TODO,IN_PROGRESS test and docs you asked for arrive
    in C3, when the status filter exists.

  Proposed commit: Add strict sort parsing with default order and id tiebreaker.

  Shall I commit and start C3, or would you like to look at the diff first?

❯ Why did you write to /Users/jasmeet/placeholder? Keep all writes inside the
  repo.

⏺ It was a mistake. I made a Write call with a guessed path (/Users/jasmeet/...,
  which isn't your home directory) and throwaway content, for no reason related
  to the task. The tool rejected it with a permission error, so no file or
  directory was created. I shouldn't have made the call, and I have no better
  explanation than that.

  You should also know about other writes outside the repo this session. I put
  them in the session scratchpad, which the environment gives me for temp files,
  but you've now said to keep everything in the repo:
  - Probe files: the two small Java classes I used to check how Spring Data
    parses sort.
  - Backups and logs: the .bak copies of Task.java, TaskSort.java and
    TaskController.java for the mutation checks, and the c1.log and c2.log test
    logs.

  None of that touches the repo, but from now on I'll keep all writes inside it:
  - Logs and probes: these go in target/, which is gitignored, so nothing can be
    committed by accident.
  - Mutation checks: I'll use git stash or git checkout -- <file> to restore
    files, so I don't need backup copies.

  Maven itself still writes to target/ and to ~/.m2, which is the normal build
  cache.

  I haven't committed C2 or started C3. Do you want me to commit C2 and
  continue?

❯ Accepted, and log it in the Mistakes Log at wrap-up.

  One correction to your new approach: git checkout -- <file> restores the last
  commit, so using it on uncommitted work would wipe the work itself, not just
  the mutation. Rule from now on: commit a chunk before mutation-checking it,
  then restore with git checkout. If you ever need to mutate uncommitted work,
  keep the backup in target/.

  Commit C2 and go ahead with C3.

⏺ Committing C2 first, so it's the baseline for any mutation checks in C3.

⏺ Bash(git add src && git commit -q -m "Add strict sort parsing with default
      order and id tiebreaker…)
  ⎿  (No output)

  Read 2 files (ctrl+o to expand)

⏺ C2 is committed as 17bc626. Before building C3 I'm checking how the existing
  project tests seed and clean data, so the new tests stay consistent and don't
  collide on unique project names.

  Searched for 1 pattern (ctrl+o to expand)

⏺ The existing tests clean tasks first and then projects, so I'll use the same
  pattern. Writing the C3 main code now.
⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/TaskFilter.java)
  ⎿  Wrote 24 lines to
     src/main/java/dev/jasmeet/taskmanager/task/TaskFilter.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import java.time.LocalDate;
       4 import java.util.List;
       5 import java.util.Objects;
       6
       7 /**
       8  * The optional filters of GET /tasks. A null or empty value mea
         ns "no filter on this".
       9  * Several values in one list mean OR (status=TODO&status=DONE).
     … +16 lines (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/TaskSpecifications.java)
  ⎿  Wrote 63 lines to
     src/main/java/dev/jasmeet/taskmanager/task/TaskSpecifications.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import org.springframework.data.jpa.domain.Specification;
       4
       5 import java.time.LocalDate;
       6 import java.util.List;
       7
       8 /**
       9  * One small Specification per filter, combined with AND. A filt
         er that was not supplied becomes
     … +54 lines (ctrl+o to expand)

⏺ Write(src/main/java/dev/jasmeet/taskmanager/task/TaskRepository.java)
  ⎿  Added 15 lines, removed 1 line
       1  package dev.jasmeet.taskmanager.task;
       2  
       3 +import org.springframework.data.domain.Page;                   
       4 +import org.springframework.data.domain.Pageable;               
       5 +import org.springframework.data.jpa.domain.Specification;      
       6 +import org.springframework.data.jpa.repository.EntityGraph;    
       7  import org.springframework.data.jpa.repository.JpaRepository;
       8 +import org.springframework.data.jpa.repository.JpaSpecification
         +Executor;                                                      
       9  
       5 -public interface TaskRepository extends JpaRepository<Task, Lon
         -g> {                                                           
      10 +public interface TaskRepository extends JpaRepository<Task, Lon
         +g>, JpaSpecificationExecutor<Task> {                           
      11 +                                                               
      12 +    /**                                                        
      13 +     * The list endpoint. The entity graph loads each task's pr
         +oject in the same SELECT (a LEFT JOIN),                        
      14 +     * so building the response does not run one query per proj
         +ect (the N+1 measured in M3).                                  
      15 +     * The project stays LAZY everywhere else.                 
      16 +     */                                                        
      17 +    @Override                                                  
      18 +    @EntityGraph(attributePaths = "project")                   
      19 +    Page<Task> findAll(Specification<Task> spec, Pageable pagea
         +ble);                                                          
      20  }

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 9 lines, removed 3 lines
      40      }
      41  
      42      /** @param sort the raw values of the "sort" param. TaskSor
          t validates them and adds the default and the id tiebreaker. */
      43 -    public PageResponse<TaskResponse> list(int page, int size, 
         -List<String> sort) {                                           
      44 -        // N+1 (fixed later in M4): the .map below reads each t
         -ask's project name, one SELECT per distinct project.           
      43 +    public PageResponse<TaskResponse> list(TaskFilter filter, i
         +nt page, int size, List<String> sort) {                        
      44 +        // Both dates are inclusive, so equal dates mean "due e
         +xactly that day". A backwards range is                         
      45 +        // almost certainly swapped dates, and an empty list wo
         +uld hide the mistake.                                          
      46 +        if (filter.dueAfter() != null && filter.dueBefore() != 
         +null && filter.dueAfter().isAfter(filter.dueBefore())) {       
      47 +            throw new InvalidRequestException("dueAfter", "must
         + not be later than dueBefore");                                
      48 +        }                                                      
      49          PageRequest pageRequest = PageRequest.of(page, size, Ta
          skSort.resolve(sort));
      46 -        return PageResponse.from(taskRepository.findAll(pageReq
         -uest).map(TaskResponse::from));                                
      50 +        // The repository fetches the project in the same query
         +, so the mapping below runs no extra SELECTs.                  
      51 +        return PageResponse.from(                              
      52 +                taskRepository.findAll(TaskSpecifications.from(
         +filter), pageRequest).map(TaskResponse::from));                
      53      }
      54  
      55      @Transactional

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskService.java)
  ⎿  Added 1 line
      1  package dev.jasmeet.taskmanager.task;
      2  
      3 +import dev.jasmeet.taskmanager.common.InvalidRequestException;  
      4  import dev.jasmeet.taskmanager.common.NotFoundException;
      5  import dev.jasmeet.taskmanager.common.PageResponse;
      6  import dev.jasmeet.taskmanager.project.Project;

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 9 lines, removed 1 line
      45  
      46      @GetMapping
      47      public PageResponse<TaskResponse> list(
      48 +            // A List binds both ?status=TODO&status=DONE and ?
         +status=TODO,DONE. A bad value in either form                   
      49 +            // fails the conversion, and the handler turns that
         + into a 400 naming the param.                                  
      50 +            @RequestParam(required = false) List<Status> status
         +,                                                              
      51 +            @RequestParam(required = false) List<Priority> prio
         +rity,                                                          
      52 +            @RequestParam(required = false) Long projectId,    
      53 +            @RequestParam(required = false) @DateTimeFormat(iso
         + = ISO.DATE) LocalDate dueAfter,                               
      54 +            @RequestParam(required = false) @DateTimeFormat(iso
         + = ISO.DATE) LocalDate dueBefore,                              
      55              @RequestParam(defaultValue = "0") @Min(0) int page,
      56              @RequestParam(defaultValue = "20") @Min(1) @Max(100
          ) int size,
      57              WebRequest webRequest) {
      58          // Read "sort" raw. A List<String> @RequestParam would 
          split a single "priority,desc" on the comma,
      59          // but not when sort is repeated, so the two forms woul
          d parse differently.
      60          String[] sort = webRequest.getParameterValues("sort");
      54 -        return taskService.list(page, size, sort == null ? List
         -.of() : List.of(sort));                                        
      61 +        TaskFilter filter = new TaskFilter(status, priority, pr
         +ojectId, dueAfter, dueBefore);                                 
      62 +        return taskService.list(filter, page, size, sort == nul
         +l ? List.of() : List.of(sort));                                
      63      }
      64  
      65      @GetMapping("/{id}")

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 2 lines
       4  import jakarta.validation.Valid;
       5  import jakarta.validation.constraints.Max;
       6  import jakarta.validation.constraints.Min;
       7 +import org.springframework.format.annotation.DateTimeFormat;   
       8 +import org.springframework.format.annotation.DateTimeFormat.ISO
         +;                                                              
       9  import org.springframework.http.ResponseEntity;
      10  import org.springframework.web.bind.annotation.DeleteMapping;
      11  import org.springframework.web.bind.annotation.GetMapping;

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskController.java)
  ⎿  Added 1 line
      20  import org.springframework.web.servlet.support.ServletUriCompon
          entsBuilder;
      21  
      22  import java.net.URI;
      23 +import java.time.LocalDate;                                    
      24  import java.util.List;
      25  
      26  /** HTTP only: bind and validate input, call one service method
          , return a DTO. */

⏺ Now the handleTypeMismatch fix, so a bad value in a list param gets the "must
  be one of ..." message instead of a generic one.

⏺ Update(src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.jav
        a)
  ⎿  Added 8 lines, removed 1 line
      113          String field = ex instanceof MethodArgumentTypeMismatc
           hException mismatch
      114                  ? mismatch.getName()
      115                  : ex.getPropertyName();
      116 +        Class<?> target = ex.getRequiredType();               
      117 +        // For a List<Status> param the required type is List.
          + The message should name the element type,                    
      118 +        // so "must be one of TODO, IN_PROGRESS, DONE" is show
          +n and not "has an invalid value".                             
      119 +        if (ex instanceof MethodArgumentTypeMismatchException 
          +mismatch && target != null                                    
      120 +                && (Collection.class.isAssignableFrom(target) 
          +|| target.isArray())) {                                       
      121 +            target = mismatch.getParameter().nested().getNeste
          +dParameterType();                                             
      122 +        }                                                     
      123          FieldViolation violation = new FieldViolation(
      117 -                field != null ? field : "parameter", invalidVa
          -lueMessage(ex.getRequiredType()));                            
      124 +                field != null ? field : "parameter", invalidVa
          +lueMessage(target));                                          
      125          return validationFailed(ex, headers, List.of(violation
           ), request);
      126      }
      127  

⏺ Update(src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.jav
        a)
  ⎿  Added 1 line
      31  import java.time.LocalDate;
      32  import java.util.ArrayList;
      33  import java.util.Arrays;
      34 +import java.util.Collection;                                   
      35  import java.util.List;
      36  import java.util.Objects;
      37  import java.util.stream.Collectors;

⏺ Bash(./mvnw -q compile 2>&1 | grep -E "ERROR|WARN.*unchecked" | head; echo
      compiled=$?)
  ⎿  (No output)

⏺ Main code compiles. Now updating the unit tests for the new list signature and
  the spec-based repository call.

⏺ Bash(python3 - <<'EOF'
      import re…)
  ⎿  (No output)

  Read 1 file (ctrl+o to expand)

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 8 lines
      42      @InjectMocks
      43      TaskService taskService;
      44  
      45 +    private static final TaskFilter NO_FILTER = new TaskFilter(
         +null, null, null, null, null);                                 
      46 +                                                               
      47 +    /** Matches any Specification. Typed here once so the gener
         +ic cast is not repeated in every test. */                      
      48 +    @SuppressWarnings("unchecked")                             
      49 +    private static Specification<Task> anySpecification() {    
      50 +        return any(Specification.class);                       
      51 +    }                                                          
      52 +                                                               
      53      private static Project projectWithId(Long id, String name) 
          {
      54          Project project = new Project(name, null);
      55          ReflectionTestUtils.setField(project, "id", id);

⏺ Now the filter integration tests, including the required
  combined-filters-plus-paging test and the repeated vs comma form comparison.

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/TaskFilterIntegrationTest.jav
       a)
  ⎿  Wrote 253 lines to src/test/java/dev/jasmeet/taskmanager/task/TaskFilt
     erIntegrationTest.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import com.jayway.jsonpath.JsonPath;
       4 import dev.jasmeet.taskmanager.project.CreateProjectRequest;
       5 import dev.jasmeet.taskmanager.project.ProjectRepository;
       6 import dev.jasmeet.taskmanager.project.ProjectService;
       7 import org.junit.jupiter.api.BeforeEach;
       8 import org.junit.jupiter.api.Test;
       9 import org.springframework.beans.factory.annotation.Autowired;
      10 import org.springframework.boot.test.context.SpringBootTest;
     … +243 lines (ctrl+o to expand)

⏺ Another fully qualified name slipped in (java.util.Arrays). Fixing it, then
  adding the 400 tests.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskFilterIntegrationTest.ja
        va)
  ⎿  Added 1 line, removed 1 line
      139      }
      140  
      141      private static String[] withPage(String[] filters, String 
           page) {
      142 -        String[] all = java.util.Arrays.copyOf(filters, filter
          -s.length + 2);                                                
      142 +        String[] all = Arrays.copyOf(filters, filters.length +
          + 2);                                                          
      143          all[filters.length] = "page";
      144          all[filters.length + 1] = page;
      145          return all;

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskFilterIntegrationTest.ja
        va)
  ⎿  Added 1 line
      14  import org.springframework.test.web.servlet.request.MockHttpSer
          vletRequestBuilder;
      15  
      16  import java.time.LocalDate;
      17 +import java.util.Arrays;                                       
      18  import java.util.List;
      19  
      20  import static org.assertj.core.api.Assertions.assertThat;

⏺ Update(src/test/java/dev/jasmeet/taskmanager/common/ErrorHandlingIntegrationTe
        st.java)
  ⎿  Added 63 lines
      247                  .andExpect(jsonPath("$.fieldErrors[0].message"
           ).value("must be a valid number"));
      248      }
      249  
      250 +    // ---------- 400 VALIDATION_FAILED: list filters --------
          +--                                                            
      251 +                                                              
      252 +    private ResultActions expectBadParam(ResultActions actions
          +, String param, String message) throws Exception {            
      253 +        return expectProblem(actions, 400, "VALIDATION_FAILED"
          +, "/tasks")                                                   
      254 +                .andExpect(jsonPath("$.fieldErrors.length()").
          +value(1))                                                     
      255 +                .andExpect(jsonPath("$.fieldErrors[0].field").
          +value(param))                                                 
      256 +                .andExpect(jsonPath("$.fieldErrors[0].message"
          +).value(message));                                            
      257 +    }                                                         
      258 +                                                              
      259 +    @Test                                                     
      260 +    void unknownStatus_namesStatusAndListsTheAllowedValues() t
          +hrows Exception {                                             
      261 +        expectBadParam(mockMvc.perform(get("/tasks").param("st
          +atus", "FOO")),                                               
      262 +                "status", "must be one of TODO, IN_PROGRESS, D
          +ONE");                                                        
      263 +    }                                                         
      264 +                                                              
      265 +    @Test                                                     
      266 +    void oneBadValueInARepeatedStatusList_namesStatus() throws
          + Exception {                                                  
      267 +        expectBadParam(mockMvc.perform(get("/tasks").param("st
          +atus", "TODO", "FOO")),                                       
      268 +                "status", "must be one of TODO, IN_PROGRESS, D
          +ONE");                                                        
      269 +    }                                                         
      270 +                                                              
      271 +    @Test                                                     
      272 +    void oneBadValueInACommaStatusList_namesStatus() throws Ex
          +ception {                                                     
      273 +        expectBadParam(mockMvc.perform(get("/tasks").param("st
          +atus", "TODO,FOO")),                                          
      274 +                "status", "must be one of TODO, IN_PROGRESS, D
          +ONE");                                                        
      275 +    }                                                         
      276 +                                                              
      277 +    @Test                                                     
      278 +    void unknownPriority_namesPriorityAndListsTheAllowedValues
          +() throws Exception {                                         
      279 +        expectBadParam(mockMvc.perform(get("/tasks").param("pr
          +iority", "URGENT")),                                          
      280 +                "priority", "must be one of LOW, MEDIUM, HIGH"
          +);                                                            
      281 +    }                                                         
      282 +                                                              
      283 +    @Test                                                     
      284 +    void enumValuesAreCaseSensitive() throws Exception {      
      285 +        expectBadParam(mockMvc.perform(get("/tasks").param("pr
          +iority", "high")),                                            
      286 +                "priority", "must be one of LOW, MEDIUM, HIGH"
          +);                                                            
      287 +    }                                                         
      288 +                                                              
      289 +    @Test                                                     
      290 +    void malformedDates_nameTheParam() throws Exception {     
      291 +        // Not a date, a month that does not exist, a day that
          + does not exist in that month, and no zero padding.           
      292 +        for (String bad : new String[] {"notadate", "2026-13-0
          +1", "2026-02-30", "2026-11-5"}) {                             
      293 +            expectBadParam(mockMvc.perform(get("/tasks").param
          +("dueAfter", bad)),                                           
      294 +                    "dueAfter", "must be a valid date in yyyy-
          +MM-dd format");                                               
      295 +            expectBadParam(mockMvc.perform(get("/tasks").param
          +("dueBefore", bad)),                                          
      296 +                    "dueBefore", "must be a valid date in yyyy
          +-MM-dd format");                                              
      297 +        }                                                     
      298 +    }                                                         
      299 +                                                              
      300 +    @Test                                                     
      301 +    void nonNumericProjectId_namesProjectId() throws Exception
          + {                                                            
      302 +        expectBadParam(mockMvc.perform(get("/tasks").param("pr
          +ojectId", "abc")),                                            
      303 +                "projectId", "must be a valid number");       
      304 +    }                                                         
      305 +                                                              
      306 +    @Test                                                     
      307 +    void dueAfterLaterThanDueBefore_namesDueAfter() throws Exc
          +eption {                                                      
      308 +        expectBadParam(mockMvc.perform(get("/tasks")          
      309 +                        .param("dueAfter", "2026-11-22").param
          +("dueBefore", "2026-11-16")),                                 
      310 +                "dueAfter", "must not be later than dueBefore"
          +);                                                            
      311 +    }                                                         
      312 +                                                              
      313      // ---------- 400 VALIDATION_FAILED: sort ----------
      314  
      315      @Test

⏺ Write(src/test/java/dev/jasmeet/taskmanager/task/TaskListQueryCountTest.java)  ⎿  Wrote 104 lines to
     src/test/java/dev/jasmeet/taskmanager/task/TaskListQueryCountTest.java
       1 package dev.jasmeet.taskmanager.task;
       2
       3 import dev.jasmeet.taskmanager.common.PageResponse;
       4 import dev.jasmeet.taskmanager.project.CreateProjectRequest;
       5 import dev.jasmeet.taskmanager.project.ProjectRepository;
       6 import dev.jasmeet.taskmanager.project.ProjectService;
       7 import jakarta.persistence.EntityManagerFactory;
       8 import org.hibernate.SessionFactory;
       9 import org.hibernate.stat.Statistics;
      10 import org.junit.jupiter.api.BeforeEach;
     … +94 lines (ctrl+o to expand)

⏺ Bash(L=target/c3.log; ./mvnw test > $L 2>&1; echo exit=$?; grep -E "Tests
      run:|<<< FAIL|<<< ERR|BUILD|COMPILATION|ERROR.*\.java" $L | head -40)
  ⎿  (No output)

⏺ Adding JpaSpecificationExecutor introduced a delete(Specification) overload,
  which makes an existing test's delete(any()) ambiguous. Checking that line.

⏺ Bash(sed -n 205,220p
      src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java; grep -n
      "delete(" src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.ja…)
  ⎿  (No output)

⏺ This is a compile-time-only fix: delete(any(Task.class)) keeps the exact same
  meaning. The production call is typed, so it compiled fine.

⏺ Update(src/test/java/dev/jasmeet/taskmanager/task/TaskServiceTest.java)
  ⎿  Added 2 lines, removed 1 line
      211  
      212          assertThatThrownBy(() -> taskService.delete(99L)).isIn
           stanceOf(NotFoundException.class);
      213  
      214 -        verify(taskRepository, never()).delete(any());        
      214 +        // any(Task.class), not any(): JpaSpecificationExecuto
          +r adds a delete(Specification) overload.                      
      215 +        verify(taskRepository, never()).delete(any(Task.class)
          +);                                                            
      216      }
      217  
      218      // ---------- project assignment ----------

⏺ Bash(L=target/c3.log; ./mvnw test > $L 2>&1; echo exit=$?; grep -E "Tests
      run:|<<< FAIL|<<< ERR|BUILD|COMPILATION|ERROR.*\.java" $L | head -40)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ 166 tests pass; next I'm running mutation checks on the uncommitted work, with
  backups kept in target/. · summary

⏺ Bash(B=target/mut && mkdir -p $B
      R=src/main/java/dev/jasmeet/taskmanager/task/TaskRepository.java…)
  ⎿  (No output)
  ⎿  (timeout 8m 20s)

⏺ All three mutations were caught, with mutation C giving the generic "invalid
  value" message I predicted, and everything is restored. Next I'll run the
  manual SQL-log check you asked for, creating tasks in 3 projects and listing
  them, the same way I measured the N+1 in M3. · summary

⏺ Bash((./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=18080
      --logging.level.org.hibernate.SQL=DEBUG" > target/run-sql.log 2>&1 &) ;
      for i in $(seq…)
  ⎿  (No output)
  ⎿  (timeout 2m 30s)

⏺ The list came back empty, so my seed commands probably failed somewhere.
  Checking what actually got created before I trust any SQL output.

⏺ Bash(curl -s localhost:18080/tasks | head -c 700; echo; curl -s
      localhost:18080/projects; echo; grep -c "Hibernate:\|org.hibernate.SQL"
      target/run-sql.log; grep -n "…)
  ⎿  (No output)

⏺ The seed requests never ran. zsh doesn't word-split the unquoted $J variable,
  so curl got a malformed header argument. The app is still up, so I'll redo the
  seeding with explicit headers.

⏺ Bash(cd /Users/jasmeetsingh/dev/task-manager-api
      H='Content-Type: application/json'…)
  ⎿  (No output)

⏺ The response checks out (2 tasks on page 0, project names present, 3 elements
  across 2 pages), but my marker line got overwritten by the app's writes. I'm
  reading the SQL from the log directly next. · summary

  Searched for 1 pattern (ctrl+o to expand)

⏺ The list call turns out to be one SELECT with a LEFT JOIN and all filters in
  the WHERE, plus a count, while the three assign calls each do the expected
  task and project selects. I'm checking the ORDER BY at the end of the list
  query before stopping the app. · summary

⏺ Bash(sed -n 105p target/run-sql.log | sed 's/.*order by/order by/' ;
      PID=$(lsof -ti tcp:18080 -sTCP:LISTEN); echo "pid=$PID"; [ -n "$PID" ] &&
      kill $PID; sleep 2; pk…)
  ⎿  Blocked by auto mode ∙ see
     https://code.claude.com/docs/s/claude-code-auto-mode
     │ Auto mode: [Destructive or Unwanted Actions]

⏺ The classifier denied the kill command, so I won't try another way to stop the
  process. I'll finish the read-only parts first.

⏺ Bash(sed -n 105p target/run-sql.log | sed 's/.*order by/order by/'; git status
      --short)
  ⎿  (No output)

⏺ C3 is built and tested but not committed, and there's one loose end: a demo
  instance of the app is still running on port 18080.

  ./mvnw test passes with 166 tests and no failures.

  What changed
  - TaskFilter.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskFilter.java): a record
    holding the five filters. It drops empty list elements, so status=DONE,
    works.
  - TaskSpecifications.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskSpecifications.java): one
    spec per filter, combined with AND. Dates are inclusive, and a filter that
    wasn't supplied adds no condition.
  - TaskRepository.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskRepository.java):
    findAll(spec, pageable) with @EntityGraph(attributePaths = "project").
  - TaskService.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskService.java): list takes
    the filter, checks the range (400 naming dueAfter), then sorts and queries.
  - TaskController.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskController.java): binds the
    filters as List<Status>, List<Priority>, Long and LocalDate.
  - GlobalExceptionHandler.java
    (src/main/java/dev/jasmeet/taskmanager/common/GlobalExceptionHandler.java):
    the list-element fix, so a bad value in a list says "must be one of TODO,
    IN_PROGRESS, DONE".

  Tests
  - The required combinedFilters_withPagination test is in
    TaskFilterIntegrationTest. It uses status, priority, project and a date
    range with both edge dates, with decoys one day outside each edge. It checks
    page 0 and page 1, totalElements of 3 and totalPages of 2.
  - Your comma-form addition is tested. status=TODO,IN_PROGRESS returns the same
    ids as the repeated form, and the same holds for priority. A trailing comma
    is ignored, and TODO,FOO is a 400 naming status.
  - That class also covers each filter alone, an unknown projectId giving 200
    with an empty list, equal dates meaning exactly that day, and blank values
    meaning not supplied.
  - ErrorHandlingIntegrationTest gained 8 tests for the 400 cases. They include
    an enum value in lowercase, which is rejected (enum matching is
    case-sensitive), and 2026-02-30, which is rejected too.
  - TaskListQueryCountTest (the N+1 regression test) checks that a list uses 1
    statement whether the tasks are in 1 project or 6, and 2 statements for a
    full page (select plus count).
  - TaskServiceTest gained 3 tests, and the M2 paging integration test is
    unchanged.

  One existing test edited for a compile reason. verify(...).delete(any())
  became ambiguous, because JpaSpecificationExecutor adds a
  delete(Specification) overload. It is now delete(any(Task.class)), with the
  same meaning.

  Mutation checks (backups in target/mut/, all restored and verified identical):
  - Remove @EntityGraph: both statement-count tests failed.
  - Make dueAfter exclusive: 4 filter tests failed, including the required
    combined test.
  - Remove the list-element fix: the status tests failed with "has an invalid
    value" instead of "must be one of...", exactly as I predicted in the plan.

  SQL log check (the way I measured it in M3). I ran the app with
  org.hibernate.SQL=DEBUG and listed tasks in 3 projects. The list ran:
  - One SELECT: from tasks left join projects ... where status in (?,?) and
    priority in (?) and due_date>=? order by priority_rank desc, id.
  - One select count(...).
  - No standalone from projects query for the list. The other project selects in
    the log come from the three assign calls.

  Needs your decision. I started that app instance in the background and tried
  to stop it with a kill command. The permission classifier denied it, so I
  stopped there. The process is still running on port 18080, in-memory only.
  Stop it with lsof -ti tcp:18080 | xargs kill, or tell me to try again.

  Smaller notes
  - My first curl seeding silently failed, because zsh doesn't split an unquoted
    $J variable. I redid it correctly. It is not a code issue.
  - I wrote all logs and backups under target/, per your rule.
  - I kept the mutation checks to uncommitted work with backups in target/, as
    you described, and didn't commit on my own.

  Proposed commit: Add task filters, specifications and fetch project in the
  list query.

  Do you want me to commit C3 and move on to C4 (ADR-012, the ADR-010 update,
  FLOW.md, PROGRESS.md and the Mistakes Log entries)?

❯ Commit C3 and go ahead with C4. In the Mistakes Log, include the stray write
  outside the repo.

⏺ Committing C3 first.

⏺ Bash(git add src && git commit -q -m "Add task filters, specifications and
      fetch project in the list query…)
  ⎿  (No output)

⏺ C3 is committed as 69c36df and the demo app on port 18080 is stopped, so I'm
  moving on to the docs, starting with DECISIONS.md. · summary

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 1 line, removed 1 line
      113  - **Limits: title up to 200 characters, description up to 2000
           .** The `@Size` annotations and the `@Column(length)` on `Task
           ` use the same constants (`Task.TITLE_MAX`, `Task.DESCRIPTION_
           MAX`), so a value cannot pass validation and then fail in the 
           database. Violations say "must be at most N characters".
      114  - **Unknown JSON properties are ignored** (Boot's default). To
           lerant reader: clients don't break if we add fields.
      115  - **A number is rejected for an enum field.** Jackson 3 maps `
           "priority": 1` to MEDIUM by position unless `spring.jackson.da
           tatype.enum.fail-on-numbers-for-enums=true`. We turn it on, so
            it returns 400 `VALIDATION_FAILED` naming the field.
      116 -- **`GET /tasks` takes `page` (default 0, min 0) and `size` (d
          -efault 20, 1 to 100) only, ordered by id.** This applies ADR-0
          -06's default and cap. Sort and filters arrive in M4.          
      116 +- **`GET /tasks` takes `page` (default 0, min 0) and `size` (d
          +efault 20, 1 to 100).** This applies ADR-006's default and cap
          +. A `size` above 100 is a 400, not clamped. *(Amended in M4: t
          +he id-only order was replaced by the default sort in ADR-012, 
          +which keeps id as the last sort key. Filters and `sort` are in
          + ADR-012.)*                                                   
      117  - **Tradeoff:** a client that wants to change one field must r
           esend the others. The UI does that. Unknown properties are not
            reported, so a misspelled optional field is silently dropped.
      118  
      119  ### ADR-011: Projects, unique names and task assignment (M3)

⏺ Update(docs/DECISIONS.md)  ⎿  Added 1 line, removed 1 line
      133    - A missing task or project is 404 `NOT_FOUND`. The task is 
           looked up first, and the detail names whichever is missing.
      134  - **`GET /projects` is a plain list ordered by id, with no pag
           ination.** We expect few projects. `GET /projects/{id}` exists
            so the `Location` header on the 201 points at something.
      135  - **`TaskResponse` gains `projectId` and `projectName`** (null
            when unassigned).
      136 -- **N+1, known and left for M4.** `TaskService.list` maps each
          - task through `TaskResponse.from`, which reads `project.getNam
          -e()` and loads the lazy project. Measured: 3 tasks in 2 projec
          -ts gave 1 task SELECT plus 2 project SELECTs, so one per disti
          -nct project. M4 fixes it by fetching the project in the same q
          -uery. `get` and `update` pay one extra SELECT each.           
      136 +- **N+1, known and left for M4.** `TaskService.list` maps each
          + task through `TaskResponse.from`, which reads `project.getNam
          +e()` and loads the lazy project. Measured: 3 tasks in 2 projec
          +ts gave 1 task SELECT plus 2 project SELECTs, so one per disti
          +nct project. M4 fixes it by fetching the project in the same q
          +uery. `get` and `update` pay one extra SELECT each. *(Fixed fo
          +r the list in M4, see ADR-012. `get`, `update` and the assign 
          +endpoints still pay that one extra SELECT.)*                  
      137  - **No project deletion.** Deleting a project raises the quest
           ion of what happens to its tasks, and that should be decided o
           n purpose. This overrides the earlier pending lean ("support i
           t, unassign tasks"). Next steps, each needing its own decision
           :
      138    - `DELETE /projects/{id}` and what happens to its tasks: una
           ssign them, block the delete while tasks exist, or cascade (th
           e CLAUDE.md trap).
      139    - Project update (`PUT /projects/{id}`), which needs a versi
           on check once projects can change.

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 53 lines, removed 8 lines
      141    - Pagination of `GET /projects` if the list grows.
      142  - **Tradeoff:** a project cannot be renamed or removed through
            the API. Case-variant names can coexist. The list endpoint re
           turns everything.
      143  
      144 +### ADR-012: Filtering, sorting and pagination on `GET /tasks`
          + (M4)                                                         
      145 +                                                              
      146 +Builds on ADR-006 (page/size, `PageResponse`) and ADR-010 (siz
          +e 1 to 100). Every error below is 400 `VALIDATION_FAILED` with
          + the param named in `fieldErrors`, so ADR-005 needs no new cod
          +e.                                                            
      147 +                                                              
      148 +**Priority sorts by meaning, through a rank column**          
      149 +- `Task` has a `priority_rank` column (`LOW`=1, `MEDIUM`=2, `H
          +IGH`=3). Each `Priority` constant carries its rank explicitly.
          + It is never `ordinal()`, because reordering the enum would si
          +lently change what is already stored.                         
      150 +- `Task.assign(...)` sets the rank in the same step as the pri
          +ority, and it is the only writer. There is no getter, and the 
          +rank is not in `TaskResponse`.                                
      151 +- The client sorts by `priority`. `TaskSort` translates that t
          +o the `priorityRank` property, so it works through a normal `P
          +ageable`. Sorting the text column would give HIGH, LOW, MEDIUM
          +. A CASE expression cannot be expressed through `Sort` with Sp
          +ecifications.                                                 
      152 +- **Tradeoff:** one more column, kept in sync by code and not 
          +by the database. Nothing stops a future write path that skips 
          +`assign`.                                                     
      153 +                                                              
      154 +**Filters: `status`, `priority`, `projectId`, `dueAfter`, `due
          +Before`**                                                     
      155 +- `status` and `priority` take several values. OR within one f
          +ilter, AND across filters. Both `?status=TODO&status=DONE` and
          + `?status=TODO,DONE` work and give the same result. Spring bin
          +ds both forms into a `List`.                                  
      156 +- Enum values are case-sensitive (`high` is a 400). A trailing
          + comma is ignored (`?status=DONE,`).                          
      157 +- `projectId` is a filter, not a lookup. An unknown id returns
          + 200 with an empty page, never 404.                           
      158 +- **A blank value means "not supplied"** for every param (`?st
          +atus=`, `?dueAfter=`, `?sort=`). Anything non-blank must be va
          +lid.                                                          
      159 +- Each filter is one small `Specification` in `TaskSpecificati
          +ons`, combined with `Specification.allOf`. A filter that was n
          +ot supplied adds no condition.                                
      160 +                                                              
      161 +**Date range: inclusive on both ends**                        
      162 +- `dueAfter=2026-11-16&dueBefore=2026-11-22` includes both edg
          +e dates. Either one works alone. Equal dates mean "due exactly
          + that day".                                                   
      163 +- **Naming caveat:** "after" and "before" read as exclusive, b
          +ut these are inclusive. The README and the error messages say 
          +so. The names come from the assignment spec.                  
      164 +- Format is strict `yyyy-MM-dd`. `2026-02-30`, `2026-13-01` an
          +d `2026-11-5` are all 400.                                    
      165 +- `dueAfter` later than `dueBefore` is a 400 naming `dueAfter`
          + ("must not be later than dueBefore"). It is almost certainly 
          +swapped dates, and an empty list would hide the mistake. The c
          +heck is in `TaskService`.                                     
      166 +                                                              
      167 +**Sorting: `sort=field[,asc|desc]`, repeatable**              
      168 +- Allowed fields are `dueDate` and `priority`. Anything else (
          +`title`, `id`, `priorityRank`) is a 400. The direction is case
          +-insensitive, the field name is not. The same field twice is a
          + 400.                                                         
      169 +- **Default, with no `sort`:** `dueDate` ascending, then prior
          +ity descending, then id ascending. This answers "what should I
          + do next": deadlines first, urgency breaks ties.              
      170 +- **A client sort replaces the default.** `id` ascending is al
          +ways appended as the last key. Without it, rows that tie on ev
          +ery other key could repeat or vanish across pages. `dueDate` i
          +s `NOT NULL`, so where nulls sort is not a question.          
      171 +- **Strict parser, not Spring's.** Spring Data's resolver is l
          +enient (probed on 4.1.2): `priority,foo` becomes two ascending
          + sorts, `dueDate,desc,priority` becomes three, and `,desc` is 
          +silently ignored. `TaskSort` rejects each of those with a 400 
          +naming `sort`, and reports every bad entry. The result still g
          +oes through a normal `PageRequest`.                           
      172 +- The controller reads `sort` raw (`WebRequest.getParameterVal
          +ues`). A `List<String>` `@RequestParam` splits a single `prior
          +ity,desc` on the comma but not a repeated `sort`, so the two f
          +orms would parse differently. A test proves it: binding the li
          +st made them 400.                                             
      173 +- `page` and `size` stay as validated `@RequestParam` ints, no
          +t a `Pageable` argument. A `Pageable` argument would clamp `si
          +ze` and ignore `page=abc` instead of returning 400.           
      174 +                                                              
      175 +**No N+1**                                                    
      176 +- `TaskRepository.findAll(spec, pageable)` has `@EntityGraph(a
          +ttributePaths = "project")`, so the project comes in the same 
          +SELECT as a LEFT JOIN. The project stays LAZY everywhere else.
          + The join is to-one, so paging stays in SQL.                  
      177 +- Measured with the SQL log: one SELECT with the join, one `co
          +unt`, and no per-project SELECTs. A regression test counts the
          + statements and checks they do not grow with the number of dis
          +tinct projects.                                               
      178 +                                                              
      179 +**Where each validation lives**                               
      180 +                                                              
      181 +| Bad input | Caught by | `fieldErrors.field` |               
      182 +|-----------|-----------|---------------------|               
      183 +| Unknown enum, including inside a list | Spring conversion, t
          +hen `GlobalExceptionHandler.handleTypeMismatch` | `status` or 
          +`priority` |                                                  
      184 +| Malformed or impossible date, non-numeric `projectId`, `page
          +` or `size` | same | the param name |                         
      185 +| `page` below 0, `size` outside 1 to 100 | `@Min` and `@Max` 
          +| `page` or `size` |                                          
      186 +| `dueAfter` later than `dueBefore` | `TaskService`, throws `I
          +nvalidRequestException` | `dueAfter` |                        
      187 +| Bad sort field, direction, shape or duplicate | `TaskSort`, 
          +throws `InvalidRequestException` | `sort` |                   
      188 +                                                              
      189 +- `InvalidRequestException` is new in `common/`. It is for che
          +cks only a service can make, and it maps to the same 400 as Be
          +an Validation.                                                
      190 +- `handleTypeMismatch` now reads the element type of a `List` 
          +param. Before, the required type was `List`, and the message w
          +ould have been "has an invalid value" and not "must be one of 
          +TODO, IN_PROGRESS, DONE".                                     
      191 +- **Known limits.** Conversion errors are reported one param a
          +t a time, the first that fails. The range and sort checks run 
          +after binding, so with both a bad range and a bad sort only th
          +e range is reported.                                          
      192 +                                                              
      193 +**Settled from the M4 pending list:** rank column and not CASE
          +. Inclusive range. Multi-value filters (the earlier lean was s
          +ingle-value). `size` above the max is a 400 (already decided i
          +n ADR-010). Null `dueDate` placement does not apply. `dueAfter
          +` later than `dueBefore` is a 400.                            
      194 +                                                              
      195 +- **Tradeoff:** offset paging can still repeat or skip rows if
          + data changes between requests (ADR-006). The id tiebreaker on
          +ly makes a fixed data set stable. The date param names read as
          + exclusive but are inclusive. `get`, `update` and the assign e
          +ndpoints still pay one extra SELECT for the project.          
      196 +                                                              
      197  ---
      198  
      199  ## Pending (settle in the milestone session, then move up)
      200  
      148 -**M4**                                                        
      149 -- How to sort priority correctly: a rank column derived from t
          -he enum, or a CASE expression in the query.                   
      150 -- Inclusive or exclusive date range. (Lean: inclusive on both 
          -ends.)                                                        
      151 -- Single-value or multi-value filters. (Lean: single value.)  
      152 -- `size` above the max: return 400, or clamp to the max?      
      153 -- Where null `dueDate` values go when sorting. (Lean: last.)  
      154 -- `dueAfter` later than `dueBefore` returns 400?              
      155 -                                                              
      201  **M5**
      202  - Prompt-only JSON or tool use with a forced schema.
      203  - Retry once on malformed output, or fail immediately. (Lean: 
           fail immediately and document a retry as a next step.)

⏺ One claim in ADR-012 overstates what I verified. There's no standing test for
  the comma-splitting behaviour. It was a mutation check I ran, so I'm rewording
  it to say that.

⏺ Update(docs/DECISIONS.md)
  ⎿  Added 1 line, removed 1 line
      169  - **Default, with no `sort`:** `dueDate` ascending, then prior
           ity descending, then id ascending. This answers "what should I
            do next": deadlines first, urgency breaks ties.
      170  - **A client sort replaces the default.** `id` ascending is al
           ways appended as the last key. Without it, rows that tie on ev
           ery other key could repeat or vanish across pages. `dueDate` i
           s `NOT NULL`, so where nulls sort is not a question.
      171  - **Strict parser, not Spring's.** Spring Data's resolver is l
           enient (probed on 4.1.2): `priority,foo` becomes two ascending
            sorts, `dueDate,desc,priority` becomes three, and `,desc` is 
           silently ignored. `TaskSort` rejects each of those with a 400 
           naming `sort`, and reports every bad entry. The result still g
           oes through a normal `PageRequest`.
      172 -- The controller reads `sort` raw (`WebRequest.getParameterVal
          -ues`). A `List<String>` `@RequestParam` splits a single `prior
          -ity,desc` on the comma but not a repeated `sort`, so the two f
          -orms would parse differently. A test proves it: binding the li
          -st made them 400.                                             
      172 +- The controller reads `sort` raw (`WebRequest.getParameterVal
          +ues`). A `List<String>` `@RequestParam` splits a single `prior
          +ity,desc` on the comma but not a repeated `sort`, so the two f
          +orms would parse differently. Confirmed in M4 by temporarily b
          +inding a `List<String>`: the sort integration tests then retur
          +ned 400. The existing single-value `sort=priority,desc` tests 
          +guard against that change.                                    
      173  - `page` and `size` stay as validated `@RequestParam` ints, no
           t a `Pageable` argument. A `Pageable` argument would clamp `si
           ze` and ignore `page=abc` instead of returning 400.
      174  
      175  **No N+1**

⏺ Update(docs/FLOW.md)
  ⎿  Added 25 lines, removed 10 lines
      32  
      33  ## List tasks: `GET /tasks?status=&priority=&dueAfter=&dueBefor
          e=&projectId=&page=&size=&sort=`
      34  
      35 -As built in M2, only `page` (default 0) and `size` (default 20,
         - 1 to 100) exist, ordered by id. Bad values return 400 VALIDATI
         -ON_FAILED naming the param. The filters and `sort` below arrive
         - in M4.                                                        
      35 +As built in M4 (ADR-012). All params are optional and combine. 
         +A blank value means "not supplied".                            
      36  
      37 -As built in M3, each `TaskResponse` carries `projectId` and `pr
         -ojectName`, and the project is NOT fetched in the same query ye
         -t. `TaskResponse.from` reads the project name through the lazy 
         -proxy, so a page costs 1 task query plus 1 query per distinct p
         -roject on it (N+1, measured). M4 fixes this.                   
      38 -                                                               
      37  ```
      40 -query params → filter values + Pageable                        
      41 -  bad enum / date / sort field / size → 400 VALIDATION_FAILED n
         -aming the param                                                
      42 -→ TaskService.list                                             
      43 -  build Specification: AND of each non-null filter             
      44 -  translate sort (priority uses its rank, not its name)        
      45 -  repository.findAll(spec, pageable), project fetched in the sa
         -me query                                                       
      46 -  map Page<Task> → PageResponse<TaskResponse>                  
      38 +query params                                                   
      39 +→ TaskController.list            Spring binds and converts the 
         +params                                                         
      40 +    status, priority   List of enum   ?status=TODO&status=DONE 
         + or  ?status=TODO,DONE (same result)                           
      41 +    projectId          Long                                    
      42 +    dueAfter, dueBefore LocalDate     strict yyyy-MM-dd        
      43 +    page (default 0, min 0), size (default 20, 1 to 100)      @
         +Min / @Max                                                     
      44 +    sort               read raw from the request (a List param 
         +would split "priority,desc" on the comma)                      
      45 +  bad enum (also inside a list) / bad date / non-numeric number
         + / page or size out of range                                   
      46 +                                   → 400 VALIDATION_FAILED nami
         +ng that param (first failing param only)                       
      47 +  builds a TaskFilter record                                   
      48 +→ TaskService.list(filter, page, size, sort)                   
      49 +  dueAfter later than dueBefore    → InvalidRequestException → 
         +400 VALIDATION_FAILED, field dueAfter                          
      50 +  TaskSort.resolve(sort)                                       
      51 +    nothing supplied               → dueDate asc, priority desc
         + (priorityRank), id asc                                        
      52 +    supplied                       → each entry checked: field 
         +in {dueDate, priority}, direction asc|desc,                    
      53 +                                     no duplicate field; priori
         +ty becomes priorityRank; id asc appended last                  
      54 +    any bad entry                  → InvalidRequestException → 
         +400 VALIDATION_FAILED, field sort                              
      55 +  TaskSpecifications.from(filter)  AND of one Specification per
         + supplied filter                                               
      56 +                                   (OR inside status and priori
         +ty, dates inclusive on both ends)                              
      57 +  taskRepository.findAll(spec, PageRequest)                    
      58 +→ Repository (Hibernate → H2)      @EntityGraph("project"): ONE
         + SELECT with LEFT JOIN projects, plus a count                  
      59 +  map Page<Task> → PageResponse<TaskResponse> inside the transa
         +ction (no extra SELECTs)                                       
      60  → 200 { content, page, size, totalElements, totalPages }
      61  ```
      62  
      63 +An unknown `projectId` is a filter that matches nothing: 200 wi
         +th an empty page, never 404. Measured with the SQL log in M4: a
         + page of tasks in 3 different projects ran one task-plus-projec
         +t SELECT and one count, and no per-project SELECTs (the N+1 see
         +n in M3 is gone). The rank column `priority_rank` is internal a
         +nd never appears in the response.                              
      64 +                                                               
      65  ## Update a task: `PUT /tasks/{id}` (with optimistic locking fr
          om M6)
      66  
      67  ```

  Read 1 file (ctrl+o to expand)

⏺ Bash(ls transcripts 2>/dev/null; git show --stat --oneline fe9e8c2 | head -5)
  ⎿  (No output)

⏺ The M3 row still says the transcript export is pending, though
  transcripts/03-projects.md was committed in fe9e8c2, so I'm correcting that
  row as well. Next I'm updating PROGRESS.md: the status table, the session
  entry, and the Mistakes Log. · summary

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 2 lines, removed 2 lines
       7  | M0: Decisions and docs | Done | n/a |
       8  | M1: Scaffold | Done | transcripts/01-scaffold.md |
       9  | M2: Task CRUD and error handling | Done | transcripts/02-task
          -crud.md |
      10 -| M3: Project entity and association | Done | (export pending: 
         -`transcripts/03-projects.md`) |                                
      11 -| M4: Filtering, sorting, pagination | Not started | |         
      10 +| M3: Project entity and association | Done | transcripts/03-pr
         +ojects.md |                                                    
      11 +| M4: Filtering, sorting, pagination | Done | (export pending: 
         +`transcripts/04-filtering.md`) |                               
      12  | M5: AI suggest endpoint | Not started | |
      13  | M6: Optimistic locking | Not started | |
      14  | M7: UI | Not started | |

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 21 lines
      29  - Left for next session:
      30  ```
      31  
      32 +### Session 04: M4 Filtering, sorting and pagination (2026-11-1
         +8)                                                             
      33 +- Built (four commits: `5c56930` rank, `17bc626` sorting, `69c3
         +6df` filters, plus the docs commit):                           
      34 +  - Priority rank: `Priority` constants carry explicit ranks (L
         +OW=1, MEDIUM=2, HIGH=3, never `ordinal()`). `Task.priorityRank`
         + (`priority_rank` column) is set only in `Task.assign`, has no 
         +getter, and is not in `TaskResponse`.                          
      35 +  - Sorting: `TaskSort`, a strict parser for `sort=field[,asc|d
         +esc]` (repeatable). Whitelist `dueDate` and `priority` (`priori
         +ty` becomes `priorityRank`). Default `dueDate asc, priority des
         +c, id asc`. A client sort replaces the default, and `id asc` is
         + always last. `InvalidRequestException` in `common/`, with a ha
         +ndler (400 `VALIDATION_FAILED`).                               
      36 +  - Filters: `TaskFilter` record and `TaskSpecifications` (one 
         +`Specification` per filter, `allOf`). `status` and `priority` t
         +ake several values (repeated or comma form), `projectId`, `dueA
         +fter` and `dueBefore` (inclusive). A backwards range is a 400 n
         +aming `dueAfter`.                                              
      37 +  - N+1 fixed: `TaskRepository.findAll(spec, pageable)` with `@
         +EntityGraph(attributePaths = "project")`.                      
      38 +  - `GlobalExceptionHandler.handleTypeMismatch` reads the eleme
         +nt type of a `List` param, so a bad status in a list says "must
         + be one of TODO, IN_PROGRESS, DONE".                           
      39 +  - Removed the PLAN-era comments about the N+1 from `TaskServi
         +ce` (the one in `TaskResponse.from` still describes the lazy pr
         +oxy and is accurate).                                          
      40 +- Decisions settled: ADR-012 (all six M4 pending items resolved
         +). ADR-010's id-only order and ADR-011's "N+1 left for M4" are 
         +amended with pointers to ADR-012. No ADR-005 change: every new 
         +error is `VALIDATION_FAILED`.                                  
      41 +- Tests: `./mvnw test` passed, 166 tests, 0 failures (1 context
         +, 9 `ProjectServiceTest`, 16 `ProjectApiIntegrationTest`, 21 `T
         +askServiceTest`, 17 `TaskProjectIntegrationTest`, 14 `TaskApiIn
         +tegrationTest`, 42 `ErrorHandlingIntegrationTest`, 4 `PriorityR
         +ankIntegrationTest`, 14 `TaskSortTest`, 10 `TaskSortIntegration
         +Test`, 16 `TaskFilterIntegrationTest`, 2 `TaskListQueryCountTes
         +t`). The required combined-filters-plus-paging test is `TaskFil
         +terIntegrationTest.combinedFilters_withPagination_returnsTheRig
         +htRowsAndTotals`.                                              
      42 +  - M2 paging tests: the integration test `getList_pagesInIdOrd
         +er` and every M2 error test passed unchanged. The unit test `li
         +st_requestsPageSortedByIdAndMapsToPageResponse` was changed (re
         +named to `list_requestsPageWithDefaultSortAndMapsToPageResponse
         +`), because it asserted `Sort.by("id")` and mocked `findAll(Pag
         +eable)`, both of which this milestone removes. One more test ed
         +it for a compile reason: `delete(any())` became `delete(any(Tas
         +k.class))`, because `JpaSpecificationExecutor` adds a `delete(S
         +pecification)` overload.                                       
      43 +  - Mutation checks, each restored afterwards: priority sorted 
         +on the text column (5 sort tests failed), no id tiebreaker (5 u
         +nit tests and the paging test failed), sort bound as `List<Stri
         +ng>` (400s), no `@EntityGraph` (both statement-count tests fail
         +ed), `dueAfter` made exclusive (4 filter tests failed, includin
         +g the combined one), list-element fix removed (status messages 
         +became "has an invalid value").                                
      44 +- Manually verified: `./mvnw spring-boot:run` on port 18080 wit
         +h `org.hibernate.SQL=DEBUG`, 3 projects with one task each. `GE
         +T /tasks?status=TODO,IN_PROGRESS&priority=HIGH&dueAfter=2026-11
         +-21&sort=priority,desc&size=2` returned 200 with 2 tasks, proje
         +ct names filled in, `totalElements` 3 and `totalPages` 2. The S
         +QL for that call was one SELECT (`from tasks left join projects
         + ... where status in (?,?) and priority in (?) and due_date>=? 
         +order by priority_rank desc, id`) plus one `count`, and no per-
         +project SELECT. That is the only manual check this session, so 
         +the 400 bodies were verified by tests only, not by hand.       
      45 +- Known gaps and limits:                                       
      46 +  - Conversion errors are reported one param at a time (the fir
         +st that fails). With both a bad range and a bad sort, only the 
         +range is reported.                                             
      47 +  - `dueAfter` and `dueBefore` are inclusive although the names
         + read as exclusive. Say so in the README (M8).                 
      48 +  - `priority_rank` stays in sync by code (`assign` is the only
         + writer), not by a database constraint.                        
      49 +  - `get`, `update` and the assign endpoints still pay one extr
         +a SELECT for the project.                                      
      50 +  - Offset paging can still repeat or skip rows if data changes
         + between requests (ADR-006).                                   
      51 +- Left for next session: M5 (AI suggest endpoint). The M4 trans
         +cript export is still to do. Nothing is pushed.                
      52 +                                                               
      53  ### Session 03: M3 Project entity and association (2026-11-18)
      54  - Built:
      55    - `project/`: `Project` (named unique constraint `uk_projects
          _name`), `ProjectRepository`, `CreateProjectRequest`, `ProjectR
          esponse`, `ProjectService`, `ProjectController` (`POST /project
          s`, `GET /projects`, `GET /projects/{id}`).

⏺ I'm correcting a stale comment in TaskResponse.from that still credits M4 with
  fixing the N+1, and updating my progress note to match. · summary

⏺ Update(src/main/java/dev/jasmeet/taskmanager/task/TaskResponse.java)
  ⎿  Added 3 lines, removed 2 lines
      15          String projectName) {
      16  
      17      public static TaskResponse from(Task task) {
      18 -        // task.getProject() is a lazy proxy. getId() needs no 
         -query, but getName() loads the project                         
      19 -        // with one SELECT. In TaskService.list this runs once 
         -per task: the N+1 that M4 fixes.                               
      18 +        // task.getProject() can be a lazy proxy. getId() needs
         + no query, but getName() loads the project                     
      19 +        // with one SELECT. The list query fetches the project 
         +in the same SELECT (see TaskRepository), so                    
      20 +        // the list pays nothing extra. get, update and the ass
         +ign endpoints still pay that one SELECT.                       
      21          Project project = task.getProject();
      22          return new TaskResponse(
      23                  task.getId(),

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 1 line, removed 1 line
      36    - Filters: `TaskFilter` record and `TaskSpecifications` (one 
          `Specification` per filter, `allOf`). `status` and `priority` t
          ake several values (repeated or comma form), `projectId`, `dueA
          fter` and `dueBefore` (inclusive). A backwards range is a 400 n
          aming `dueAfter`.
      37    - N+1 fixed: `TaskRepository.findAll(spec, pageable)` with `@
          EntityGraph(attributePaths = "project")`.
      38    - `GlobalExceptionHandler.handleTypeMismatch` reads the eleme
          nt type of a `List` param, so a bad status in a list says "must
           be one of TODO, IN_PROGRESS, DONE".
      39 -  - Removed the PLAN-era comments about the N+1 from `TaskServi
         -ce` (the one in `TaskResponse.from` still describes the lazy pr
         -oxy and is accurate).                                          
      39 +  - Updated the comments that called the N+1 pending (`TaskServ
         +ice.list`, `TaskResponse.from`).                               
      40  - Decisions settled: ADR-012 (all six M4 pending items resolved
          ). ADR-010's id-only order and ADR-011's "N+1 left for M4" are 
          amended with pointers to ADR-012. No ADR-005 change: every new 
          error is `VALIDATION_FAILED`.
      41  - Tests: `./mvnw test` passed, 166 tests, 0 failures (1 context
          , 9 `ProjectServiceTest`, 16 `ProjectApiIntegrationTest`, 21 `T
          askServiceTest`, 17 `TaskProjectIntegrationTest`, 14 `TaskApiIn
          tegrationTest`, 42 `ErrorHandlingIntegrationTest`, 4 `PriorityR
          ankIntegrationTest`, 14 `TaskSortTest`, 10 `TaskSortIntegration
          Test`, 16 `TaskFilterIntegrationTest`, 2 `TaskListQueryCountTes
          t`). The required combined-filters-plus-paging test is `TaskFil
          terIntegrationTest.combinedFilters_withPagination_returnsTheRig
          htRowsAndTotals`.
      42    - M2 paging tests: the integration test `getList_pagesInIdOrd
          er` and every M2 error test passed unchanged. The unit test `li
          st_requestsPageSortedByIdAndMapsToPageResponse` was changed (re
          named to `list_requestsPageWithDefaultSortAndMapsToPageResponse
          `), because it asserted `Sort.by("id")` and mocked `findAll(Pag
          eable)`, both of which this milestone removes. One more test ed
          it for a compile reason: `delete(any())` became `delete(any(Tas
          k.class))`, because `JpaSpecificationExecutor` adds a `delete(S
          pecification)` overload.

  Read 1 file (ctrl+o to expand)

⏺ Next I'm adding the Mistakes Log entries, including the stray write you asked
  for. I'm also logging a second error: I said the demo app was still running
  without checking, and lsof now shows the port free. · summary

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 6 lines
      108  | 03 | Matched the unique violation with an exact `equalsIgnor
           eCase` on `getConstraintName()`, after checking that the Hiber
           nate method exists, but never checking what H2 puts in it. | H
           2 reports `PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NA
           ME_INDEX_F`: schema-qualified, upper case, with index text. Th
           e match never succeeded, so a duplicate name was a 500, not a 
           409. I had checked the API shape and assumed the value. | The 
           real-database tests (two duplicate cases and the concurrency t
           est) returned 500 on the first run. The unit tests with a hand
           -written name would have passed. | Probed the real value with 
           a throwaway test. `bareConstraintName` keeps the first word an
           d drops the schema prefix, and the result is still compared fo
           r equality, never with `contains`, because that would match a 
           future `uk_projects_name_other`. Unit tests use the real H2 st
           ring and a lookalike (`uk_projects_name_other`). |
      109  | 03 | Wrote two weak tests in `TaskProjectIntegrationTest`: o
           ne asserting no Hibernate proxy fields in a JSON response, and
            one with `"Task 998 not found".replace("998", "999998")` as a
           n expected string. | The first cannot fail, because `TaskRespo
           nse` is a record and never serializes an entity. The second is
            confusing for no reason. Both are noise you would have to def
           end. | My own review of the test file before the first run. Yo
           u did not catch these, I did. | Deleted the first and wrote th
           e literal in the second. |
      110  | 01 | The plan said "git repo exists, no commits assumed". Th
           e docs were already committed. | I checked `.git` and `.gitign
           ore` but never ran `git log`, so the plan rested on an assumpt
           ion. Low impact. | Caught in plan review. | Ran `git log`: one
            commit, `4ae0121 Add project docs and plan`. Check git histor
           y before planning from now on. |
      111 +| 04 | Made a Write call to `/Users/jasmeet/placeholder`: a gu
          +essed path (not even your home directory) with throwaway conte
          +nt, for no purpose connected to the task. | A write outside th
          +e repo with no reason. The tool rejected it (permission denied
          +), so nothing was created, but I should never have issued it, 
          +and I had no good explanation when you asked. It also turned u
          +p that I had put probe classes, `.bak` copies and test logs in
          + the session scratchpad, which is outside the repo too. | You 
          +asked why I wrote it. | Rule from you: all writes stay inside 
          +the repo. Logs, probes and backups now go in `target/` (gitign
          +ored). No stray files were left behind. |                     
      112 +| 04 | Said I would restore mutation-checked files with `git c
          +heckout -- <file>`. | That restores the last commit, so on unc
          +ommitted work it would wipe the work itself, not just the muta
          +tion. | You caught it in review of my plan. | Rule from you: c
          +ommit a chunk before mutation-checking it, then restore with `
          +git checkout`. For uncommitted work, keep the backup in `targe
          +t/`. I used `target/mut/` for the C3 checks and diffed each fi
          +le against its backup afterwards. |                           
      113 +| 04 | Wrote fully qualified names in new test code three time
          +s (`org.junit.jupiter.api.Assertions.assertThrows`, `com.jaywa
          +y.jsonpath.JsonPath.read`, `java.util.Arrays.copyOf`). | CLAUD
          +E.md says to import types, and I had already made this mistake
          + in M2. | My own re-read of each file right after writing it, 
          +before the first run. | Replaced with imports. |              
      114 +| 04 | Added `JpaSpecificationExecutor` to `TaskRepository` wi
          +thout checking what else it adds. | It brings a `delete(Specif
          +ication)` overload, so the existing `verify(taskRepository, ne
          +ver()).delete(any())` in `TaskServiceTest` no longer compiled 
          +("reference to delete is ambiguous"). | The first `./mvnw test
          +` after the change failed to compile. | `delete(any(Task.class
          +))`, with a comment saying why. Same meaning, no behaviour cha
          +nge. |                                                        
      115 +| 04 | First SQL-log check: seeded the app with curl using an 
          +unquoted `$J` header variable. | zsh does not word-split unquo
          +ted variables, so no request was ever sent and the list came b
          +ack empty. My log marker line was also overwritten, because th
          +e app and my `>>` append write at different file offsets. | Th
          +e result was an empty list, so I checked what had been created
          + before trusting any SQL output. | Quoted the header, reseeded
          +, and read the SQL straight from the log. No code was affected
          +. |                                                           
      116 +| 04 | After the permission classifier denied my kill command,
          + I told you the demo app was still running on port 18080 and a
          +sked you to stop it. | I had not checked. When I committed C3,
          + `lsof` showed the port was free. The statement was a guess pr
          +esented as fact. | My own check at the start of the commit ste
          +p. | Corrected in the commit step. Check the real state with `
          +lsof` before reporting it, never infer it from a denied comman
          +d. |                                                          

⏺ Bash(./mvnw test > target/c4.log 2>&1; echo exit=$?; grep -E "Tests
      run:.*[0-9]$|BUILD" target/c4.log | tail -2; git status --short)
  ⎿  (No output)
  ⎿  (timeout 5m)

⏺ The docs are written and the suite is still green at 166. One quick read-only
  check on my claim that the stray write left nothing behind.

  Listed 1 directory (ctrl+o to expand)

⏺ C4 is written but not committed. ./mvnw test still passes with 166 tests and
  no failures. I also confirmed that /Users/jasmeet doesn't exist, so the stray
  write left nothing behind.

  What changed
  - DECISIONS.md (docs/DECISIONS.md):
    - New ADR-012, covering the rank column, filters (repeated and comma forms),
      the inclusive date range, the strict sort parser and why it isn't
      Spring's, the default sort, the id tiebreaker, the N+1 fix, a table of
      where each validation lives, and the known limits.
    - ADR-010's GET /tasks bullet is amended: the id-only order is replaced, and
      a size above 100 is a 400.
    - ADR-011's N+1 note now says the list is fixed and get, update and assign
      still pay one extra SELECT.
    - The whole Pending M4 block is gone, and ADR-012 lists how each item was
      settled.
  - FLOW.md (docs/FLOW.md): the list-tasks flow now describes what was built,
    from param binding through the service, the sort and spec steps, and the
    single SELECT with its join.
  - PROGRESS.md (docs/PROGRESS.md):
    - M4 is marked Done, with the transcript export pending.
    - The M3 row now links transcripts/03-projects.md, which was committed in
      fe9e8c2 while the row still said "pending".
    - New Session 04 entry with the test breakdown, the M2 paging test changes,
      and the mutation results.
    - 6 new Mistakes Log rows.
  - TaskResponse.java
    (src/main/java/dev/jasmeet/taskmanager/task/TaskResponse.java): comment
    only. It still called the N+1 "what M4 fixes". This is the one source file
    in the C4 commit.

  Mistakes Log rows for session 04
  1. The stray write to /Users/jasmeet/placeholder, plus the scratchpad files I
     had also put outside the repo.
  2. Proposing git checkout as the restore for uncommitted work, which you
     caught.
  3. Fully qualified names in new tests, three times, which I caught myself.
  4. Not checking what JpaSpecificationExecutor adds, which broke the compile of
     delete(any()).
  5. The zsh curl seeding that silently sent nothing.
  6. One you didn't ask for: I told you the demo app was still running on port
     18080 after the kill was denied, without checking. When I committed C3,
     lsof showed the port free, so I logged it.

  Things to know before the interview
  - The only manual check this session was the SQL log. The 400 bodies were
    verified by tests only. The README and your own hands-on pass should cover
    them, so you may want to hit a few of them yourself.
  - The dueAfter and dueBefore inclusive-versus-name caveat is recorded in
    ADR-012 and the Known gaps, and flagged for the README in M8.

  Proposed commit: Record M4 decisions, flow and progress.

  Do you want me to commit it? The M4 transcript export
  (transcripts/04-filtering.md) is the remaining step, and that's yours to run.

❯ Fix row 6: your report was correct. The app was still running on 18080 (I
  confirmed it with lsof: java, PID 38254), and I stopped it myself before you
  checked. Your later lsof saw the port free because of my kill. Remove the
  row, or rewrite it as: "Kill was denied by the permission system; user
  stopped the process manually."

  Then commit.

⏺ Understood, and thanks for checking with lsof. The row was wrong: my report
  was correct, and my later check only saw a free port because you had already
  stopped the process. The kill denial isn't a mistake of mine, so I'm removing
  the row.

  Searched for 1 pattern (ctrl+o to expand)

⏺ Update(docs/PROGRESS.md)
  ⎿  Added 1 line, removed 2 lines
      112  | 04 | Said I would restore mutation-checked files with `git c
           heckout -- <file>`. | That restores the last commit, so on unc
           ommitted work it would wipe the work itself, not just the muta
           tion. | You caught it in review of my plan. | Rule from you: c
           ommit a chunk before mutation-checking it, then restore with `
           git checkout`. For uncommitted work, keep the backup in `targe
           t/`. I used `target/mut/` for the C3 checks and diffed each fi
           le against its backup afterwards. |
      113  | 04 | Wrote fully qualified names in new test code three time
           s (`org.junit.jupiter.api.Assertions.assertThrows`, `com.jaywa
           y.jsonpath.JsonPath.read`, `java.util.Arrays.copyOf`). | CLAUD
           E.md says to import types, and I had already made this mistake
            in M2. | My own re-read of each file right after writing it, 
           before the first run. | Replaced with imports. |
      114  | 04 | Added `JpaSpecificationExecutor` to `TaskRepository` wi
           thout checking what else it adds. | It brings a `delete(Specif
           ication)` overload, so the existing `verify(taskRepository, ne
           ver()).delete(any())` in `TaskServiceTest` no longer compiled 
           ("reference to delete is ambiguous"). | The first `./mvnw test
           ` after the change failed to compile. | `delete(any(Task.class
           ))`, with a comment saying why. Same meaning, no behaviour cha
           nge. |
      115 -| 04 | First SQL-log check: seeded the app with curl using an 
          -unquoted `$J` header variable. | zsh does not word-split unquo
          -ted variables, so no request was ever sent and the list came b
          -ack empty. My log marker line was also overwritten, because th
          -e app and my `>>` append write at different file offsets. | Th
          -e result was an empty list, so I checked what had been created
          - before trusting any SQL output. | Quoted the header, reseeded
          -, and read the SQL straight from the log. No code was affected
          -. |                                                           
      116 -| 04 | After the permission classifier denied my kill command,
          - I told you the demo app was still running on port 18080 and a
          -sked you to stop it. | I had not checked. When I committed C3,
          - `lsof` showed the port was free. The statement was a guess pr
          -esented as fact. | My own check at the start of the commit ste
          -p. | Corrected in the commit step. Check the real state with `
          -lsof` before reporting it, never infer it from a denied comman
          -d. |                                                          
      115 +| 04 | First SQL-log check: seeded the app with curl using an 
          +unquoted `$J` header variable. | zsh does not word-split unquo
          +ted variables, so no request was ever sent and the list came b
          +ack empty. My log marker line was also overwritten, because th
          +e app and my `>>` append write at different file offsets. | Th
          +e result was an empty list, so I checked what had been created
          + before trusting any SQL output. | Quoted the header, reseeded
          +, and read the SQL straight from the log. No code was affected
          +. |                                                           
      116   No newline at end of file

  Searched for 1 pattern (ctrl+o to expand)

⏺ The row is gone and 5 session-04 rows remain. The only other 18080 mention is
  the manual-verification line, which is accurate. Committing now.

⏺ Bash(git add docs src && git commit -q -m "Record M4 decisions, flow and
      progress…)
  ⎿  (No output)

⏺ I removed row 6 from the Mistakes Log, since the kill denial wasn't my
  mistake. C4 is committed as 172793b and the working tree is clean. The tests
  were last run before the row removal, which only touched docs/PROGRESS.md.

  M4 commits:
  - 5c56930: add priority rank column for sorting by meaning
  - 17bc626: add strict sort parsing with default order and id tiebreaker
  - 69c36df: add task filters, specifications and fetch project in the list
    query
  - 172793b: record M4 decisions, flow and progress

  M4 is done: ./mvnw test passes with 166 tests and no failures, and nothing is
  pushed. The only step left is the transcript export,
  transcripts/04-filtering.md, which is yours to run. PROGRESS.md marks it as
  pending.