# Exchange with an external system

Test assignment: `04-system-integration.md`. Work plan: `docs/PLAN.md`.

## Structure

```
dispatch/          main application: accepts delivery requests, dispatches them
                   to the carrier, tracks delivery and result, reconciles
carrier-emulator/  emulator of the external delivery carrier (not implemented yet)
contracts/         OpenAPI contracts
web/               React client (later)
docs/              plan and documentation
```

## Requirements

- Docker with Docker Compose
- JDK 25 — only to build and run `dispatch` outside Docker

## Run

```bash
cp .env.example .env
make up        # build and start postgres + dispatch
make ps        # service status
```

Check: `curl http://localhost:8080/actuator/health` and `curl http://localhost:8080/api/system/info`.

If port 5432 or 8080 is already taken, change `DB_PORT` / `DISPATCH_PORT` in `.env`. Both `docker compose` and `make run` read it.

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
- Errors are Problem Details (RFC 9457) with a machine-readable `code`: `VALIDATION_FAILED` (with `errors[]` per field), `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `UNAUTHENTICATED`, `BAD_CREDENTIALS`, `FORBIDDEN`, `NOT_FOUND`, `VERSION_CONFLICT` (with the `current` state), `INVALID_ASSIGNEE`. Other HTTP errors carry the status name as the code, e.g. `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`.
- A repeated create with the same `Idempotency-Key` returns the first response with header `Idempotent-Replayed: true`.

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

curl -s -b "$JAR" "$API/api/requests?businessStatus=AWAITING"

# Edit: use the id and version from the card
curl -s -c "$JAR" -b "$JAR" -X PATCH -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -d '{"version":0,"comment":"Fragile"}' $API/api/requests/<id>
```

## Tests

```bash
make test      # unit + integration tests (integration tests use Testcontainers: Docker must be running)
make up        # start the stack
make e2e       # end-to-end tests over real HTTP against the running stack
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
