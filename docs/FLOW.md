# Request flows

How a request moves through the app. This starts as the intended design and gets updated at the end of each milestone to match what was actually built.

## The general path

```
HTTP request
  → Controller          binds JSON/params to a record, @Valid triggers Bean Validation
  → Service             @Transactional, business rules, loads/saves entities, maps entity → DTO
  → Repository          Spring Data JPA → Hibernate → H2
  ← Service             returns a record (never an entity)
  ← Controller          wraps in ResponseEntity with the right status
HTTP response (JSON)

Any exception thrown on the way
  → GlobalExceptionHandler → ProblemDetail (ADR-005) → application/problem+json

POST /tasks/suggest calls no repository. Its service calls AiClient instead (see the AI suggestion flow).
```

## Create a task: `POST /tasks`

```
JSON body → CreateTaskRequest (@Valid)
  invalid field / bad enum (a number too) / bad date → 400 VALIDATION_FAILED with fieldErrors
  invalid JSON syntax or empty body                  → 400 MALFORMED_REQUEST
  title, dueDate and priority are required, there are no defaults, and status is not accepted
→ TaskService.create
  status is always TODO
  save Task
→ 201 Created, Location: /tasks/{id} (path only, no query string), body TaskResponse (version 0)
```

## List tasks: `GET /tasks?status=&priority=&dueAfter=&dueBefore=&projectId=&page=&size=&sort=`

As built in M4 (ADR-012). All params are optional and combine. A blank value means "not supplied".

```
query params
→ TaskController.list            Spring binds and converts the params
    status, priority   List of enum   ?status=TODO&status=DONE  or  ?status=TODO,DONE (same result)
    projectId          Long
    dueAfter, dueBefore LocalDate     strict yyyy-MM-dd
    page (default 0, min 0), size (default 20, 1 to 100)      @Min / @Max
    sort               read raw from the request (a List param would split "priority,desc" on the comma)
  bad enum (also inside a list) / bad date / non-numeric number / page or size out of range
                                   → 400 VALIDATION_FAILED naming that param (first failing param only)
  builds a TaskFilter record
→ TaskService.list(filter, page, size, sort)
  dueAfter later than dueBefore    → InvalidRequestException → 400 VALIDATION_FAILED, field dueAfter
  TaskSort.resolve(sort)
    nothing supplied               → dueDate asc, priority desc (priorityRank), id asc
    supplied                       → each entry checked: field in {dueDate, priority}, direction asc|desc,
                                     no duplicate field; priority becomes priorityRank; id asc appended last
    any bad entry                  → InvalidRequestException → 400 VALIDATION_FAILED, field sort
  TaskSpecifications.from(filter)  AND of one Specification per supplied filter
                                   (OR inside status and priority, dates inclusive on both ends)
  taskRepository.findAll(spec, PageRequest)
→ Repository (Hibernate → H2)      @EntityGraph("project"): ONE SELECT with LEFT JOIN projects, plus a count
  map Page<Task> → PageResponse<TaskResponse> inside the transaction (no extra SELECTs)
→ 200 { content, page, size, totalElements, totalPages }
```

An unknown `projectId` is a filter that matches nothing: 200 with an empty page, never 404. Measured with the SQL log in M4: a page of tasks in 3 different projects ran one task-plus-project SELECT and one count, and no per-project SELECTs (the N+1 seen in M3 is gone). The rank column `priority_rank` is internal and never appears in the response.

## Update a task: `PUT /tasks/{id}` (optimistic locking, M6, ADR-014)

As built in M6.

```
JSON body → UpdateTaskRequest (@Valid): title, dueDate, priority, status, version (all required) and description
  version missing or not a number → 400 VALIDATION_FAILED naming version
  version below 0                 → 400 VALIDATION_FAILED naming version
→ TaskService.update
  load task                         missing → 404 NOT_FOUND
  Objects.equals(task.version, request.version)
                                    different → 409 VERSION_CONFLICT, nothing changed (checked first, so a stale
                                    version is a 409 even if the PUT would change nothing)
  apply all fields (full replacement)
  flushOrConflict → Hibernate runs
      UPDATE tasks SET ..., version = v+1 WHERE id = ? AND version = v     (v = the version loaded above)
    1 row                           → the entity's version is now v+1
    0 rows (someone committed after our load)
                                    → ObjectOptimisticLockingFailureException caught in flushOrConflict
                                    → 409 VERSION_CONFLICT, the same body as the comparison above
    nothing changed                 → no UPDATE, version stays v
  TaskResponse.from(task)           built AFTER the flush, so it carries the new version
→ 200 TaskResponse
```

The 409 is the ADR-005 shape with `code` `VERSION_CONFLICT`, `fieldErrors: []`, and no current version. The client fetches the task again (`GET /tasks/{id}`) and retries with the version it gets.

Why flush before the response: the version only changes when the UPDATE runs. Left to commit time, the response would show the old version, and the client's next PUT would get a false 409.

