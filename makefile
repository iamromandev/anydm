## core
# variables
# This file owns no logic. Each target hands off to the stack that does:
# api/makefile for Python and Docker, ui/package.json for the SPA.
#
# Variables typed on the command line reach the sub-make on their own — GNU make
# forwards command-line overrides through MAKEFLAGS. Run `make -C api help` for
# the API's own targets.
API := api
UI := ui
BUN := bun

# phony targets
.PHONY: check help \
	api-check api-test api-test-all api-run api-up api-down api-build api-restart api-ps api-logs \
	api-migrate api-install api-export api-clean api-clean-db api-clean-system \
	ui-install ui-dev ui-restart ui-build ui-check ui-format

## both stacks
check: api-check ui-check # Lint + typecheck both stacks

## api — delegates to api/makefile
api-check: # Lint + typecheck the API
	$(MAKE) -C $(API) check

api-test: # Run the API unit tests
	$(MAKE) -C $(API) test

api-test-all: # Run every API test, integration included (needs api-up)
	$(MAKE) -C $(API) test-all

api-run: # Run the API dev server on the host
	$(MAKE) -C $(API) run

api-up: # Start the API and database containers
	$(MAKE) -C $(API) up

api-down: # Remove the API containers
	$(MAKE) -C $(API) down

api-build: # Build the API Docker images
	$(MAKE) -C $(API) build

api-restart: # Stop, rebuild, and start the API containers
	$(MAKE) -C $(API) restart

api-ps: # List the API containers
	$(MAKE) -C $(API) ps

api-logs: # Follow the API container logs
	$(MAKE) -C $(API) logs

api-migrate: # Run database migrations
	$(MAKE) -C $(API) migrate

api-install: # Install API dependencies
	$(MAKE) -C $(API) install

api-export: # Export requirements.txt
	$(MAKE) -C $(API) export

api-clean: # Stop containers, remove volumes and images
	$(MAKE) -C $(API) clean

api-clean-db: # Remove the database volume
	$(MAKE) -C $(API) clean-db

api-clean-system: # Prune all unused Docker data
	$(MAKE) -C $(API) clean-system

## ui — delegates to ui/package.json
ui-install: # Install UI dependencies
	$(BUN) install --cwd $(UI)

ui-dev: # Run the UI dev server (formats and typechecks first)
	$(BUN) run --cwd $(UI) web:dev

ui-restart: # Wipe node_modules + bun.lock, reinstall, then run the dev server
	$(BUN) run --cwd $(UI) web:restart

ui-build: # Build the UI for production
	$(BUN) run --cwd $(UI) web:build

ui-check: # Format check + typecheck the UI
	$(BUN) run --cwd $(UI) web:fmt.chk
	$(BUN) run --cwd $(UI) web:chk

ui-format: # Format the UI sources
	$(BUN) run --cwd $(UI) web:fmt

# help
help:
	@grep -E '^[a-zA-Z_-]+:.*?#' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?#"}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
