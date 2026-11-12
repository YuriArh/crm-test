# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A test assignment: "Exchange with an external system". The spec is `04-system-integration.md` (Russian, v2.1; requirements are referenced by ID, e.g. D01–D07, NF01–NF10). The work plan is `docs/PLAN.md` (Russian); `docs/archive/PLAN-v1.md` is an older, more detailed version. Detailed design decisions for `dispatch` (statuses, retries, result handling, reconciliation, problems, permissions) are in `docs/dispatch-design.md` (Russian) — follow them. Read the plan before starting a new piece of work: it fixes the order (stage 1 = a basic working end-to-end flow; stage 2 = reliability, permissions, audit, load, tests, docs).

Domain: a cargo delivery request (cargo, weight, address, deadline). Lifecycle: "Create" (saved and queued for sending in one transaction; no drafts, content is immutable) → execution → result. A fictional delivery carrier (`carrier-emulator`) returns a shipment number and price, or a rejection. `dispatch` **pulls** results from the emulator by polling.

## Repository layout and status

- `dispatch/` — the main application: accepts delivery requests, dispatches them to the carrier, tracks delivery and result, reconciles. Spring Boot 4 / Java 25 (package `com.acme.dispatch`). Currently: config, `/api/system/info`, and the stage-1 schema (Flyway V1–V5: services, users, requests, operations, attempts, inbox, feed cursor, idempotency keys, demo users; see `docs/dispatch-design.md` §4). No entities, security or API yet.
- `carrier-emulator/` — emulator of the external carrier: separate Spring Boot app (package `com.acme.carrier`) with its **own database**. Not implemented yet. When added, include it in `settings.gradle.kts` and `docker-compose.yml`.
- `contracts/` — OpenAPI contracts (`dispatch-api.yaml` for dispatch↔web, `carrier-api.yaml` for dispatch↔carrier-emulator); the YAML files are not written yet. `dispatch` and `carrier-emulator` must **not share Java classes**: the emulator stands in for an external system, so the contract in `contracts/` is the only agreement between them.
- `web/` — planned React + TypeScript + Vite client, a separate npm project (not in Gradle). The UI and the API are served from one origin (Vite proxy in dev, nginx in Docker), so session cookies work without CORS.

Gradle multi-project build: the root `build.gradle.kts` configures the Java 25 toolchain, `-parameters`, JUnit Platform, and `user.timezone=UTC` for tests in all subprojects. Plugin versions live in `gradle/libs.versions.toml`; library versions come from the Spring Boot BOM and are not pinned.

## Commands

```bash
cp .env.example .env   # first time
make up                # build and start postgres + dispatch in Docker
make db                # only PostgreSQL (for running dispatch from the IDE)
make run               # make db + ./gradlew :dispatch:bootRun
make test              # ./gradlew test — needs Docker running (Testcontainers)
make build             # ./gradlew build (with tests)
make reset             # stop everything and delete the DB volume
make logs              # follow dispatch logs
```

Single test class / method:

```bash
./gradlew :dispatch:test --tests 'com.acme.dispatch.DispatchApplicationTests'
./gradlew :dispatch:test --tests 'com.acme.dispatch.DispatchApplicationTests.healthIsUp'
```

Smoke check: `curl localhost:8080/actuator/health` and `curl localhost:8080/api/system/info`.

## Conventions

- **Time:** instants are stored and processed in UTC (the JVM default zone and the Postgres container are UTC). The single business time zone is `app.timezone` / `APP_TIMEZONE` (default `Europe/Moscow`, bound to `AppProperties`), used for deadlines and period boundaries consistently in UI, API and calculations (NF07). Get "now" from the injected `Clock` bean (`TimeConfig`), never `Instant.now()` directly, so tests can use a fixed clock.
- **Schema:** owned only by Flyway migrations in `dispatch/src/main/resources/db/migration`. No ORM schema validation: integration tests on a real database check that code and schema match.
- **Data access:** entities via Spring Data JDBC (repositories, `@Version`, paging; no persistence context, lazy loading or implicit updates). The exchange core (queue `FOR UPDATE SKIP LOCKED`, row locks, `INSERT … ON CONFLICT`, conditional status `UPDATE … WHERE status IN (…)`, the registry query) is explicit SQL via `JdbcClient`. Do not use JPA/Hibernate.
- **Integration tests** use a real PostgreSQL via Testcontainers: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`. Keep the image (`postgres:18-alpine`) in sync with `docker-compose.yml`.
- **Resource budget (NF08):** dispatch + postgres + carrier-emulator together get at most 2 CPUs / 4 GiB; the planned split is in the comment at the top of `docker-compose.yml`. Keep compose `deploy.resources.limits` within it.

## Database migrations (Flyway)

Migrations are applied automatically when `dispatch` starts (`make run`, `make up`) and from scratch in every Testcontainers test run (`make test`); there is no separate migrate command.

- **Location and naming:** `dispatch/src/main/resources/db/migration/V<n>__<description>.sql` (two underscores), next number in sequence, one logical topic per migration.
- **Never edit an applied migration** that is committed or shared — Flyway fails with `Migration checksum mismatch`. Fix forward with a new `V<n+1>__…`. A brand-new, local-only migration may be edited, then recreate the local DB with `make reset && make up`.
- **Schema rules** (details in `docs/dispatch-design.md` §4):
  - `uuid` primary keys without DB defaults — the application generates UUIDv7;
  - statuses as `text` + `CHECK (… IN (…))`, not PostgreSQL enums; a new status means a new migration that recreates the `CHECK`;
  - instants `timestamptz`, dates `date`; `created_at`/`updated_at` are set by the app from `Clock` — no `now()` defaults, no triggers;
  - `snake_case` tables/columns matching Spring Data JDBC naming;
  - name every constraint explicitly (e.g. `delivery_request_weight_range`) so violations are readable;
  - enforce integrity in the DB (`NOT NULL`, `CHECK`, FKs, unique/partial indexes), not only in code (NF03); add indexes for the actual queries.
- **Changing existing tables:** a new required column is added as nullable → backfilled → `SET NOT NULL`. Renames/drops go in the same commit as the code that uses them.
- **Data in migrations:** only reference data that must always exist (services, demo users — see `V5__demo_users.sql`), passwords as BCrypt hashes (`htpasswd -bnBC 10 "" <password>`). Demo delivery requests are never inserted by migrations — they are created through the API (`make seed`) so their exchange history is real (NF01).
- **Tests:** every new constraint gets a test showing invalid data is rejected (`SchemaMigrationTests` or the feature's tests); update its expected migration list when adding a version.
- **Check applied versions:** `docker compose exec postgres psql -U dispatch -d dispatch -c "select version, description, success from flyway_schema_history order by installed_rank"`.

## Design decisions to build in from the start (from `docs/PLAN.md`)

- Every sent request has an immutable `operationId` (the business-operation identity used for idempotency/dedup with the emulator).
- Sending goes through a DB queue table (outbox) processed by a background worker — never a direct call to the emulator from a user request.
- Requests carry a `version` field for optimistic locking.
- Delivery status (sent / received by executor) and business result (pending / done / rejected) are stored separately — receipt acknowledgement is not execution.
- Stage 2 reliability targets: bounded retries with backoff, at-most-once execution in the emulator, discarding duplicate and stale results, recovery after restart, emulator failure modes (unavailable, lost response, duplicate, delayed result) that can be toggled reproducibly, and reconciliation where ambiguities are surfaced to an operator rather than overwritten.
- Demo users: two initiators and one operator; initiators must not see others' requests or get operator rights, including via direct API calls.
