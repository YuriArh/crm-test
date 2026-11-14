# Exchange with an external system

Test assignment: `04-system-integration.md`. Work plan: `docs/PLAN.md`.

## Structure

```
dispatch/          main application: accepts delivery requests, dispatches them
                   to the carrier, tracks delivery and result, reconciles
carrier-emulator/  emulator of the external delivery carrier (own database `carrier`)
contracts/         OpenAPI contracts (dispatch-api.yaml: web ↔ dispatch, carrier-api.yaml: dispatch ↔ carrier)
docker/            PostgreSQL init script (creates the carrier database)
web/               web client: React + TypeScript + Vite + Mantine, served by nginx in Docker
docs/              plan and documentation
```

## Requirements

- Docker with Docker Compose
- JDK 25 — only to build and run `dispatch` outside Docker
- Node.js 22.12+ with corepack (pnpm; `make web-install` enables it) — only to develop `web/` outside Docker

## Run

```bash
cp .env.example .env
make up        # build and start postgres + dispatch + carrier-emulator + web
make ps        # service status
```

Check: `curl http://localhost:8080/actuator/health`, `curl http://localhost:8080/api/system/info` and `curl http://localhost:8081/actuator/health` (emulator).

The web client is at http://localhost:3000. It and the API share this origin: nginx serves the static build and proxies `/api` to `dispatch`.

If port 5432, 8080, 8081 or 3000 is already taken, change `DB_PORT` / `DISPATCH_PORT` / `CARRIER_PORT` / `WEB_PORT` in `.env`. Both `docker compose` and `make run` read it.

The emulator's database is created by `docker/postgres/init` only when the PostgreSQL volume is empty. If the stack was started before the emulator existed, run `make reset` once (it deletes local data).

## Demo users

Password for all users: `demo1234`.

| Login | Role | Service |
|---|---|---|
| `north.initiator` | initiator | North |
| `north.operator` | operator | North |
| `south.initiator` | initiator | South |

The operator sees only North requests; each initiator sees only their own.

## API

| Method and path | Who | What |
|---|---|---|
| `GET /api/auth/csrf` | anyone | sets the `XSRF-TOKEN` cookie and returns the token |
| `POST /api/auth/login` | anyone | `{username, password}` → session cookie + current user |
| `POST /api/auth/logout` | signed in | ends the session (204) |
| `GET /api/auth/me` | signed in | current user and service |
| `GET /api/users/operators` | operator | operators of the own service (assignee picker) |
| `POST /api/requests` | initiator | create a request; header `Idempotency-Key` required; the request is queued for the carrier at once |
| `GET /api/requests` | signed in | registry: `businessStatus`, `deliveryStatus`, `overdue`, `page`, `size` (≤ 100), `sort` (`createdAt` / `deadline` / `number`, `,asc` / `,desc`) |
| `GET /api/requests/{id}` | signed in | request card; operators also get the `exchange` block |
| `PATCH /api/requests/{id}` | owner / operator | `{version, comment?, assigneeId?}`; an omitted field is kept, `null` clears it; only an operator sets the assignee |

- Every mutating request needs the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie; the token changes after login, so re-read the cookie.
- Errors are Problem Details (RFC 9457) with a machine-readable `code`: `VALIDATION_FAILED` (with `errors[]` per field), `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `UNAUTHENTICATED`, `BAD_CREDENTIALS`, `FORBIDDEN`, `CSRF_INVALID` (missing or stale CSRF token; fetch a new one and repeat), `NOT_FOUND`, `VERSION_CONFLICT` (with the `current` state), `INVALID_ASSIGNEE`. Other HTTP errors carry the status name as the code, e.g. `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`.
- A repeated create with the same `Idempotency-Key` returns the first response with header `Idempotent-Replayed: true`.

### How a request reaches its result

1. `POST /api/requests` saves the request and puts its operation into the send queue in one transaction: `deliveryStatus: PENDING`, `businessStatus: AWAITING`.
2. The send worker (every ~1 s) sends it to the carrier with its immutable `operationId` → `ACKNOWLEDGED`. If the carrier is unreachable or answers `5xx` → `RETRY_WAIT`; no answer in time → `UNKNOWN`; both are retried with the same `operationId` (pause from 5 s, doubling up to 5 min, at most 8 attempts, then `FAILED`). `4xx` → `FAILED`.
3. The feed poller (every ~1 s) reads the carrier's result feed and passes each entry to one handler: the card moves to `IN_PROGRESS`, then `SUCCEEDED` (tracking number, price) or `REJECTED` (reason). Duplicates and outdated results have no effect; a final result is never overwritten.
4. A result also confirms delivery: a request stuck in `UNKNOWN` becomes `ACKNOWLEDGED` and is not resent.

Operators see the exchange state in the card's `exchange` block: attempts, next attempt, last error, when and how delivery was confirmed. Each result changes the request's `version`, so read the card again before editing.

### Walkthrough with curl

```bash
JAR=$(mktemp)                       # cookie jar: session + CSRF cookie
API=http://localhost:8080
token() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR"; }

