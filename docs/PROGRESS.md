# Progress

## Milestone status

| Milestone | Status | Transcript |
|-----------|--------|------------|
| M0: Decisions and docs | Done | n/a |
| M1: Scaffold | Done | transcripts/01-scaffold.md |
| M2: Task CRUD and error handling | Done | transcripts/02-task-crud.md |
| M3: Project entity and association | Done | (export pending: `transcripts/03-projects.md`) |
| M4: Filtering, sorting, pagination | Not started | |
| M5: AI suggest endpoint | Not started | |
| M6: Optimistic locking | Not started | |
| M7: UI | Not started | |
| M8: Hardening and submission prep | Not started | |

## Session log

Newest first. One entry per Claude Code session.

### Template

```
### Session NN: MX name (date)
- Built:
- Decisions settled: (ADR numbers or pending items resolved)
- Tests: `./mvnw test` result (passed / failed, count)
- Manually verified:
- Left for next session:
```

### Session 03: M3 Project entity and association (2026-11-18)
- Built:
  - `project/`: `Project` (named unique constraint `uk_projects_name`), `ProjectRepository`, `CreateProjectRequest`, `ProjectResponse`, `ProjectService`, `ProjectController` (`POST /projects`, `GET /projects`, `GET /projects/{id}`).
  - `common/`: generic `ConflictException` and its handler (409 with a code and field errors chosen by the service).
  - `Task`: nullable lazy `@ManyToOne Project` with no cascade, `moveTo` and `removeFromProject`. `TaskResponse` gains `projectId` and `projectName`.
  - `TaskService.assignProject` and `unassignProject`, with `PUT /tasks/{id}/project/{projectId}` (200) and `DELETE /tasks/{id}/project` (204).
- Decisions settled: ADR-011 (fields, database-enforced unique names caught by constraint name, case-sensitive and trimmed, assignment endpoints and idempotence, plain project list, no project deletion). ADR-005 amended with `DUPLICATE_PROJECT_NAME`. All three pending M3 items resolved. Project deletion is recorded as a next step.
- Tests: `./mvnw test` passed, 103 tests, 0 failures (1 context, 9 `ProjectServiceTest`, 16 `ProjectApiIntegrationTest`, 17 `TaskServiceTest`, 17 `TaskProjectIntegrationTest`, 14 `TaskApiIntegrationTest`, 29 `ErrorHandlingIntegrationTest`).
  - The concurrent duplicate-name test passed five runs in a row.
  - Mutation check: making `Task.assign()` clear the project made `update_leavesTheProjectUntouched` and `putOnTheTask_keepsItsProject` fail. Restored afterwards.
- Manually verified: `./mvnw spring-boot:run` with no env vars, then curl. 201 with `Location`, duplicate gives 409 `DUPLICATE_PROJECT_NAME` as `application/problem+json`, assign (200), repeated assign (200), missing project (404 "Project 99 not found"), missing task (404), unassign twice (204, 204), `GET /tasks` and `GET /projects` show the right fields. No "Unhandled exception" in the app log.
- N+1 measured: 3 tasks in 2 projects ran 1 task SELECT plus 2 project SELECTs (one per distinct project). It sits at `TaskService.list`, in the `.map(TaskResponse::from)` call. M4 fixes it.
- Known gaps and limits:
  - Names are case-sensitive ("Home" and "home" coexist), recorded in ADR-011 and by a test.
  - A `projectId` in a PUT body is silently ignored (tolerant reader, ADR-010), pinned by a test.
  - The constraint-name matching is exercised against H2's reported format. Another database reports names differently, so it would need rechecking if the database changed.
- Left for next session: M4 (filters, sorting, pagination, and the N+1 fix). Project deletion, project update, case-insensitive names and project pagination are listed as next steps in ADR-011. M3 is committed in `6802dfe` (projects) and `fd3a5db` (assignment), plus the docs commit. Not pushed. The transcript export is still to do.

