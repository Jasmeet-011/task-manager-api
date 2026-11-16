# CLAUDE.md

Instructions for Claude Code in this repo. At the start of every session, read this file, `docs/PLAN.md`, `docs/DECISIONS.md` and `docs/PROGRESS.md` before doing anything else.

## Project

A personal task manager REST API, built as a take-home for a backend engineer interview. I will run it live, explain every line, and extend it with an AI tool in front of the interviewers. Clarity beats cleverness. If I can't explain it, it doesn't belong here.

Scope comes from the assignment spec. `docs/PLAN.md` maps every requirement to a milestone. Build only what the current milestone asks for.

## Stack

- Java 17 (language level 17, must build and run on a JDK 17)
- Spring Boot, latest stable release from start.spring.io (exact version recorded in `docs/DECISIONS.md`)
- Maven with the wrapper (`./mvnw`)
- H2 in-memory database
- Spring Web MVC, Spring Data JPA, Bean Validation
- JUnit 5, Mockito, MockMvc
- Anthropic Claude API called through Spring `RestClient` (no SDK, no Spring AI)
- Plain HTML and vanilla JS in `src/main/resources/static` (no framework, no build step)
- No Lombok, no MapStruct

## Hard rules

1. `./mvnw spring-boot:run` must start cleanly on a fresh clone with only JDK 17 installed. No API key, env vars or external services required to start.
2. `./mvnw test` must pass with no API key and no network. Tests never call the real model.
3. Follow `docs/DECISIONS.md`. Never change an accepted decision silently. If you think one is wrong, stop and tell me why.
4. Stay inside the current milestone. No extra features and no "while I'm here" refactors. Suggest them, don't build them.
5. Start every milestone with a plan: files to add or change, approach, and open questions. Wait for my approval before writing code.
6. Every milestone ships with its tests. Run `./mvnw test` before calling a milestone done and report the real result.
7. Do not commit unless I ask. Propose small commit points with messages.
8. When you make a non-obvious choice, explain why in one or two sentences. I have to defend every line.
9. If something is ambiguous, ask. Don't guess silently.

## Architecture

Package by feature under `dev.jasmeet.taskmanager`:

```
task/      Task entity, enums, TaskRepository, TaskService, TaskController, DTOs, TaskSpecifications
project/   Project entity, ProjectRepository, ProjectService, ProjectController, DTOs
ai/        AiClient interface, AnthropicAiClient, TaskSuggestionService, suggestion DTOs, AI exceptions
common/    error handling (GlobalExceptionHandler, exception types), PageResponse, config
```

Layering:

- **Controller:** HTTP only. Binds and validates input, calls one service method, returns a DTO. No business logic, no repositories.
- **Service:** business rules and transactions. `@Transactional` lives here (`readOnly = true` for queries). Entities are mapped to DTOs inside the transaction.
- **Repository:** Spring Data JPA interfaces.
- Entities never leave the service layer. Controllers accept and return records.

## Conventions

- DTOs are Java records. Request DTOs carry Bean Validation annotations.
- Constructor injection only. No field `@Autowired`.
- Enums are stored with `@Enumerated(EnumType.STRING)`.
- `spring.jpa.open-in-view=false`.
- Dates are ISO-8601 `LocalDate` (`yyyy-MM-dd`).
- Services throw typed exceptions. `GlobalExceptionHandler` maps them to the error body in ADR-005. No try/catch returning `ResponseEntity` inside controllers.
- Use an injectable `java.time.Clock` wherever "today" matters (AI prompt, date logic) so tests are deterministic.
- If the Spring Boot version is 4.x: Jackson 3 uses `tools.jackson` packages, and tests use `@MockitoBean` (the old `@MockBean` is removed). Check the actual version before assuming APIs.

## Traps I will check for

- Sorting priority alphabetically (HIGH, LOW, MEDIUM). Priority order must be LOW < MEDIUM < HIGH.
- Optimistic locking that never fires because the service loads the entity and saves it without comparing the client's version.
- N+1 queries when listing tasks with their project.
- `CascadeType.REMOVE` or `ALL` from Project to Task.
- Returning entities from controllers, or "fixing" `LazyInitializationException` with EAGER fetching or open-in-view.
- Catch-all handlers that hide bugs as 500s without logging, or leak stack traces to the client.
- Unknown enum values, malformed dates, or unknown sort fields producing a 500 instead of a 400.
- Serializing Spring's `Page` object directly as JSON.
- A hardcoded API key or model name.

## End of every session

Update `docs/PROGRESS.md` with what was built, the test result, decisions made, and any mistakes of yours that I caught (Agent Mistakes Log). Move settled items from "Pending" to "Accepted" in `docs/DECISIONS.md`. Update `docs/FLOW.md` if a request flow changed.
