# Decisions

Each decision records what we chose, what else we considered, why, and the tradeoff. Accepted decisions are binding for Claude Code. Pending decisions get settled in the milestone session where they come up, then move up into Accepted.

---

## Accepted

### ADR-001: Maven as the build tool

- **Options:** Maven, Gradle.
- **Why:** Declarative `pom.xml`, the default in most Spring docs, and the wrapper gives the one-command build the spec requires.
- **Tradeoff:** More verbose and slower than Gradle. Irrelevant at this size.

### ADR-002: Project as the relational entity, one-to-many, unidirectional

- **Options:** Project (one-to-many), Tag (many-to-many).
- **Choice:** `Task` has a nullable `@ManyToOne(fetch = LAZY) Project project`. `Project` has no `tasks` collection.
- **Why:** One foreign key column, no join table, no owning-side or collection sync bugs. "Tasks in a project" is just a filter on `GET /tasks`, so Project doesn't need to navigate to its tasks. Unidirectional also rules out JSON recursion.
- **Tradeoff:** No `project.getTasks()` navigation, and no cross-cutting labels. Tags are the natural next extension.

### ADR-003: AI endpoint is `POST /tasks/suggest`, returning an unsaved draft

- **Options:** `/tasks/suggest`, `/tasks/{id}/breakdown`, `/tasks/query`.
- **Why:** The model's output is untrusted. The endpoint turns plain language into a draft task and never writes to the database. The user confirms by calling `POST /tasks`, which runs the normal validation again. The draft has the same shape as the create request, so the UI can submit it directly.
- **Tradeoff:** Two calls to create a task from text. Acceptable, and it keeps a human in the loop.

### ADR-004: Claude via `RestClient` behind an `AiClient` interface

- **Options:** Spring AI, Anthropic Java SDK, plain HTTP with `RestClient`.
- **Why:** Full control over timeouts and error mapping, no framework magic to explain, trivially mockable in tests, and the provider can be swapped behind the interface.
- **Details:** API key from the `ANTHROPIC_API_KEY` env var. Model name, base URL and timeouts are configurable properties. The app starts without a key, and the endpoint returns 503 until one is set.
- **Tradeoff:** We write the request and response DTOs for the Anthropic API ourselves.

### ADR-005: Error format is RFC 9457 Problem Details with two extensions