### Session 02: M2 Task CRUD and error handling (2026-11-15)
- Built: Task entity, enums, repository, DTOs, `TaskService`, `TaskController`, `PageResponse`, `GlobalExceptionHandler` (ADR-005 body for every error that reaches Spring MVC), Jackson and `server.error` properties.
- Decisions settled: ADR-010 (Task field rules, PUT semantics, list params, tolerant reader, numbers rejected for enums). ADR-005 amended with `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `CLIENT_ERROR`. CLAUDE.md gained the no-fully-qualified-names convention.
- Tests: `./mvnw test` passed, 52 tests, 0 failures (1 context, 8 `TaskServiceTest`, 14 `TaskApiIntegrationTest`, 29 `ErrorHandlingIntegrationTest`). The safety-net test was shown to fail with the `type` fix removed (`No value at JSON path "$.type"`) and pass with it restored.
- Manually verified: `./mvnw spring-boot:run` with no env vars. All five endpoints, every error row (400 variants, 404, unknown route, 405 with `Allow: DELETE, GET, PUT`, 415) and `Accept: application/xml` (406, `CLIENT_ERROR`) returned the ADR-005 body. App log had no "Unhandled exception" entries.
- Known gaps:
  - The null-response guard in `handleExceptionInternal` has no test. `super` returns null only when the response is already committed, which MockMvc cannot easily reproduce.
  - Errors Tomcat rejects before Spring runs (`/tasks/%`, `/%`, `/tasks/1%00`) return Tomcat's HTML 400 page, not ADR-005. No stack trace or message in it. Optional M8 item in PLAN.md.
- Left for next session: M3. M2 is committed in three commits (`5e7a583`, `8d9933f`, `74463f1`), each with a green `./mvnw test`. Not pushed.

### Session 01: M1 Scaffold (2026-11-15)
- Built: Initializr scaffold at repo root (Boot 4.1.2, Maven, Java 17; web, data-jpa, validation, h2). Merged `.gitignore` (kept `.DS_Store`). `application.properties` with H2 in-memory, `open-in-view=false`, `ddl-auto=create-drop`, H2 console at `/h2-console`. README skeleton. PLAN.md M1 updated: no empty packages, they arrive in M2.
- Decisions settled: ADR-009 (Boot 4.1.2).
- Tests: `./mvnw test` on JDK 17.0.21 passed, 1 test (context loads), 0 failures.
- Manually verified: `./mvnw spring-boot:run` starts in under 1s with no env vars. `/h2-console` redirects to `/h2-console/`, which returns 200 with the "H2 Console" page, so no extra dependency was needed. `/` returns 404, as expected until M7.
- Left for next session: M2. Nothing committed yet. `mvnw` is mode 755 on disk, so it will be executable once added.

### Session 00: M0 Decisions and docs
- Built: CLAUDE.md, PLAN.md, DECISIONS.md, FLOW.md, PROGRESS.md.
- Decisions settled: ADR-001 to ADR-008.
- Tests: n/a.
- Left for next session: M1 scaffold.

## Agent Mistakes Log

Every time the model produced something wrong or poorly structured and I caught it. These are the strongest moments in the transcript and the best interview material, so log them as they happen.

| Session | What the model did | Why it was wrong | How I caught it | Fix |
|---------|--------------------|------------------|-----------------|-----|
| 01 | Asked Initializr for `4.1.2.RELEASE` and put that string in the plan and command without checking Maven Central. The generated pom pointed at a parent version that doesn't exist. | Boot dropped the `.RELEASE` suffix on Central. Initializr's metadata ID doesn't match the published coordinates. | `./mvnw test` failed with "Non-resolvable parent POM". | Changed the pom to `4.1.2`, confirmed it returns 200 on Central, recorded in ADR-009. |
| 02 | Planned the error handler without checking what Jackson 3 does with a number for an enum field. | Jackson 3 maps `"priority": 1` to MEDIUM by position, so the API created a task from it. | You asked me to check. My first test run returned 201 instead of 400. | `spring.jackson.datatype.enum.fail-on-numbers-for-enums=true`, plus a test. |
| 02 | Assumed Spring would serialize `"type": "about:blank"`. ADR-005 shows it. | Spring omits `type` from the JSON unless it is set explicitly. All 27 error tests failed on it. | The first run of the error tests. | `setType` in `respond()`, and later in the `handleExceptionInternal` safety net, which I had missed until you asked. |
| 02 | Wrote the error test with fully qualified names, and a `Task_TITLE_MAX` constant. | Hard to read, and the same pattern you had already rejected twice. | Code review. | Imports, no mirrored constants. |
| 02 | `Task`'s constructor called the public `update()` method. | Calling an overridable method from a constructor is a known pitfall, and Hibernate subclasses entities for proxies, so `update()` cannot be made `final` either. | Code review of `Task.java`. | Both the constructor and `update()` call a private `assign(...)`. |
| 02 | Named the `fieldErrors` record `FieldError`. | Clashes with Spring's `org.springframework.validation.FieldError`, which the exception handler reads, forcing fully qualified names. | Code review before step 2. | Renamed to `FieldViolation`. |
| 02 | Built the `Location` header with `ServletUriComponentsBuilder.fromCurrentRequest()`. | It carries the query string, so `POST /tasks?foo=1` returned `/tasks/7?foo=1`. | Code review of `TaskController`. | `fromCurrentRequestUri()`, plus a test asserting no `?` in `Location`. |
| 02 | The `handleExceptionInternal` override called `getBody()` on the result of `super` without a null check, and the fallback code for unmapped 4xx was `BAD_REQUEST`. | `super` can return null when the response is already committed, and an error handler must never throw. A 406 labelled `BAD_REQUEST` is misleading. | Code review of `GlobalExceptionHandler`. | Null guard added (still untested, see Known gaps). Fallback renamed `CLIENT_ERROR` and added to ADR-005. |
| 03 | Matched the unique violation with an exact `equalsIgnoreCase` on `getConstraintName()`, after checking that the Hibernate method exists, but never checking what H2 puts in it. | H2 reports `PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F`: schema-qualified, upper case, with index text. The match never succeeded, so a duplicate name was a 500, not a 409. I had checked the API shape and assumed the value. | The real-database tests (two duplicate cases and the concurrency test) returned 500 on the first run. The unit tests with a hand-written name would have passed. | Probed the real value with a throwaway test. `bareConstraintName` keeps the first word and drops the schema prefix, and the result is still compared for equality, never with `contains`, because that would match a future `uk_projects_name_other`. Unit tests use the real H2 string and a lookalike (`uk_projects_name_other`). |
| 03 | Wrote two weak tests in `TaskProjectIntegrationTest`: one asserting no Hibernate proxy fields in a JSON response, and one with `"Task 998 not found".replace("998", "999998")` as an expected string. | The first cannot fail, because `TaskResponse` is a record and never serializes an entity. The second is confusing for no reason. Both are noise you would have to defend. | My own review of the test file before the first run. You did not catch these, I did. | Deleted the first and wrote the literal in the second. |
| 01 | The plan said "git repo exists, no commits assumed". The docs were already committed. | I checked `.git` and `.gitignore` but never ran `git log`, so the plan rested on an assumption. Low impact. | Caught in plan review. | Ran `git log`: one commit, `4ae0121 Add project docs and plan`. Check git history before planning from now on. |
