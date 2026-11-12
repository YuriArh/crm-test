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
