# Plan

Interview: Fri Nov 20. Submission deadline: at least 24 hours before the interview slot. Each milestone runs in a fresh Claude Code session, exported to `transcripts/` at the end.

## Milestones and estimated time

| Milestone | Estimate | Session |
|-----------|----------|---------|
| M0: Decisions and docs | done | planning |
| M1: Scaffold | 1 hr | 01 |
| M2: Task CRUD and error handling | 2 to 2.5 hrs | 02 |
| M3: Project entity and association | 1 to 1.5 hrs | 03 |
| M4: Filtering, sorting, pagination | 1.5 to 2 hrs | 04 |
| M5: AI suggest endpoint | 2 to 2.5 hrs | 05 |
| M6: Optimistic locking | 1 hr | 06 |
| M7: UI | 1 hr | 07 |
| M8: Hardening and submission prep | 2 hrs | 08 |

Total build: roughly 12 to 14 hours. After the build: understand every file, build PREP.md, rehearse live extensions, then submit with buffer before the deadline.

If time gets tight, M7 can be trimmed to the bare requirements. M8 is never skipped.

## Session loop (every milestone)

1. Fresh session. Read CLAUDE.md, PLAN.md, DECISIONS.md, PROGRESS.md.
2. Plan mode first. Claude proposes the plan, I review it and push back, then approve.
3. Settle that milestone's pending decisions and record them in DECISIONS.md.
4. Implement. I read every diff.
5. `./mvnw test` passes, and I hit the endpoints myself.
6. Small commits.
7. Update PROGRESS.md (with the Agent Mistakes Log) and FLOW.md.
8. `/export` to `transcripts/NN-name.md`.

---

## M1: Scaffold

**Build**
- Generate from start.spring.io: Maven, Java 17, latest stable Spring Boot, group `dev.jasmeet`, artifact `taskmanager`. Dependencies: Spring Web, Spring Data JPA, Validation, H2.
- `application.properties`: H2 in-memory, `spring.jpa.open-in-view=false`, H2 console at `/h2-console` for demos.
- No empty feature packages. `task/`, `project/`, `ai/` and `common/` appear in M2 and later, when real classes go in.
- README skeleton with the run and test commands.

**Done when**
- `./mvnw spring-boot:run` starts on JDK 17 with no env vars.
- `./mvnw test` passes (context-loads test).
- `mvnw` is executable in git.

## M2: Task CRUD and error handling

**Build**
- `Priority` (LOW, MEDIUM, HIGH) and `Status` (TODO, IN_PROGRESS, DONE) enums.
- `Task` entity: `id` (auto-generated), `title` (required), `description` (optional), `dueDate`, `priority`, `status`.
- `CreateTaskRequest`, `UpdateTaskRequest`, `TaskResponse` records.
- `TaskService` and `TaskController`:
  - `POST /tasks` returns 201 with a `Location` header
  - `GET /tasks` returns a `PageResponse` (filters come in M4)
  - `GET /tasks/{id}` returns 200 or 404
  - `PUT /tasks/{id}` returns 200 or 404
  - `DELETE /tasks/{id}` returns 204 or 404
- `GlobalExceptionHandler` producing the ADR-005 body for:
  - Bean Validation failures (body) with every failing field listed
  - unknown enum values and malformed dates in the body, naming the field
  - invalid JSON syntax
  - type mismatches in path and query params (e.g. `/tasks/abc`)
  - not found, including unknown routes
  - unexpected exceptions: logged, generic 500, no stack trace

**Tests**
- Unit test (happy path) for every `TaskService` method.
- Integration test (`@SpringBootTest` + MockMvc) for each of the five CRUD endpoints, end to end.
- Validation tests asserting on the error body: missing title, unknown priority, malformed date.
- 404 test asserting the same body shape.

**Done when:** every error that reaches Spring MVC returns the ADR-005 shape, and no response contains a stack trace. Malformed URLs that Tomcat rejects before Spring runs (e.g. `/tasks/%`) return Tomcat's own HTML 400 page, not ADR-005. That is a documented limit, handled by an optional M8 item.

## M3: Project entity and association

