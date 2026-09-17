## core
# variables
DOCKER_COMPOSE := docker compose -f docker-compose.yml -p anydm
UV := uv
UVX := $(UV)x
SERVER_CONTAINER := server-anydm-fastapi

# phony targets
.PHONY: clean-host clean-volumes clean-all ps build up stop down restart install install-dev check test test-all run export add logs migrate help

## operation
# system cleanup
clean-host: # Prune all unused Docker data on the host
	docker system prune --all --force
	docker volume prune --all --force
	docker buildx prune --all --force
	docker builder prune --all --force

clean-volumes: # Remove this project's volumes (db, download, torrent)
	$(DOCKER_COMPOSE) down -v

clean-all: # Stop containers, remove volumes & images
	$(DOCKER_COMPOSE) down -v --rmi all

# docker control
ps: # List containers
	$(DOCKER_COMPOSE) ps -a

build: # Build Docker images
	$(DOCKER_COMPOSE) build

up: # Start containers
	$(DOCKER_COMPOSE) up -d

stop: # Stop containers
	$(DOCKER_COMPOSE) stop

down: # Remove containers
	$(DOCKER_COMPOSE) down

restart: # Restart containers
	make stop
	make build
	make up

logs: # Show live logs
	$(DOCKER_COMPOSE) logs -f

# python tools (uv)
install: # Install all dependencies
	$(UV) sync

install-dev: # Add development dependencies
	$(UV) add --dev ruff ty

check: # Run lint + type checks
	make install
	$(UVX) ruff check --fix
	$(UV) run ty check

test: # Run the unit test suite
	$(UV) run pytest

test-all: # Run every test, integration included (needs `make up`)
	$(UV) run pytest -m ""

run: # Run FastAPI dev server
	make check
	$(UV) run uvicorn src.main:app --reload --loop uvloop --port 8030

export: # Export requirements.txt
	$(UV) export --format requirements-txt --output requirements.txt

# git
add: # Stage all changes
	git add .

# database scripts
migrate: # Run database migrations
	docker exec $(SERVER_CONTAINER) python -m scripts.migrate

# help
help:
	@grep -E '^[a-zA-Z_-]+(\\:[a-zA-Z_-]+)*:.*#' $(MAKEFILE_LIST) | awk 'match($$0, /^[a-zA-Z_-]+(\\:[a-zA-Z_-]+)*/) { name = substr($$0, 1, RLENGTH); gsub(/\\/, "", name); help = $$0; sub(/^[^#]*#/, "", help); printf "\033[36m%-20s\033[0m %s\n", name, help }'