curl -s -c "$JAR" -b "$JAR" $API/api/auth/csrf > /dev/null
curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -d '{"username":"north.initiator","password":"demo1234"}' $API/api/auth/login

# Create (deadline must not be before today in Europe/Moscow)
curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"cargoDescription":"Pallets","weightKg":12.5,"deliveryAddress":"Main st 1","deadline":"2026-12-01"}' \
     $API/api/requests

curl -s -b "$JAR" "$API/api/requests?businessStatus=SUCCEEDED"   # a second or two later: executed by the carrier

# Edit: use the id and the current version from the card
curl -s -c "$JAR" -b "$JAR" -X PATCH -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -d '{"version":<version>,"comment":"Fragile"}' $API/api/requests/<id>
```

## Carrier emulator

`carrier-emulator` (port 8081) stands in for the external delivery carrier. Contract and exchange rules: `contracts/carrier-api.yaml`; design: `docs/carrier-emulator-design.md`; fault modes: `docs/specs/carrier-faults.md`.

| Method and path | What |
|---|---|
| `POST /operations` | receive an operation; `202` also for a repeat with the same content (no second execution), `409 PAYLOAD_MISMATCH` for the same `operationId` with other content |
| `GET /operations/{operationId}` | carrier's state and latest result (for reconciliation); `404` if unknown |
| `GET /results?after=N&limit=100` | result feed after cursor `N`: `IN_PROGRESS`, then `SUCCEEDED` / `REJECTED` |
| `GET /admin/operations[?operationId=]` | operations with `receivedCount`, `executionCount`, remembered `faults` and `resultPublishAt` (also `make carrier-ops`) |
| `GET` / `PUT /admin/faults` | fault mode switches (also `make faults`, `make fault-*`) |

- Operations are executed in the background about a second after receipt.
- The outcome is deterministic: weight over 10 000 kg or an address containing `тест-отказ` → rejected; otherwise a tracking number `TRK-…` and price `300 + 25 × weight`.
- Assumption: no authentication — the emulator is a local test stand inside the Docker network.

```bash
ID=$(uuidgen)
curl -s -H 'Content-Type: application/json' \
     -d "{\"operationId\":\"$ID\",\"cargoDescription\":\"Pallets\",\"weightKg\":12.5,\"deliveryAddress\":\"Main st 1\",\"deadline\":\"2026-12-01\"}" \
     localhost:8081/operations
sleep 2
curl -s localhost:8081/operations/$ID
curl -s "localhost:8081/results?after=0"
```

### Fault modes

Four switches in the emulator's memory (all off after a restart): `unavailable` (`503` on the exchange endpoints), `loseResponse` (the response to the first receipt is held until the operation is executed and 10 s have passed — longer than dispatch's 5 s timeout), `duplicate` (every feed entry published twice under the same `messageId`), `delayed` (executed at once, feed entries published after 15 s on the stack, the final result before `IN_PROGRESS`). `duplicate` and `delayed` are remembered by an operation when it is received. Every `make fault-*` switches on only its mode; combinations go through `PUT /admin/faults`. Delays: `CARRIER_LOSE_RESPONSE_DELAY`, `CARRIER_DELAYED_RESULT_DELAY`.

```bash
make fault-lose-response   # create a request → result via the feed, acknowledged via RESULT, no resend
make carrier-ops           # one operation, receivedCount = 1, state = EXECUTED
curl -X PUT localhost:8081/admin/faults -H 'Content-Type: application/json' \
  -d '{"unavailable":false,"loseResponse":true,"duplicate":false,"delayed":true}'
                           # create a request → UNKNOWN, resend, ACKNOWLEDGED via RESPONSE, result after ~15 s
