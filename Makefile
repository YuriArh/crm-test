# Local settings (ports, credentials) are shared by Docker Compose and `make run`.
-include .env
export

.PHONY: help up down reset logs logs-carrier ps db build test run wait e2e seed contracts-lint carrier-ops \
        faults fault-unavailable fault-lose-response fault-duplicate fault-delayed fault-off \
        web-install web-dev web-build web-test web-e2e web-api-types web-api-check

REDOCLY_IMAGE := redocly/cli:1.34.3

E2E_BASE_URL ?= http://localhost:$(or $(DISPATCH_PORT),8080)
CARRIER_BASE_URL ?= http://localhost:$(or $(CARRIER_PORT),8081)
WEB_BASE_URL ?= http://localhost:$(or $(WEB_PORT),3000)
# Playwright reads WEB_BASE_URL: set only where a target passes it, otherwise it tests the dev server.
unexport WEB_BASE_URL

help: ## Show available commands
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

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

seed: wait ## Create demo requests through the API (safe to re-run; start the stack with make up)
	./scripts/seed.sh $(E2E_BASE_URL)

carrier-ops: ## Show carrier-emulator operations with execution counters (newest 100)
	@curl -sf $(CARRIER_BASE_URL)/admin/operations || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"

# PUT /admin/faults with the four switches in the order unavailable, loseResponse, duplicate, delayed.
define set-faults
	@curl -sf -X PUT $(CARRIER_BASE_URL)/admin/faults -H 'Content-Type: application/json' \
	  -d '{"unavailable":$(1),"loseResponse":$(2),"duplicate":$(3),"delayed":$(4)}' \
	  && echo || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"
endef

faults: ## Show carrier-emulator fault modes
	@curl -sf $(CARRIER_BASE_URL)/admin/faults && echo || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"

fault-unavailable: ## Switch on only the unavailable fault mode
	$(call set-faults,true,false,false,false)

fault-lose-response: ## Switch on only the loseResponse fault mode
	$(call set-faults,false,true,false,false)

fault-duplicate: ## Switch on only the duplicate fault mode
	$(call set-faults,false,false,true,false)

fault-delayed: ## Switch on only the delayed fault mode
	$(call set-faults,false,false,false,true)

fault-off: ## Switch off all fault modes
	$(call set-faults,false,false,false,false)

contracts-lint: ## Lint the OpenAPI contracts in contracts/ (Redocly CLI in Docker)
	docker run --rm -v "$(CURDIR)/contracts:/spec" -w /spec $(REDOCLY_IMAGE) lint carrier-api.yaml dispatch-api.yaml

web-install: ## Install web/ dependencies (pnpm via corepack) and the Playwright browser
	corepack enable
	cd web && pnpm install --frozen-lockfile && pnpm e2e:install

web-dev: ## Run the web client on :5173 (proxies /api to dispatch on :8080: make run or make up)
	cd web && pnpm dev

web-build: ## Check API types against the contract, typecheck, unit-test and build web/
	cd web && pnpm api:check && pnpm typecheck && pnpm test && pnpm build

web-test: ## Run the web unit tests (Vitest)
	cd web && pnpm test

web-api-types: ## Regenerate web/src/shared/api/schema.d.ts from contracts/dispatch-api.yaml
	cd web && pnpm api:types

web-api-check: ## Fail if web/src/shared/api/schema.d.ts is out of date with the contract
	cd web && pnpm api:check

web-e2e: ## Playwright: against the Vite dev server, then against the Docker stack on :3000
	cd web && pnpm e2e
	@ok=; for i in $$(seq 1 45); do curl -sf $(WEB_BASE_URL)/api/system/info >/dev/null && { ok=1; break; }; sleep 2; done; \
	  [ -n "$$ok" ] || { echo "web is not reachable at $(WEB_BASE_URL) (make up)"; exit 1; }
	cd web && WEB_BASE_URL=$(WEB_BASE_URL) pnpm e2e