Two requests with the same version at the same time: both load version 0, so both pass the comparison. Both flush `UPDATE ... WHERE version = 0`. The first matches the row. The second matches no row (Hibernate reports "expected row count 1 but was 0"), so it gets the 409. A request that loads after the first has committed fails the comparison instead. Either way: one 200 and one 409.

## Create a project: `POST /projects` (M3)

```
JSON body → CreateProjectRequest (@Valid)
  blank name, name over 100, description over 500 → 400 VALIDATION_FAILED with fieldErrors
  invalid JSON syntax or empty body               → 400 MALFORMED_REQUEST
→ ProjectService.create
  trim the name
  projectRepository.saveAndFlush      the INSERT runs here, inside the try block
    violates uk_projects_name         → catch DataIntegrityViolationException
                                        constraint name matches  → ConflictException → 409 DUPLICATE_PROJECT_NAME
                                                                   fieldErrors [{ name, "already exists" }]
                                        any other violation      → rethrown → logged 500 INTERNAL_ERROR
→ 201 Created, Location: /projects/{id}, body ProjectResponse
```

Why `saveAndFlush`: a flush left for commit time would throw after the service method returned, outside the catch, and become a 500. Uniqueness is enforced only by the database, so two concurrent requests with the same name give one 201 and one 409.

## List and get projects: `GET /projects`, `GET /projects/{id}` (M3)

```
GET /projects        → ProjectService.list → 200 plain JSON array, ordered by id, no pagination
GET /projects/{id}   → ProjectService.get  → 200 ProjectResponse
                         missing → 404 NOT_FOUND, non-numeric → 400 VALIDATION_FAILED
```

## Assign and unassign a task (M3, changed in M6)

```
PUT /tasks/{id}/project/{projectId}        (no version in the request, ADR-014)
→ TaskService.assignProject
  load task                          missing → 404 NOT_FOUND "Task N not found"
  load project                       missing → 404 NOT_FOUND "Project N not found" (task left unchanged)
  task.moveTo(project)               moves it if it was elsewhere; same project again = no change, no UPDATE
  flushOrConflict                    a real change runs UPDATE ... WHERE id = ? AND version = v, so the version is v+1
                                     no change: no UPDATE, version stays
                                     0 rows (a PUT, assign or unassign committed after our load)
                                       → 409 VERSION_CONFLICT; the client retries
→ 200 TaskResponse with projectId, projectName and the new version

DELETE /tasks/{id}/project
→ TaskService.unassignProject
  load task                          missing → 404 NOT_FOUND
  task.removeFromProject()           already none = fine (no UPDATE, no bump)
  flushOrConflict                    flushed here, not at commit, so a lost race can be mapped (409 VERSION_CONFLICT)
→ 204 No Content
```

`PUT /tasks/{id}` never touches the project. Only `moveTo` and `removeFromProject` change it. Because they change the task row, a client still holding the old version gets a 409 on its next `PUT /tasks/{id}`.

## Delete a task: `DELETE /tasks/{id}` (M6, ADR-014)

```
→ TaskService.delete
  taskRepository.deleteWithoutVersionCheck(id)    one DELETE ... WHERE id = ?, no version, no entity loaded
    0 rows deleted                  → 404 NOT_FOUND (also what a second concurrent delete gets)
→ 204 No Content
```

Deleting the loaded entity would add `AND version = ?`, and a concurrent edit or second delete between the load and the delete would be a 500. The bulk delete bypasses the persistence context, which is safe because `Task` has no cascades or lifecycle callbacks.

## AI suggestion: `POST /tasks/suggest` (M5)

As built in M5 (ADR-013). Nothing is saved and nothing touches the database.

```
{ "text": "..." } → SuggestTaskRequest (@Valid)
  missing, blank or over 500 characters → 400 VALIDATION_FAILED, field text (the model is not called)
  body is not JSON                      → 400 MALFORMED_REQUEST
→ TaskSuggestionController (ai/)
→ TaskSuggestionService.suggest
  system prompt with today's date and weekday from the Clock ("Today is Saturday, 2026-11-21.")
  tool create_task_draft with a strict schema (four fields, nullable optional ones, no extra fields)
→ AiClient.callTool (AnthropicAiClient)
  no key, or a blank one              → 503 AI_NOT_CONFIGURED (checked first, no request sent)
  POST {base-url}/v1/messages
      headers: x-api-key, anthropic-version 2026-10-21
      body:    model from config, max_tokens 4096, effort low,
               tool_choice auto with at most one call (not forced)
  connect or read timeout             → 504 AI_TIMEOUT
  400, 401, 403, 404                  → 502 AI_UPSTREAM_ERROR, logged at ERROR (the operator must fix something)
  429, 5xx, 529, refused, unknown host → 502 AI_UPSTREAM_ERROR, logged at WARN
  2xx:
    body not JSON, stop_reason not tool_use, not exactly one create_task_draft call, call without input
                                      → 502 AI_OUTPUT_INVALID
  returns the call's input (thinking and text blocks are ignored)
← TaskSuggestionService validates the input
    not an object, an extra field, title missing / blank / not a string / over 200 characters,
    description over 2000, priority not exactly LOW|MEDIUM|HIGH, dueDate not a real yyyy-MM-dd
                                      → 502 AI_OUTPUT_INVALID
    title trimmed, blank description → null, a missing optional field → null, past dates allowed
→ 200 TaskDraft { title, description, dueDate, priority }
    all four keys always present, nulls included, no status
```

