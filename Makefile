# Local settings (ports, credentials) are shared by Docker Compose and `make run`.
-include .env
export

.PHONY: help up down reset logs ps db build test run

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-8s %s\n", $$1, $$2}'

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
