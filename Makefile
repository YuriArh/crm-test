# Local settings (ports, credentials) are shared by Docker Compose and `make run`.
-include .env
export

.PHONY: help up down reset logs logs-carrier ps db build test run wait e2e contracts-lint carrier-ops \
        web-install web-dev web-build web-test web-e2e web-api-types

REDOCLY_IMAGE := redocly/cli:1.34.3

E2E_BASE_URL ?= http://localhost:$(or $(DISPATCH_PORT),8080)
CARRIER_BASE_URL ?= http://localhost:$(or $(CARRIER_PORT),8081)
WEB_BASE_URL ?= http://localhost:$(or $(WEB_PORT),3000)

help: ## Show available commands
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-15s %s\n", $$1, $$2}'

up: ## Build and start all services in Docker
	docker compose up -d --build

down: ## Stop all services (data is kept)
	docker compose down

reset: ## Stop all services and delete the database volume
	docker compose down -v

logs: ## Follow dispatch logs
	docker compose logs -f dispatch

logs-carrier: ## Follow carrier-emulator logs
	docker compose logs -f carrier-emulator

ps: ## Show service status
	docker compose ps

db: ## Start only PostgreSQL (for running dispatch from the IDE)
	docker compose up -d postgres

build: ## Build all Gradle projects (with tests)
	./gradlew build

test: ## Run all tests (needs Docker for Testcontainers)
	./gradlew test

run: db ## Run dispatch locally against the Docker database
	./gradlew :dispatch:bootRun

wait: ## Wait until dispatch and carrier-emulator report healthy (up to 90 s each)
	@for url in $(E2E_BASE_URL) $(CARRIER_BASE_URL); do \
	  ok=; for i in $$(seq 1 45); do curl -sf $$url/actuator/health >/dev/null && { ok=1; break; }; sleep 2; done; \
	  [ -n "$$ok" ] || { echo "not healthy at $$url"; exit 1; }; \
	done

e2e: wait ## Run end-to-end tests against the running stack (start it with make up)
	E2E_BASE_URL=$(E2E_BASE_URL) CARRIER_BASE_URL=$(CARRIER_BASE_URL) ./gradlew :e2e:test -Pe2e

carrier-ops: ## Show carrier-emulator operations with execution counters (newest 100)
	@curl -sf $(CARRIER_BASE_URL)/admin/operations || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"

contracts-lint: ## Lint the OpenAPI contracts in contracts/ (Redocly CLI in Docker)
	docker run --rm -v "$(CURDIR)/contracts:/spec" -w /spec $(REDOCLY_IMAGE) lint carrier-api.yaml dispatch-api.yaml

web-install: ## Install web/ dependencies (pnpm via corepack)
	cd web && pnpm install --frozen-lockfile

web-dev: ## Run the web client on :5173 (proxies /api to dispatch on :8080: make run or make up)
	cd web && pnpm dev

web-build: ## Typecheck, lint, unit-test and build the web client
	cd web && pnpm typecheck && pnpm lint && pnpm test && pnpm build

web-test: ## Run the web unit and component tests (Vitest)
	cd web && pnpm test

web-api-types: ## Regenerate web/src/api/schema.d.ts from contracts/dispatch-api.yaml
	cd web && pnpm api:types

web-e2e: ## Run browser scenarios (Playwright) against the running stack (start it with make up)
	@ok=; for i in $$(seq 1 45); do curl -sf $(WEB_BASE_URL)/api/system/info >/dev/null && { ok=1; break; }; sleep 2; done; \
	  [ -n "$$ok" ] || { echo "web is not reachable at $(WEB_BASE_URL)"; exit 1; }
	cd web && pnpm exec playwright install chromium && WEB_BASE_URL=$(WEB_BASE_URL) pnpm e2e
