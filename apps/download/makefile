## core
# variables
DOCKER_COMPOSE := docker compose -f docker-compose.yml
UV := uv
UVX := $(UV)x

# phony targets
.PHONY: clean-system clean-db clean ps build up stop down restart install install-dev check run export add logs help

## operation
# system cleanup
clean-system: # Prune all unused Docker data
	docker system prune --all --force
	docker volume prune --all --force
	docker buildx prune --all --force
	docker builder prune --all --force

clean-db: # Remove DB volumes
	$(DOCKER_COMPOSE) down -v

clean: # Stop all containers, remove volumes & images
	$(DOCKER_COMPOSE) down -v --rmi all

# docker control
ps: # List containers
	$(DOCKER_COMPOSE) ps -a

build: # Build Docker images
	COMPOSE_BAKE=true $(DOCKER_COMPOSE) build

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
	$(UV) add --dev ruff mypy pytest pytest-asyncio

check: # Run lint + type checks
	$(UVX) ruff check --fix
	#$(UVX) ty check

run: # Run FastAPI dev server
	make check
	$(UV) run uvicorn src.main:app --reload

export: # Export requirements.txt
	$(UV) export --format requirements-txt --output requirements.txt

# git
add: # Stage all changes
	git add .

# help
help:
	@grep -E '^[a-zA-Z_-]+:.*?#' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?#"}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'