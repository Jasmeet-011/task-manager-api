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

- **Amended in M2:** the last three rows were added so that every status Spring MVC can produce has a stable code. `type` is always `"about:blank"` and is set explicitly, because Spring leaves it out of the JSON otherwise.
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
- **`GET /tasks` takes `page` (default 0, min 0) and `size` (default 20, 1 to 100) only, ordered by id.** This applies ADR-006's default and cap. Sort and filters arrive in M4.
- **Tradeoff:** a client that wants to change one field must resend the others. The UI does that. Unknown properties are not reported, so a misspelled optional field is silently dropped.

---

## Pending (settle in the milestone session, then move up)

**M3**
- How tasks get associated: dedicated endpoints `PUT /tasks/{id}/project/{projectId}` and `DELETE /tasks/{id}/project` (lean), or `projectId` in the task body.
- Support `DELETE /projects/{id}`? If so, what happens to its tasks? (Lean: support it, unassign tasks, never delete them.)
- Must project names be unique?

**M4**
- How to sort priority correctly: a rank column derived from the enum, or a CASE expression in the query.
- Inclusive or exclusive date range. (Lean: inclusive on both ends.)
- Single-value or multi-value filters. (Lean: single value.)
- `size` above the max: return 400, or clamp to the max?
- Where null `dueDate` values go when sorting. (Lean: last.)
- `dueAfter` later than `dueBefore` returns 400?

**M5**
- Prompt-only JSON or tool use with a forced schema.
- Retry once on malformed output, or fail immediately. (Lean: fail immediately and document a retry as a next step.)
- Connect and read timeout values.
- How relative dates like "before Friday" resolve. (Lean: pass today's date from the `Clock` in the prompt.)
- What to do if the model returns a due date in the past.

**M6**
- Version in the request body (lean) or an `If-Match` header with ETags.
- Whether assigning or unassigning a project also checks the version.

**M7**
- Seed demo data on startup, while keeping tests isolated from it?
