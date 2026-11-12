# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A test assignment: "Exchange with an external system". The spec is `04-system-integration.md` (Russian, v2.1; requirements are referenced by ID, e.g. D01–D07, NF01–NF10). The work plan is `docs/PLAN.md` (Russian); `docs/archive/PLAN-v1.md` is an older, more detailed version. Read the plan before starting a new piece of work: it fixes the order (stage 1 = a basic working end-to-end flow; stage 2 = reliability, permissions, audit, load, tests, docs).

Domain: a cargo delivery request (cargo, weight, address, deadline). Lifecycle: draft → "Send" → execution → result. A fictional delivery service (the emulator) returns a shipment number and price, or a rejection. The backend **pulls** results from the emulator by polling.

## Repository layout and status

- `backend/` — Spring Boot 4 / Java 21 (package `com.acme.exchange`). Currently only a skeleton: config, `/api/system/info`, and an empty Flyway migration folder.
- `emulator/` — separate Spring Boot app with its **own database**. Not implemented yet. When added, include it in `settings.gradle.kts` and `docker-compose.yml`.
- `contracts/` — OpenAPI contracts (`app-api.yaml` for backend↔web, `emulator-api.yaml` for backend↔emulator); the YAML files are not written yet. The backend and the emulator must **not share Java classes**: the emulator stands in for an external system, so the contract in `contracts/` is the only agreement between them.
- `web/` — planned React + TypeScript + Vite client, a separate npm project (not in Gradle). The UI and the API are served from one origin (Vite proxy in dev, nginx in Docker), so session cookies work without CORS.

Gradle multi-project build: the root `build.gradle.kts` configures the Java 21 toolchain, `-parameters`, JUnit Platform, and `user.timezone=UTC` for tests in all subprojects. Plugin versions live in `gradle/libs.versions.toml`; library versions come from the Spring Boot BOM and are not pinned.

## Commands

```bash
cp .env.example .env   # first time
make up                # build and start postgres + backend in Docker
make db                # only PostgreSQL (for running the backend from the IDE)
make run               # make db + ./gradlew :backend:bootRun
make test              # ./gradlew test — needs Docker running (Testcontainers)
make build             # ./gradlew build (with tests)
make reset             # stop everything and delete the DB volume
make logs              # follow backend logs
```

Single test class / method:

```bash
./gradlew :backend:test --tests 'com.acme.exchange.BackendApplicationTests'
./gradlew :backend:test --tests 'com.acme.exchange.BackendApplicationTests.healthIsUp'
```

Smoke check: `curl localhost:8080/actuator/health` and `curl localhost:8080/api/system/info`.

## Conventions

- **Time:** instants are stored and processed in UTC (JVM, Hibernate JDBC time zone, Postgres container are all UTC). The single business time zone is `app.timezone` / `APP_TIMEZONE` (default `Europe/Moscow`, bound to `AppProperties`), used for deadlines and period boundaries consistently in UI, API and calculations (NF07). Get "now" from the injected `Clock` bean (`TimeConfig`), never `Instant.now()` directly, so tests can use a fixed clock.
- **Schema:** owned only by Flyway migrations in `backend/src/main/resources/db/migration`. Hibernate runs with `ddl-auto: validate`; `open-in-view` is off.
- **Integration tests** use a real PostgreSQL via Testcontainers: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`. Keep the image (`postgres:18-alpine`) in sync with `docker-compose.yml`.
- **Resource budget (NF08):** backend + postgres + emulator together get at most 2 CPUs / 4 GiB; the planned split is in the comment at the top of `docker-compose.yml`. Keep compose `deploy.resources.limits` within it.

## Design decisions to build in from the start (from `docs/PLAN.md`)

- Every sent request has an immutable `operationId` (the business-operation identity used for idempotency/dedup with the emulator).
- Sending goes through a DB queue table (outbox) processed by a background worker — never a direct call to the emulator from a user request.
- Requests carry a `version` field for optimistic locking.
- Delivery status (sent / received by executor) and business result (pending / done / rejected) are stored separately — receipt acknowledgement is not execution.
- Stage 2 reliability targets: bounded retries with backoff, at-most-once execution in the emulator, discarding duplicate and stale results, recovery after restart, emulator failure modes (unavailable, lost response, duplicate, delayed result) that can be toggled reproducibly, and reconciliation where ambiguities are surfaced to an operator rather than overwritten.
- Demo users: two initiators and one operator; initiators must not see others' requests or get operator rights, including via direct API calls.
