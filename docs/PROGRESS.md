# Progress

## Milestone status

| Milestone | Status | Transcript |
|-----------|--------|------------|
| M0: Decisions and docs | Done | n/a |
| M1: Scaffold | Done | transcripts/01-scaffold.md |
| M2: Task CRUD and error handling | Not started | |
| M3: Project entity and association | Not started | |
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
| 01 | The plan said "git repo exists, no commits assumed". The docs were already committed. | I checked `.git` and `.gitignore` but never ran `git log`, so the plan rested on an assumption. Low impact. | Caught in plan review. | Ran `git log`: one commit, `4ae0121 Add project docs and plan`. Check git history before planning from now on. |