**Logging:** each AI failure is logged once, by `GlobalExceptionHandler`, from the exception's message. The client only ever gets a fixed `detail`. Invalid output is logged with the raw output, cut to 1,000 characters and without thinking blocks. The key is removed from anything the client builds, and the user's text is never logged.

**After the draft:** the user fills any null `dueDate` or `priority`, then sends it to `POST /tasks`, where the normal validation runs again and the task starts as TODO. In the UI (M7) the page fills the create form from the draft and the user clicks Create (see the UI flows below).

## The UI: `GET /` (M7, ADR-015)

`index.html` is a static file that Spring Boot serves at `/`. It calls the API above from the browser and has no endpoint of its own. Every API call goes through one `api()` function, and every failure becomes the same problem shape (below).

```
Startup, when app.seed-demo-data=true (main config; the test config sets false)
→ DemoDataSeeder (an ApplicationRunner, only a bean when the property is true)
  projects and tasks both empty, checked through ProjectService.list and TaskService.list
    no  → log "not loaded", nothing changes
    yes → ProjectService.create x2
          per task: TaskService.create (TODO, version 0)
                    → assignProject (version 1)            if it has a project
                    → update with the response's version   if its status is not TODO (version 2)
          due dates = today (Clock) + an offset
  an exception here stops the startup on purpose

Page load
→ GET /projects                    fills the project filter
→ GET /tasks?page=0&size=5         renders the rows
```

**One result at a time.** Every action below starts with `clearResults()`: it hides the success banners, the suggest, create, status and list errors and the create field errors. A result stays until the next action. There is no timer.

```
List, filter, page   (a filter change goes back to page 0, Previous and Next change the page)
→ clearResults
→ GET /tasks?status=&priority=&projectId=&page=&size=5     only non-empty filters are sent
  a request counter drops the answer to an older request
  200 → rows (title, description, due, priority, status dropdown, project, version), "Page 1 of 2 · 8 tasks"
  error → rows cleared, the error shown above the table

Create
→ clearResults, create button disabled
→ POST /tasks { title, description|null, dueDate|null, priority|null }     no client-side validation
  201 → form cleared, "Created task #N: title" (plus a note if a filter is active), page 0, list reloaded
  400 → each fieldErrors entry under the input it names; title and detail in the box
        entries with no input are listed in the box
  other error → the box

Suggest
→ clearResults, suggest button disabled, "Suggesting..."
→ POST /tasks/suggest { text }
  200 → the four form fields are replaced by the draft (null becomes empty)
        null dueDate or priority → highlighted yellow, plus a note saying what the note did not say
        editing a highlighted field removes its highlight
        nothing is saved; the user clicks Create (above)
  400 (text), 502, 503, 504 → title, code and the server's detail in the box next to the note; the form is untouched

Change a status (the dropdown in a row)
→ clearResults, this dropdown disabled
→ PUT /tasks/{id} { title, description, dueDate, priority, status: new, version: from the last load }
  200 → "Task #N is now X.", list reloaded (the row shows the new status and version + 1)
  409 VERSION_CONFLICT → "This task was changed elsewhere" in the status error box,
                         dropdown set back, list reloaded (current data and version), so a retry works
  404 → the API error, dropdown set back, list reloaded (the task is gone)
  other error, or no answer → the error, dropdown set back
```

**The problem shape.** `api()` throws an `ApiError` with `{ status, title, detail, code, fieldErrors }` for every failure:

```
response not ok, Content-Type JSON  → the ADR-005 body, field by field
response not ok, anything else      → "HTTP 400 Bad Request" (Tomcat's HTML page for a malformed URL)
fetch itself fails                  → "Cannot reach the server"
2xx whose body is not JSON          → "Unreadable response"
not an ApiError (a bug in the page) → "Unexpected error in the page", also console.error
```

Every piece of API or AI text is set with `textContent`, `.value` or an attribute. `IndexPageTest` fails if the file contains an HTML-building call.

## Unexpected failure

```
any unhandled exception
→ GlobalExceptionHandler logs it with the stack trace (server side only)
→ 500 INTERNAL_ERROR, generic detail, no stack trace in the body

Spring MVC exceptions with no specific handler (e.g. 406)
→ handleExceptionInternal fills in type, code (CLIENT_ERROR for unmapped 4xx) and fieldErrors only where missing

Rejected by Tomcat before Spring MVC runs (e.g. /tasks/%)
→ Tomcat's HTML 400 page, not ADR-005, no stack trace. Optional M8 item.
```
