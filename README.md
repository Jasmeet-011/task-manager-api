# Task Manager API

A personal task manager REST API. Java 17, Spring Boot 4.1.2, Maven, H2 in-memory.

## Run

Requires JDK 17. No API key or environment variables needed.

```
./mvnw spring-boot:run
```

## Test

```
./mvnw test
```

## Stretch feature: optimistic locking

Two people (or two browser tabs) editing the same task must not silently overwrite each other. Every task has a `version` number, and an update that was based on an out-of-date copy is rejected.

### Built

- Every task response (create, get, list, update, assign) includes `version`. A new task starts at 0.
- `PUT /tasks/{id}` must include the `version` the client last saw. If it is no longer the current one, the API returns 409 `VERSION_CONFLICT` and changes nothing. The client fetches the task again and retries.
- A missing `version` is a 400 naming the field (without it the server could not detect a conflict), and so is a negative one.
- Two PUTs with the same version sent at once give one 200 and one 409. The comparison in `TaskService` catches a stale version, and JPA's `@Version` catches a write that lands between the read and the save. Both give the same 409.
- The response to a PUT or an assign already carries the new version, so the client can send its next update straight away.

```
PUT /tasks/7   {"title": "Report", "dueDate": "2026-12-01", "priority": "HIGH", "status": "TODO", "version": 0}
200            {"id": 7, ..., "version": 1}

PUT /tasks/7   {"title": "Other",  "dueDate": "2026-12-01", "priority": "HIGH", "status": "TODO", "version": 0}
409            {"type": "about:blank", "title": "Conflict", "status": 409,
                "detail": "Task 7 was changed by someone else since you loaded it. Fetch it again and retry.",
                "instance": "/tasks/7", "code": "VERSION_CONFLICT", "fieldErrors": []}
```

Rules worth knowing:

- Assigning or unassigning a project changes the task, so it raises the version. A client holding the old version gets a 409 on its next PUT, which is correct, because the task did change. Assigning the same project again changes nothing, so the version stays.
- A PUT that changes nothing leaves the version as it is.
- Assign and unassign take no version. If one collides with another write in the same few milliseconds it also returns 409 `VERSION_CONFLICT`, and the client retries.
- `DELETE` never checks the version: deleting means "I want this task gone".

### Deliberately left out

- **ETag and `If-Match` headers.** By HTTP convention a failed `If-Match` is 412, and the assignment asks for 409. A `version` field in the body is also visible in the JSON and simple for a UI.
- **Version checks on assign, unassign and delete.** Each is one explicit action, and the version still moves when they change the task.
- **The current version (or task) in the 409 body.** The error keeps the same shape as every other error, and the client re-fetches anyway.
- **Merging conflicting edits.** The client decides what to do after re-fetching.
- **Versioned projects.** Projects cannot be updated yet, so there is nothing to protect.

### What comes next

- Return the current task in the 409, so the client skips a round trip.
- Accept `If-Match` as well as the body field.
- Merge edits that touch different fields instead of rejecting them.
- A `version` on `PUT /projects/{id}` once project updates exist.
- In the UI: send the version with every update and show "changed elsewhere, reload" on a 409.

More sections (endpoints, errors, AI endpoint, design decisions) are added in later milestones.
