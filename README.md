# Exchange with an external system

Test assignment: `04-system-integration.md`. Work plan: `docs/PLAN.md`.

## Structure

```
backend/     Spring Boot: API and background workers
emulator/    external service emulator (not implemented yet)
contracts/   OpenAPI contracts
web/         React client (later)
docs/        plan and documentation
```

## Requirements

- Docker with Docker Compose
- JDK 21 — only to build and run the backend outside Docker

## Run

```bash
cp .env.example .env
make up        # build and start postgres + backend
make ps        # service status
```

Check: `curl http://localhost:8080/actuator/health` and `curl http://localhost:8080/api/system/info`.

If port 5432 or 8080 is already taken, change `DB_PORT` / `BACKEND_PORT` in `.env`. Both `docker compose` and `make run` read it.

## Development

```bash
make db        # start only PostgreSQL
make run       # run the backend locally (./gradlew :backend:bootRun)
make test      # tests; integration tests use Testcontainers, so Docker must be running
```

`make help` lists all commands.

## Conventions

- Instants are stored and processed in UTC; the business time zone is `APP_TIMEZONE` (default `Europe/Moscow`).
- The database schema is managed only by Flyway migrations (`backend/src/main/resources/db/migration`); Hibernate validates it and never changes it.