**Build**
- `Project` entity (`id`, `name` required, `description` optional), unidirectional `@ManyToOne` from Task, no cascade.
- `POST /projects` (201), `GET /projects`, `GET /projects/{id}`.
- Associate and disassociate a task (shape per the pending M3 decision).
- `TaskResponse` gains `projectId` and `projectName` (null when unassigned).
- Delete behavior per the pending M3 decision.

**Tests**
- Unit tests for every `ProjectService` method and any new `TaskService` methods.
- Integration tests: create and list projects, assign, unassign, 404 for a missing project or task.

## M4: Filtering, sorting, pagination

**Build**
- `GET /tasks` query params: `status`, `priority`, `dueAfter`, `dueBefore`, `projectId`, `page`, `size`, `sort`. All filters combine with AND.
- JPA Specifications, one small spec per filter, composed.
- Sorting by `dueDate` and `priority`, with priority ordered LOW < MEDIUM < HIGH (not alphabetically).
- Whitelisted sort fields. An unknown field returns 400.
- `size` capped per ADR-006, and bad params return 400 naming the param.
- No N+1 when loading tasks with their project (fetch the project in the same query).

**Tests**
- Combined filters plus pagination in one request (e.g. status + priority + date range + projectId, page 0 size 2), asserting content and totals.
- Priority sort order test, ascending and descending.
- 400 for an unknown sort field, an unknown enum in a query param, and a malformed date in a query param.

## M5: AI suggest endpoint

**Build**
- Config: `ai.anthropic.api-key=${ANTHROPIC_API_KEY:}`, model, base URL, connect and read timeouts.
- `AiClient` interface plus `AnthropicAiClient` (`RestClient`, timeouts set explicitly).
- `TaskSuggestionService`: builds the prompt with today's date from `Clock`, calls the client, parses and validates the output, and returns a draft.
- `POST /tasks/suggest`, with request `{ "text": "..." }` (required, max length enforced).
- Response has the same shape as `CreateTaskRequest`:

```json
{
  "title": "Submit quarterly report",
  "description": "Finish and submit the Q4 quarterly report.",
  "dueDate": "2026-11-20",
  "priority": "HIGH",
  "status": "TODO"
}
```

- Server-side validation of model output: valid JSON, non-blank title within limits, priority is a real enum value, dueDate is a valid ISO date or null. Anything else returns 502 `AI_OUTPUT_INVALID`, and the raw output is logged (truncated) server-side.
- Failure mapping per ADR-005: no key returns 503, timeout 504, upstream error 502.
- The API key is never logged.

**Tests (all with the model mocked)**
- Service unit test, happy path.
- Malformed model output (not JSON) returns `AI_OUTPUT_INVALID`.
- Model output with an invalid enum or date returns `AI_OUTPUT_INVALID`.
- Timeout returns 504, missing key returns 503.
- Controller integration test with the `AiClient` mocked.
- Blank `text` returns a 400 with the error body.

**Manual:** one real call with a key set, to confirm the happy path end to end.

## M6: Optimistic locking

**Build**
- `@Version Long version` on Task, included in `TaskResponse`.
- `UpdateTaskRequest.version` is required.
- The service compares the client's version to the current one and throws a conflict on mismatch. It also maps `ObjectOptimisticLockingFailureException` (a concurrent write between read and save) to the same 409.
- 409 `VERSION_CONFLICT` with the ADR-005 body.
- **Watch out (found in M2 review):** `TaskService.update` builds the `TaskResponse` before the transaction commits, and `@Version` only increments at flush. Once versioning exists, the PUT response would carry the OLD version and the client's next update would get a false 409. Fix when building M6: call `saveAndFlush` (or `flush()`) before mapping to the response, and assert the returned version in the integration test.

**Tests**
- Integration test: create the task, update it with the right version (200, version increments), then update again with the old version (409, body asserted).
- Unit test for the conflict path.

**README:** what was built, what was deliberately left out, and what comes next.

## M7: UI

**Build** a single `src/main/resources/static/index.html` served at `/`:
- Task list with at least one working filter (status, plus priority and project if cheap), and next and previous page buttons.
- Create-task form (with a project dropdown).
- Suggest box: text in, draft shown, then a "Create this task" button that posts the draft.
- Error bodies are displayed readably, not swallowed.

