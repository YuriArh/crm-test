# Local settings (ports, credentials) are shared by Docker Compose and `make run`.
-include .env
export

.PHONY: help up down reset logs ps db build test run wait e2e contracts-lint

REDOCLY_IMAGE := redocly/cli:1.34.3

E2E_BASE_URL ?= http://localhost:$(or $(DISPATCH_PORT),8080)

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

wait: ## Wait until dispatch reports healthy (up to 90 s)
	@for i in $$(seq 1 45); do curl -sf $(E2E_BASE_URL)/actuator/health >/dev/null && exit 0; sleep 2; done; \
	echo "dispatch is not healthy at $(E2E_BASE_URL)"; exit 1

e2e: wait ## Run end-to-end tests against the running stack (start it with make up)
	E2E_BASE_URL=$(E2E_BASE_URL) ./gradlew :e2e:test -Pe2e

contracts-lint: ## Lint the OpenAPI contracts in contracts/ (Redocly CLI in Docker)
	docker run --rm -v "$(CURDIR)/contracts:/spec" -w /spec $(REDOCLY_IMAGE) lint carrier-api.yaml