RFC 9457 replaced RFC 7807. Spring's `ProblemDetail` implements it. Content type is `application/problem+json`.

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/tasks",
  "code": "VALIDATION_FAILED",
  "fieldErrors": [
    { "field": "priority", "message": "must be one of LOW, MEDIUM, HIGH" }
  ]
}
```

- `code` is a stable machine-readable string. `title` and `detail` are for humans.
- `fieldErrors` is always present, empty when no specific field failed, so every client parses one shape for 400, 404, 409 and 5xx.

| Code | Status | When |
|------|--------|------|
| `VALIDATION_FAILED` | 400 | Bean Validation failures, unknown enum values, malformed dates, bad query params |
| `MALFORMED_REQUEST` | 400 | Body is not valid JSON |
| `NOT_FOUND` | 404 | Missing task or project, unknown route |
| `VERSION_CONFLICT` | 409 | Stale version on update (ADR-007) |
| `AI_UPSTREAM_ERROR` | 502 | Model API returned an error or was unreachable |
| `AI_OUTPUT_INVALID` | 502 | Model output could not be parsed or failed validation |
| `AI_NOT_CONFIGURED` | 503 | No API key set |
| `AI_TIMEOUT` | 504 | Model call exceeded the timeout |
| `INTERNAL_ERROR` | 500 | Anything unexpected. Logged server-side, no stack trace returned |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method. The `Allow` header is kept. (Added in M2) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Request Content-Type is not supported. (Added in M2) |
| `CLIENT_ERROR` | 4xx | Fallback for a 4xx status with no specific code above, e.g. 406. (Added in M2) |
| `DUPLICATE_PROJECT_NAME` | 409 | A project with that name already exists. Carries `fieldErrors: [{ "field": "name", "message": "already exists" }]`. (Added in M3) |

- **Amended in M2:** the three rows before `DUPLICATE_PROJECT_NAME` were added so that every status Spring MVC can produce has a stable code. `type` is always `"about:blank"` and is set explicitly, because Spring leaves it out of the JSON otherwise.
- **Amended in M3:** `DUPLICATE_PROJECT_NAME` was added (ADR-011). It is specific, like `VERSION_CONFLICT`, so a client can switch on it. The 409 lists `name` in `fieldErrors` because a specific field failed.
- **Known limit:** errors Tomcat rejects before Spring MVC runs (malformed URLs such as `/tasks/%`) return Tomcat's HTML 400 page, not this body. It leaks no stack trace or message. Optional M8 item.

- **Why:** A real standard that's built into Spring, with one shape for every error and field-level detail where it matters.
- **Tradeoff:** Slightly verbose, and `code` and `fieldErrors` are our own extensions.

### ADR-006: Page/size pagination with a custom response wrapper

- **Options:** page/size, offset/limit, cursor (keyset).
- **Choice:** `page` (0-based) and `size` through Spring's `Pageable`. Default size 20, max 100. Sorting with `sort=field,dir` (Spring's convention), limited to a whitelist of fields that includes `dueDate` and `priority`.
- **Response:** our own `PageResponse<T>` with `content`, `page`, `size`, `totalElements`, `totalPages`. We never serialize Spring's `Page` directly, because its JSON shape isn't stable.
- **Why:** Matches Spring Data, page numbers map cleanly to a UI, and total counts come for free.
- **Tradeoff:** Offset pagination slows on deep pages and can skip or repeat rows when data changes between requests. Cursor pagination fixes both but is overkill here.

### ADR-007: Stretch feature is optimistic locking

- **Choice:** `@Version Long version` on `Task`, returned in every task response. Update requests must include `version`. A mismatch returns 409 `VERSION_CONFLICT`.
- **Why:** Lost updates are a real problem when the same task is edited from two tabs or devices. JPA supports it natively, and the surface area is small.
- **Tradeoff:** Clients must send the version and handle a 409 by re-fetching. There's no automatic merge.

### ADR-008: Code style

- Records for DTOs, no Lombok, package by feature, constructor injection.
- **Why:** Everything is visible in the source, which makes it explainable. Records are idiomatic Java 17.

### ADR-009: Spring Boot 4.1.2

- **Options:** 4.1.2 (latest stable), 4.0.9 (older stable line). 4.2.0.RC2 and snapshots excluded by the "stable only" rule.
- **Why:** Latest stable release from start.spring.io, as CLAUDE.md requires. Supports Java 17.
- **Consequences:** Jackson 3 (`tools.jackson` packages) and `@MockitoBean` in tests. The H2 console is its own module (`spring-boot-h2console`), which Initializr adds to the pom automatically.
- **Note:** Initializr reports the version as `4.1.2.RELEASE`, but Maven Central publishes `4.1.2`. The generated pom had to be corrected to `4.1.2`.
- **Tradeoff:** A newer major line has less community material than Boot 3.x.

### ADR-010: Task field rules and PUT semantics (M2)

- **`dueDate` is required. Past dates are allowed on create and on update.** A create-only check could be bypassed with a PUT anyway, and overdue tasks must stay editable.
- **`priority` is required, with no default.** Priority is a judgment only the user can make, so the server does not guess.
- **`status` is not part of the create request.** Every new task starts as TODO. A `status` sent on create is ignored. On update, `status` is required.
- **PUT is a full replacement.** The client sends `title`, `dueDate`, `priority` and `status` every time. `description` is optional, and omitting it clears it. `CreateTaskRequest` and `UpdateTaskRequest` are separate records.
- **PUT never touches the project.** Project assignment comes in M3 through its own endpoints.
- **Limits: title up to 200 characters, description up to 2000.** The `@Size` annotations and the `@Column(length)` on `Task` use the same constants (`Task.TITLE_MAX`, `Task.DESCRIPTION_MAX`), so a value cannot pass validation and then fail in the database. Violations say "must be at most N characters".
- **Unknown JSON properties are ignored** (Boot's default). Tolerant reader: clients don't break if we add fields.
- **A number is rejected for an enum field.** Jackson 3 maps `"priority": 1` to MEDIUM by position unless `spring.jackson.datatype.enum.fail-on-numbers-for-enums=true`. We turn it on, so it returns 400 `VALIDATION_FAILED` naming the field.
- **`GET /tasks` takes `page` (default 0, min 0) and `size` (default 20, 1 to 100).** This applies ADR-006's default and cap. A `size` above 100 is a 400, not clamped. *(Amended in M4: the id-only order was replaced by the default sort in ADR-012, which keeps id as the last sort key. Filters and `sort` are in ADR-012.)*
- **Tradeoff:** a client that wants to change one field must resend the others. The UI does that. Unknown properties are not reported, so a misspelled optional field is silently dropped.

### ADR-011: Projects, unique names and task assignment (M3)

Builds on ADR-002 (unidirectional, lazy, no cascade).

- **Fields.** `name` is required, up to 100 characters. `description` is optional, up to 500. The `@Size` annotations and the `@Column(length)` on `Project` use the same constants (`Project.NAME_MAX`, `Project.DESCRIPTION_MAX`). The name is trimmed in the service before it is saved.
- **Unique names, enforced by the database.** The `projects` table has a named constraint, `uk_projects_name`. There is no check-then-insert, because two concurrent requests could both pass the check. Only the database can guarantee uniqueness.
  - `ProjectService.create` calls `saveAndFlush` inside its `try` block, so the INSERT runs where the violation can be caught. A flush left for commit time would throw after the method returned, outside the catch, and become a 500. (With `IDENTITY` ids Hibernate inserts immediately anyway, but correctness should not depend on the ID strategy.)
  - The catch recognises a duplicate **by constraint name**, not by "any unique violation". A unique constraint added later would otherwise be mislabelled as a duplicate project name. Any other integrity violation is rethrown and reaches the logged 500 handler.
  - H2 reports the name decorated (`PUBLIC.UK_PROJECTS_NAME INDEX PUBLIC.UK_PROJECTS_NAME_INDEX_F`). `bareConstraintName` keeps the first word and drops the schema prefix, and the result is compared for equality, never with `contains`, so `uk_projects_name_other` does not match.
  - The service throws a generic `ConflictException(code, message, fieldErrors)`, which the handler maps to 409. M6's `VERSION_CONFLICT` reuses it.
- **Names are case-sensitive.** "Home" and "home" can coexist. Case-insensitive uniqueness needs either an H2-only column type (`VARCHAR_IGNORECASE`, not portable) or an extra lowercase key column. Documented limit, and a test records it.
- **Assignment has its own endpoints, and `PUT /tasks/{id}` never touches the project.** `Task` has `moveTo(Project)` and `removeFromProject()` and nothing else changes the project. A `projectId` in a PUT body is ignored like any other unknown property (ADR-010).
  - `PUT /tasks/{id}/project/{projectId}` sets the project, moves the task if it was in another one, and returns 200 with `TaskResponse`. Repeating the call changes nothing (Hibernate sees no difference, so it runs no UPDATE).
  - `DELETE /tasks/{id}/project` returns 204, also when the task had no project. The caller's goal is already met, so there is nothing to fail on.
  - A missing task or project is 404 `NOT_FOUND`. The task is looked up first, and the detail names whichever is missing.
- **`GET /projects` is a plain list ordered by id, with no pagination.** We expect few projects. `GET /projects/{id}` exists so the `Location` header on the 201 points at something.
- **`TaskResponse` gains `projectId` and `projectName`** (null when unassigned).
- **N+1, known and left for M4.** `TaskService.list` maps each task through `TaskResponse.from`, which reads `project.getName()` and loads the lazy project. Measured: 3 tasks in 2 projects gave 1 task SELECT plus 2 project SELECTs, so one per distinct project. M4 fixes it by fetching the project in the same query. `get` and `update` pay one extra SELECT each. *(Fixed for the list in M4, see ADR-012. `get`, `update` and the assign endpoints still pay that one extra SELECT.)*
- **No project deletion.** Deleting a project raises the question of what happens to its tasks, and that should be decided on purpose. This overrides the earlier pending lean ("support it, unassign tasks"). Next steps, each needing its own decision:
  - `DELETE /projects/{id}` and what happens to its tasks: unassign them, block the delete while tasks exist, or cascade (the CLAUDE.md trap).
  - Project update (`PUT /projects/{id}`), which needs a version check once projects can change.
  - Case-insensitive names.
  - Pagination of `GET /projects` if the list grows.
- **Tradeoff:** a project cannot be renamed or removed through the API. Case-variant names can coexist. The list endpoint returns everything.

### ADR-012: Filtering, sorting and pagination on `GET /tasks` (M4)

Builds on ADR-006 (page/size, `PageResponse`) and ADR-010 (size 1 to 100). Every error below is 400 `VALIDATION_FAILED` with the param named in `fieldErrors`, so ADR-005 needs no new code.

**Priority sorts by meaning, through a rank column**
- `Task` has a `priority_rank` column (`LOW`=1, `MEDIUM`=2, `HIGH`=3). Each `Priority` constant carries its rank explicitly. It is never `ordinal()`, because reordering the enum would silently change what is already stored.
- `Task.assign(...)` sets the rank in the same step as the priority, and it is the only writer. There is no getter, and the rank is not in `TaskResponse`.
- The client sorts by `priority`. `TaskSort` translates that to the `priorityRank` property, so it works through a normal `Pageable`. Sorting the text column would give HIGH, LOW, MEDIUM. A CASE expression cannot be expressed through `Sort` with Specifications.
- **Tradeoff:** one more column, kept in sync by code and not by the database. Nothing stops a future write path that skips `assign`.

**Filters: `status`, `priority`, `projectId`, `dueAfter`, `dueBefore`**
- `status` and `priority` take several values. OR within one filter, AND across filters. Both `?status=TODO&status=DONE` and `?status=TODO,DONE` work and give the same result. Spring binds both forms into a `List`.
- Enum values are case-sensitive (`high` is a 400). A trailing comma is ignored (`?status=DONE,`).
- `projectId` is a filter, not a lookup. An unknown id returns 200 with an empty page, never 404.
- **A blank value means "not supplied"** for every param (`?status=`, `?dueAfter=`, `?sort=`). Anything non-blank must be valid.
- Each filter is one small `Specification` in `TaskSpecifications`, combined with `Specification.allOf`. A filter that was not supplied adds no condition.

**Date range: inclusive on both ends**
- `dueAfter=2026-11-16&dueBefore=2026-11-22` includes both edge dates. Either one works alone. Equal dates mean "due exactly that day".
- **Naming caveat:** "after" and "before" read as exclusive, but these are inclusive. The README and the error messages say so. The names come from the assignment spec.
- Format is strict `yyyy-MM-dd`. `2026-02-30`, `2026-13-01` and `2026-11-5` are all 400.
- `dueAfter` later than `dueBefore` is a 400 naming `dueAfter` ("must not be later than dueBefore"). It is almost certainly swapped dates, and an empty list would hide the mistake. The check is in `TaskService`.

**Sorting: `sort=field[,asc|desc]`, repeatable**
- Allowed fields are `dueDate` and `priority`. Anything else (`title`, `id`, `priorityRank`) is a 400. The direction is case-insensitive, the field name is not. The same field twice is a 400.
- **Default, with no `sort`:** `dueDate` ascending, then priority descending, then id ascending. This answers "what should I do next": deadlines first, urgency breaks ties.
- **A client sort replaces the default.** `id` ascending is always appended as the last key. Without it, rows that tie on every other key could repeat or vanish across pages. `dueDate` is `NOT NULL`, so where nulls sort is not a question.
- **Strict parser, not Spring's.** Spring Data's resolver is lenient (probed on 4.1.2): `priority,foo` becomes two ascending sorts, `dueDate,desc,priority` becomes three, and `,desc` is silently ignored. `TaskSort` rejects each of those with a 400 naming `sort`, and reports every bad entry. The result still goes through a normal `PageRequest`.
- The controller reads `sort` raw (`WebRequest.getParameterValues`). A `List<String>` `@RequestParam` splits a single `priority,desc` on the comma but not a repeated `sort`, so the two forms would parse differently. Confirmed in M4 by temporarily binding a `List<String>`: the sort integration tests then returned 400. The existing single-value `sort=priority,desc` tests guard against that change.
- `page` and `size` stay as validated `@RequestParam` ints, not a `Pageable` argument. A `Pageable` argument would clamp `size` and ignore `page=abc` instead of returning 400.

**No N+1**
- `TaskRepository.findAll(spec, pageable)` has `@EntityGraph(attributePaths = "project")`, so the project comes in the same SELECT as a LEFT JOIN. The project stays LAZY everywhere else. The join is to-one, so paging stays in SQL.
- Measured with the SQL log: one SELECT with the join, one `count`, and no per-project SELECTs. A regression test counts the statements and checks they do not grow with the number of distinct projects.

**Where each validation lives**

| Bad input | Caught by | `fieldErrors.field` |
|-----------|-----------|---------------------|
| Unknown enum, including inside a list | Spring conversion, then `GlobalExceptionHandler.handleTypeMismatch` | `status` or `priority` |
| Malformed or impossible date, non-numeric `projectId`, `page` or `size` | same | the param name |
| `page` below 0, `size` outside 1 to 100 | `@Min` and `@Max` | `page` or `size` |
| `dueAfter` later than `dueBefore` | `TaskService`, throws `InvalidRequestException` | `dueAfter` |
| Bad sort field, direction, shape or duplicate | `TaskSort`, throws `InvalidRequestException` | `sort` |

- `InvalidRequestException` is new in `common/`. It is for checks only a service can make, and it maps to the same 400 as Bean Validation.
- `handleTypeMismatch` now reads the element type of a `List` param. Before, the required type was `List`, and the message would have been "has an invalid value" and not "must be one of TODO, IN_PROGRESS, DONE".
- **Known limits.** Conversion errors are reported one param at a time, the first that fails. The range and sort checks run after binding, so with both a bad range and a bad sort only the range is reported.

**Settled from the M4 pending list:** rank column and not CASE. Inclusive range. Multi-value filters (the earlier lean was single-value). `size` above the max is a 400 (already decided in ADR-010). Null `dueDate` placement does not apply. `dueAfter` later than `dueBefore` is a 400.

- **Tradeoff:** offset paging can still repeat or skip rows if data changes between requests (ADR-006). The id tiebreaker only makes a fixed data set stable. The date param names read as exclusive but are inclusive. `get`, `update` and the assign endpoints still pay one extra SELECT for the project.

---

## Pending (settle in the milestone session, then move up)

**M5**
- Prompt-only JSON or tool use with a forced schema.
- Retry once on malformed output, or fail immediately. (Lean: fail immediately and document a retry as a next step.)
- Connect and read timeout values.
- How relative dates like "before Friday" resolve. (Lean: pass today's date from the `Clock` in the prompt.)
- What to do if the model returns a due date in the past.

**M6**
- Version in the request body (lean) or an `If-Match` header with ETags.
- Whether assigning or unassigning a project also checks the version. (Once `@Version` exists, an assignment that changes the project bumps it. A repeated identical assign does not, because Hibernate sees no change.)

**M7**
- Seed demo data on startup, while keeping tests isolated from it?
