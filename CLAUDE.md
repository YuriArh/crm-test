# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A test assignment: "Exchange with an external system". The spec is `04-system-integration.md` (Russian, v2.1; requirements are referenced by ID, e.g. D01–D07, NF01–NF10). The work plan is `docs/PLAN.md` (Russian); `docs/archive/PLAN-v1.md` is an older, more detailed version. Detailed design decisions for `dispatch` (statuses, retries, result handling, reconciliation, problems, permissions) are in `docs/dispatch-design.md` (Russian) — follow them. Read the plan before starting a new piece of work: it fixes the order (stage 1 = a basic working end-to-end flow; stage 2 = reliability, permissions, audit, load, tests, docs).

Domain: a cargo delivery request (cargo, weight, address, deadline). Lifecycle: "Create" (saved and queued for sending in one transaction; no drafts, content is immutable) → execution → result. A fictional delivery carrier (`carrier-emulator`) returns a shipment number and price, or a rejection. `dispatch` **pulls** results from the emulator by polling.

## Repository layout and status

- `dispatch/` — the main application: accepts delivery requests, dispatches them to the carrier, tracks delivery and result, reconciles. Spring Boot 4 / Java 25 (package `com.acme.dispatch`). Stage-1 schema (Flyway V1–V5, see `docs/dispatch-design.md` §4), session sign-in, the requests API (create with `Idempotency-Key`, registry, card, `PATCH` comment/assignee) and the exchange with the carrier (send worker with retries, feed polling, `ResultInbox`) are implemented; reconciliation, problems and operator actions are not yet. Packages under `com.acme.dispatch`:
  - `auth` — users, services, `CurrentUser` principal, `SecurityConfig` (session cookie, SPA CSRF, 401/403 as Problem Details), login/logout/me, operators list;
  - `request` — `DeliveryRequest` aggregate, `RequestService` (write side), `RequestQueryService`/`RequestQueryDao` (registry and card SQL), `RequestPermissions` (pure access policy), `OverduePolicy`, request bodies;
  - `carrier` — `CarrierClient` / `HttpCarrierClient` (the only code that knows the carrier's HTTP API; own DTOs per `contracts/carrier-api.yaml`; classifies submit outcomes);
  - `exchange` — `DispatchOperationDao` (operation row = send queue), `DeliveryStatus`, `PayloadHasher`, `SendWorker` + `SendQueueDao` (claim/complete with conditional updates fenced by attempt number), `RetryPolicy`, `ExchangeAttemptDao`; `exchange/feed` — `FeedPoller`, `FeedCursorDao`; `exchange/inbox` — `ResultInbox` (single result handler), `ResultRules` (pure), `ResultMessage`, `InboxDao`;
  - Exchange tests: `@IntegrationTest` replaces the carrier with `FakeCarrier` (programmable answers, feed, `duringSubmit` hook) and turns the background workers off (`app.exchange.enabled=false`) — call `SendWorker.runOnce()` / `FeedPoller.runOnce()`; create requests with `TestRequests`, feed entries with `TestFeed`.
  - `idempotency` — `IdempotencyService` (must run inside the caller's transaction);
  - `common` — `BusinessCalendar` (only source of "now"/"today"), `SqlTime`; `common/web` — `ErrorCode`, `ApiException`, `ApiExceptionHandler`, `ProblemResponseWriter`, `PageResponse`;
  - `config` — `AppProperties`, `Clock`, `IdGenerator` (UUIDv7).
- `e2e/` — end-to-end tests over real HTTP against the running Docker stack (`E2eClient`: cookie jar, CSRF, JSON, for dispatch; `CarrierClient`: plain JSON, for the emulator at `CARRIER_BASE_URL`). Skipped by `./gradlew build`; run with `make e2e` after `make up`. They create their own data and must pass repeatedly on a non-empty database.
- `carrier-emulator/` — emulator of the external carrier: separate Spring Boot app (package `com.acme.carrier`) with its **own database** (database `carrier` in the shared PostgreSQL container). Deliberately minimal test stand; design in `docs/carrier-emulator-design.md`. The core works (receipt, at-most-once background execution, result feed, operation state, admin counters); fault modes are not implemented yet. Data access is `JdbcClient` only; own Flyway migrations; port 8081. The database and user are created by `docker/postgres/init/01-carrier-db.sh` on an empty volume only (existing volume → `make reset`). Packages under `com.acme.carrier`:
  - `operation` — `POST /operations` (idempotent by `operationId`, `ContentHash`; repeat counted, other content → `409`), `GET /operations/{id}`, `OperationDao` (state + latest result via a lateral join);
  - `execution` — `Executor` (single scheduler thread, the only feed writer: `ACCEPTED → EXECUTED` by conditional `UPDATE`, publishes `IN_PROGRESS` + final), `OutcomePolicy` (pure, deterministic);
  - `feed` — `GET /results`; `admin` — `GET /admin/operations`; `web` — `{code, message}` errors; `config` — `Clock`, UUIDv7, `CarrierProperties`.
  - Tests: `@IntegrationTest` (Testcontainers, fixed clock, executor off — call `Executor.runOnce()`); every MockMvc response is checked against `contracts/carrier-api.yaml` with `Contract.matchesContract()` — keep doing that in new tests.
- `contracts/` — OpenAPI contracts (`dispatch-api.yaml` for dispatch↔web client, `carrier-api.yaml` for dispatch↔carrier-emulator); `carrier-api.yaml` also holds the exchange rules and the emulator's `admin` endpoints. Every MockMvc response of a `dispatch` `@IntegrationTest` is validated against `dispatch-api.yaml` automatically (`support/ContractConfiguration` + `Contract`): every called path and method must be in the contract and every response must match it; request bodies and parameters are not checked. An endpoint added without a contract change fails the build (`ContractCheckTests`). A test that calls outside the contract on purpose sets the header `Contract.SKIP_HEADER` with the reason; actuator is exempt. Lint with `make contracts-lint` after every change in `contracts/`. `dispatch` and `carrier-emulator` must **not share Java classes**: the emulator stands in for an external system, so the contract in `contracts/` is the only agreement between them.

Gradle multi-project build: the root `build.gradle.kts` configures the Java 25 toolchain, `-parameters`, JUnit Platform, and `user.timezone=UTC` for tests in all subprojects. Plugin versions live in `gradle/libs.versions.toml`; library versions come from the Spring Boot BOM and are not pinned.

## Commands

```bash
cp .env.example .env   # first time
make up                # build and start postgres + dispatch + carrier-emulator in Docker
make db                # only PostgreSQL (for running dispatch from the IDE)
make run               # make db + ./gradlew :dispatch:bootRun
make test              # ./gradlew test — needs Docker running (Testcontainers)
make build             # ./gradlew build (with tests)
make reset             # stop everything and delete the DB volume
make logs              # follow dispatch logs (make logs-carrier — emulator)
make e2e               # e2e tests against the running stack (waits for both services first)
make carrier-ops       # emulator operations with receivedCount / executionCount
make contracts-lint    # lint contracts/ (Redocly CLI in Docker)
```

Verification gate for every change: `./gradlew build` (unit + integration) green, then `make up && make e2e` green. Changes in `contracts/` also need `make contracts-lint` green.

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
- **Tests — three levels:** unit (plain JUnit, `*Test`), integration (`*Tests`), e2e (`e2e/`, `*E2ETest`).
  - Every integration test uses exactly `@IntegrationTest` (`support/`): real PostgreSQL via Testcontainers + MockMvc + the test clock, one shared context and container. Keep the image (`postgres:18-alpine`) in sync with `docker-compose.yml`.
  - Fixtures in `dispatch/src/test/.../support/`: `DemoData` (demo ids/logins), `TestSecurity.asUser(users, login)`, `MutableClock` (reset to `TestClockConfiguration.START` in `@BeforeEach`), `TestRows.deleteRequestData(jdbc)` in `@AfterEach`. Mutating MockMvc calls need `.with(csrf())`.
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