**Done when** a reviewer can do all of the above without a REST client.

## M8: Hardening and submission prep

- Full `./mvnw test` passes.
- Optional: make errors rejected by Tomcat before Spring MVC runs (malformed URLs such as `/tasks/%`, `/%`, `/tasks/1%00`) return the ADR-005 body, via a custom `ErrorController`. Today they return Tomcat's HTML 400 page. It contains no stack trace or exception message, because `server.error.include-stacktrace` and `include-message` are `never`, but it is not ADR-005.
- README complete:
  - setup and the single run command
  - how to run tests
  - how to set `ANTHROPIC_API_KEY` (and that the app runs without it)
  - an endpoint table, with pagination, sorting and filter params documented
  - the error format with an example
  - the AI endpoint description, JSON schema, and an example request and response
  - a Design Decisions section (2 or 3 picks, with tradeoffs)
  - the stretch feature: built, left out, next
- Fresh clone test: clone into a temp folder, run with JDK 17 and no API key, hit every endpoint and the UI.
- Transcripts: every session exported in order in `transcripts/`, plus a root `transcript.md` that concatenates them in order (the spec expects `transcript.md` at the root).
- Zip layout: `src/`, `pom.xml`, `mvnw`, `.mvn/`, `README.md`, `transcript.md`, plus `docs/` and `transcripts/`.

---

## Requirements traceability

Every item from the assignment spec, mapped to where it's built. Tick each one off in M8.

**Stack**
- [ ] Java 17 (M1)
- [ ] Spring Boot latest stable (M1)
- [ ] Maven (M1)
- [ ] H2 in-memory (M1)

**1. Task CRUD with a relational element**
- [ ] Task fields: id, title (required), description (optional), dueDate, priority, status (M2)
- [ ] POST, GET list, GET by id, PUT, DELETE on `/tasks` (M2)
- [ ] One relational entity, modeled properly (M3)
- [ ] Create, list, associate and disassociate for that entity (M3)

**2. Querying**
- [ ] Filter by status, priority, dueBefore and dueAfter (M4)
- [ ] Filter by project (M4)
- [ ] Sort by dueDate and priority (M4)
- [ ] Pagination, documented (M4, M8)
- [ ] Filters combinable in one request (M4)

**3. Validation and errors**
- [ ] 400 with a structured body naming the failed fields: missing required fields, malformed dates, unknown enums (M2, M4)
- [ ] 404 with the same body shape (M2)
- [ ] Format chosen deliberately and explainable (ADR-005)

**4. AI endpoint**
- [ ] Calls a real model (M5)
- [ ] Validates model output server-side, with a clean error on malformed output (M5)
- [ ] Degrades cleanly on timeout and upstream errors (M5)
- [ ] Stable JSON schema documented in the README (M5, M8)

**5. Stretch feature**
- [ ] Optimistic locking with 409 on concurrent update (M6)
- [ ] README: built, left out, next (M6, M8)

**6. UI**
- [ ] View the task list with at least one filter (M7)
- [ ] Create a task (M7)
- [ ] Trigger the AI endpoint and display the result (M7)

**7. Tests (pass with `./mvnw test`)**
- [ ] At least one unit test per service-layer method, happy path (M2 to M6)
- [ ] Integration test exercising each CRUD endpoint end to end with the Spring context (M2)
- [ ] Combined filters plus pagination test (M4)
- [ ] Validation failure test asserting the error body (M2)
- [ ] AI endpoint test with the model mocked (M5)
- [ ] Malformed model output test (M5)

**8. README**
- [ ] Clone, one command, running (M8)
- [ ] AI endpoint description, schema, example (M8)
- [ ] Design Decisions section with 2 or 3 tradeoffs (M8)

**Build and submission**
- [ ] `./mvnw spring-boot:run` works cold on JDK 17 (M8)
- [ ] Transcript(s) included in order (M8)
- [ ] Zip sent to everyone CC'd with subject `Backend Engineer Pre-Onsite — Jasmeet Singh Wadhwa` (M8)
