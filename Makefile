# Local settings (ports, credentials) are shared by Docker Compose and `make run`.
-include .env
export

.PHONY: help up down reset logs logs-carrier ps db build test run wait e2e seed contracts-lint carrier-ops inbox \
        faults fault-unavailable fault-lose-response fault-duplicate fault-delayed fault-malformed fault-off \
        dispatch-kill dispatch-start carrier-kill carrier-start db-kill db-start \
        web-install web-dev web-build web-test web-e2e web-api-types web-api-check \
        load-up load-down load-reset load-hello load-install load-typecheck load-seed

REDOCLY_IMAGE := redocly/cli:1.34.3

# Load testing (NF08): k6 in Docker against a separate stack — Compose project exchange-load with its own
# database volume and ports, the same resource limits. Inline variables override the ones exported from .env.
K6_IMAGE := grafana/k6:2.3.0
LOAD_PROJECT := exchange-load
LOAD_COMPOSE := COMPOSE_PROJECT_NAME=$(LOAD_PROJECT) DB_PORT=15432 DISPATCH_PORT=18080 CARRIER_PORT=18081 \
                WEB_PORT=13000 docker compose
# k6 runs outside the stack's limits, in the stack's network, and hits dispatch directly (server API time).
# Scripts (TypeScript) live in load/, results go to load/out/ (ignored by git). Usage: $(K6) run /scripts/<script>.ts
K6 = docker run --rm --network $(LOAD_PROJECT)_default -u $$(id -u):$$(id -g) \
       -v $(CURDIR)/load:/scripts -w /scripts \
       -e BASE_URL=http://dispatch:8080 -e CARRIER_URL=http://carrier-emulator:8081 $(K6_IMAGE)

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

# Crash and restart during the exchange (D04, D-NF01, NF03; docs/PLAN.md, stage 2, item 2). SIGKILL, not a
# graceful stop: the process dies mid-call like a real crash. When to kill and how long to stay down is up to you.
dispatch-kill: ## Crash dispatch (SIGKILL) in the middle of whatever it is doing
	docker compose kill dispatch

dispatch-start: ## Start dispatch after dispatch-kill and wait until it is healthy (sign in again: sessions are in memory)
	docker compose start dispatch
	@$(MAKE) --no-print-directory wait

carrier-kill: ## Crash carrier-emulator (SIGKILL); its fault modes are off after carrier-start (they live in memory)
	docker compose kill carrier-emulator

carrier-start: ## Start carrier-emulator after carrier-kill and wait until dispatch and carrier-emulator are healthy
	docker compose start carrier-emulator
	@$(MAKE) --no-print-directory wait

db-kill: ## Crash PostgreSQL (SIGKILL); dispatch and carrier-emulator lose their databases but keep running
	docker compose kill postgres

db-start: ## Start PostgreSQL after db-kill and wait until dispatch and carrier-emulator are healthy again
	docker compose start postgres
	@$(MAKE) --no-print-directory wait

LOAD_DISPATCH_URL := http://localhost:18080
LOAD_CARRIER_URL := http://localhost:18081

load-up: ## Build and start the load-test stack and wait until it is healthy (own DB volume, ports 18080/18081/13000/15432)
	$(LOAD_COMPOSE) up -d --build
	@for url in $(LOAD_DISPATCH_URL) $(LOAD_CARRIER_URL); do \
	  ok=; for i in $$(seq 1 45); do curl -sf $$url/actuator/health >/dev/null && { ok=1; break; }; sleep 2; done; \
	  [ -n "$$ok" ] || { echo "not healthy at $$url"; exit 1; }; \
	done

load-down: ## Stop the load-test stack (data is kept)
	$(LOAD_COMPOSE) down

load-reset: ## Stop the load-test stack and delete its database volume
	$(LOAD_COMPOSE) down -v

load-install: ## Install TypeScript and k6 types in load/ (pnpm via corepack; k6 itself is a Docker image)
	cd load && corepack pnpm install --frozen-lockfile

load-typecheck: ## Type-check the k6 scripts in load/ (tsc --noEmit; k6 runs the .ts files itself)
	cd load && corepack pnpm typecheck

load-seed: ## Create the load data set through the API on an empty load stand (PROFILE=full: 5000 requests, smoke: 200)
	$(K6) run -e PROFILE=$(or $(PROFILE),full) -e RUN_ID=$$(date +%s) /scripts/seed.ts

load-hello: ## Minimal k6 script against the load-test stack (make load-up first)
	$(K6) run /scripts/hello.ts

seed: wait ## Create demo requests through the API (safe to re-run; start the stack with make up)
	CARRIER_BASE_URL=$(CARRIER_BASE_URL) ./scripts/seed.sh $(E2E_BASE_URL)

inbox: ## Show the newest 30 carrier results received by dispatch: status, reason, processing attempts
	@docker compose exec -T postgres psql -U $(or $(DB_USER),dispatch) -d $(or $(DB_NAME),dispatch) -c \
	  "select to_char(received_at, 'HH24:MI:SS') as received, feed_seq, processing_status as status, attempts, \
	          left(reason, 90) as reason, left(last_error, 50) as last_error, message_id, operation_id \
	     from inbox order by received_at desc, feed_seq desc nulls last limit 30"

carrier-ops: ## Show carrier-emulator operations with execution counters (newest 100)
	@curl -sf $(CARRIER_BASE_URL)/admin/operations || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"

# PUT /admin/faults with the five switches in the order unavailable, loseResponse, duplicate, delayed, malformed.
define set-faults
	@curl -sf -X PUT $(CARRIER_BASE_URL)/admin/faults -H 'Content-Type: application/json' \
	  -d '{"unavailable":$(1),"loseResponse":$(2),"duplicate":$(3),"delayed":$(4),"malformed":$(5)}' \
	  && echo || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"
endef

faults: ## Show carrier-emulator fault modes
	@curl -sf $(CARRIER_BASE_URL)/admin/faults && echo || echo "carrier-emulator is not reachable at $(CARRIER_BASE_URL)"

fault-unavailable: ## Switch on only the unavailable fault mode
	$(call set-faults,true,false,false,false,false)

fault-lose-response: ## Switch on only the loseResponse fault mode
	$(call set-faults,false,true,false,false,false)

fault-duplicate: ## Switch on only the duplicate fault mode
	$(call set-faults,false,false,true,false,false)

fault-delayed: ## Switch on only the delayed fault mode
	$(call set-faults,false,false,false,true,false)

fault-malformed: ## Switch on only the malformed fault mode (feed responses carry a broken price in SUCCEEDED entries)
	$(call set-faults,false,false,false,false,true)

fault-off: ## Switch off all fault modes
	$(call set-faults,false,false,false,false,false)

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

