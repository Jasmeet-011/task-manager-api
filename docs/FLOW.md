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
→ 201 Created, Location: /tasks/{id} (path only, no query string), body TaskResponse
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

## Update a task: `PUT /tasks/{id}` (with optimistic locking from M6)

```
JSON body → UpdateTaskRequest (@Valid, version required)
→ TaskService.update
  load task                         missing → 404 NOT_FOUND
  compare request.version to task   mismatch → 409 VERSION_CONFLICT
  apply all fields (full replacement)
  flush; Hibernate checks @Version   concurrent write in between → 409 VERSION_CONFLICT
→ 200 TaskResponse (version incremented)
```

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

## Assign and unassign a task (M3)

```
PUT /tasks/{id}/project/{projectId}
→ TaskService.assignProject
  load task                          missing → 404 NOT_FOUND "Task N not found"
  load project                       missing → 404 NOT_FOUND "Project N not found" (task left unchanged)
  task.moveTo(project)               moves it if it was elsewhere; same project again = no change, no UPDATE
→ 200 TaskResponse with projectId and projectName

DELETE /tasks/{id}/project
→ TaskService.unassignProject
  load task                          missing → 404 NOT_FOUND
  task.removeFromProject()           already none = fine
→ 204 No Content
```

`PUT /tasks/{id}` never touches the project. Only `moveTo` and `removeFromProject` change it.

## AI suggestion: `POST /tasks/suggest`

```
{ "text": "..." } → SuggestTaskRequest (@Valid)
  blank or too long → 400 VALIDATION_FAILED
→ TaskSuggestionService.suggest
  no API key configured              → 503 AI_NOT_CONFIGURED
  build prompt (includes today's date from Clock)
  → AiClient.complete (AnthropicAiClient over RestClient, explicit timeouts)
      timeout                        → 504 AI_TIMEOUT
      HTTP error / unreachable       → 502 AI_UPSTREAM_ERROR
  parse model text as JSON           fails → 502 AI_OUTPUT_INVALID (raw output logged, truncated)
  validate fields (title, priority, dueDate)
                                     fails → 502 AI_OUTPUT_INVALID
  build draft, status always TODO
→ 200 draft (same shape as CreateTaskRequest), nothing saved
```

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