make carrier-ops           # one operation, receivedCount = 2, state = EXECUTED
make fault-duplicate       # create a request → one result, two entries per message in the feed
make fault-delayed         # create a request → result after ~15 s, the late IN_PROGRESS is ignored
make fault-unavailable     # create a request → RETRY_WAIT; then make fault-off → result
make fault-off
make faults                # current switches
```

What to look at: the dispatch card as an operator (`deliveryStatus`, `businessStatus`, `exchange.attempts`, `exchange.acknowledgedVia`, `exchange.lastError`), `make carrier-ops` (exactly one operation per request, `receivedCount`, `faults`, `resultPublishAt`) and the emulator log (`make logs-carrier`, lines `Fault <mode>: …`).

## Tests

```bash
make test      # unit + integration tests (integration tests use Testcontainers: Docker must be running)
make up        # start the stack
make e2e       # end-to-end tests over real HTTP against the running stack
make contracts-lint   # lint contracts/ (after changing a contract)
```

e2e tests create their own data and can run repeatedly against a non-empty database.

## Development

```bash
make db        # start only PostgreSQL
make run       # run dispatch locally (./gradlew :dispatch:bootRun)
make test      # tests; integration tests use Testcontainers, so Docker must be running
```

`make help` lists all commands.

## Conventions

- Instants are stored and processed in UTC; the business time zone is `APP_TIMEZONE` (default `Europe/Moscow`).
- The database schema is managed only by Flyway migrations (`dispatch/src/main/resources/db/migration`).
- Data access: Spring Data JDBC for entities, explicit SQL via `JdbcClient` for the exchange core; no JPA/Hibernate.

## Web client

```bash
make web-install   # once: install web/ dependencies and the Playwright browser
make web-dev       # http://localhost:5173, proxies /api to dispatch on :8080 (make run or make up)
make web-build     # contract types check, typecheck, unit tests, production build
make web-e2e       # Playwright: against the dev server, then against the stack on :3000 (make up first)
```

- Stack: React 19, TypeScript (strict), Vite, TanStack Router, TanStack Query, Mantine; pnpm project outside Gradle.
- Design system in the spirit of Linear (dark by default, light scheme behind the switch in the header):
  tokens, Mantine theme and rules in `docs/design-system.md`.
- Modular structure: `src/app/` wires the app (query client, router, theme), `src/shared/` holds code used by
  several modules, each feature module gets its own folder (and its own `e2e/<module>/` folder).
- Data layer `src/shared/api/`: types generated from `contracts/dispatch-api.yaml`, one HTTP client with the
  CSRF header and Problem Details errors. After changing the contract run `make web-api-types`;
  `make web-build` fails while the types are out of date.
- Sign-in: open http://localhost:3000 and sign in as a demo user (above; the login page lists them too).
  Any page opened without a session leads to the login page and back afterwards. The header shows the user,
  role and service, and has the sign-out button. When the session is lost (sign-out in another tab,
  `dispatch` restarted) the next action leads to the login page.
- Requests: after sign-in the registry opens (`/requests`). Filters «Результат», «Доставка» (operators) and
  «Только просроченные», the sort and the page are kept in the address, so a link or Back restores them; on a
  phone the table becomes cards and the filters move into a bottom sheet. The list refreshes itself every 10 s.
- Initiators create a request with «Новая заявка»: the form checks the fields, shows «Проверьте данные», and
  then sends; a repeated click or a retry after a lost answer creates one request. The deadline cannot be
  before today in the business time zone.
- The request card shows the content (read-only — it cannot change after creation), both statuses, the
  carrier's result and, for operators, the exchange details; it refreshes every 5 s until the result is final.
  Anyone who sees the request can edit the comment, operators choose the assignee; if someone else changed
  the same field meanwhile, nothing is overwritten silently.
- `make web-e2e` runs the browser tests against the stack as it was built: after changing `web/`, rebuild it
  with `make up` (or `docker compose up -d --build web`) first.
