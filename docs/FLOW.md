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
  invalid field / bad enum / bad date → 400 VALIDATION_FAILED with fieldErrors
  invalid JSON syntax                 → 400 MALFORMED_REQUEST
→ TaskService.create
  apply defaults (status, priority per DECISIONS)
  save Task
→ 201 Created, Location: /tasks/{id}, body TaskResponse
```

## List tasks: `GET /tasks?status=&priority=&dueAfter=&dueBefore=&projectId=&page=&size=&sort=`

```
query params → filter values + Pageable
  bad enum / date / sort field / size → 400 VALIDATION_FAILED naming the param
→ TaskService.list
  build Specification: AND of each non-null filter
  translate sort (priority uses its rank, not its name)
  repository.findAll(spec, pageable), project fetched in the same query
  map Page<Task> → PageResponse<TaskResponse>
→ 200 { content, page, size, totalElements, totalPages }
```

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

## Associate a task with a project (M3)

```
(shape decided in M3)
load task and project               either missing → 404 NOT_FOUND
set task.project (or null to unassign)
→ 200 TaskResponse with projectId and projectName
```

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
```
