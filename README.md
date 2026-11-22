# Task Manager API

A personal task manager REST API. Java 17, Spring Boot 4.1.2, Maven, H2 in-memory.

## Run

Requires JDK 17. No API key or environment variables needed.

```
./mvnw spring-boot:run
```

Then open http://localhost:8080/ for the UI (see below).

## Test

```
./mvnw test
```

## UI

One page, served at http://localhost:8080/ after `./mvnw spring-boot:run`. It is a single file, `src/main/resources/static/index.html`, with plain HTML, CSS and JavaScript: no framework, no build step, nothing to install, and it works offline. A reviewer can use the whole API from it without a REST client.

- **Task list.** Filter by status, priority and project (one value each, or "All"). Tasks come in the API's default order (due date, then priority), 5 per page, with Previous and Next. Each row shows its project and its `version`.
- **Create a task.** Title, description, due date and priority. Every error from the API is shown, and field errors appear under the field they name.
- **Suggest from a note.** Type a note such as "urgent, send the report by Friday" and click Suggest. The AI's draft fills the create form, and nothing is saved. A date or priority the note did not give stays empty and is highlighted, so you fill it in and click Create. This needs `ANTHROPIC_API_KEY` (see the AI endpoint). Without it, the page shows the API's 503 message and the rest still works.
- **Change a status.** Each row has a status dropdown. It sends the whole task and its `version` to `PUT /tasks/{id}`. To see the optimistic locking from the stretch feature: open the page in two tabs, change a task's status in one, then change the same task in the other. The second tab shows "This task was changed elsewhere", reloads the list at the new version, and the retry works.
- **Errors.** Every error body is shown readably (title, code, detail, and each field), including "Cannot reach the server" when the app is down. The page shows the result of the latest action only: starting another action clears the earlier message.

The page puts every title, project name and AI draft into the page as plain text, never as HTML, because they are untrusted. A test fails if the page starts using `innerHTML` or similar calls.

### Demo data

So that a fresh app has something to filter and change, it loads demo data at startup:

- **What loads:** 2 projects (Website Launch, Home Renovation) and 8 tasks with mixed statuses and priorities. One task is overdue and not done, one is overdue and done, and one has no project. Due dates are relative to today, so they never go stale.
- **When:** only into an empty database, on every start, through the normal services, so ranks and versions are set as for a real request.
- **It vanishes on stop.** The database is H2 in memory, so everything, including tasks you create, is gone when the app stops, and the demo data loads again on the next start.
- **Turn it off** to start empty, with any one of:
  - set `app.seed-demo-data=false` in `src/main/resources/application.properties`
  - `./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.seed-demo-data=false`
  - set the environment variable `APP_SEED_DEMO_DATA=false`
- **Tests never see it.** `src/test/resources/config/application.properties` sets it to `false`. Spring Boot loads that file in addition to the main `application.properties`, so the main settings stay. It applies from Maven and from an IDE. Do not name a test file `src/test/resources/application.properties`: it would replace the main one during tests.

### What the UI leaves out, and what comes next

Left out on purpose: editing anything but the status, a project picker, creating projects, deleting, and sort controls. The API supports the sorting and the project assignment (see the endpoints), and the list's default order is already the useful one.

- **Project picker** on the create form. The API's `POST /tasks` takes no project, so it needs either a `projectId` on create or a create-then-assign from the page. The second leaves a half-done result if the assign fails.
- Sort controls, overdue highlighting, and editing more than the status.
- Browser tests. The page's JavaScript has no automated test in this repository. It was checked with a throwaway harness and by hand (see ADR-015).

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
- ~~In the UI: send the version with every update and show "changed elsewhere, reload" on a 409.~~ Done: see the UI section.

More sections (endpoints, errors, AI endpoint, design decisions) are added in later milestones.
