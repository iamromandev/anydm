## core
# variables
API_DIR := api
UI_DIR := ui

# Every target with a colon in its name is escaped for GNU Make 3.81, which is
# what ships on macOS. Unescaped, `api:run` is parsed as target `api` with
# prerequisite `run` and silently does the wrong thing.
.PHONY: api\:install api\:run api\:test api\:test-all api\:check api\:up api\:down api\:logs api\:migrate ui\:install ui\:dev ui\:build ui\:check check help

## api
api\:install: # Install Python dependencies
	$(MAKE) -C $(API_DIR) install

api\:run: # Run the FastAPI dev server
	$(MAKE) -C $(API_DIR) run

api\:test: # Run the Python unit tests
	$(MAKE) -C $(API_DIR) test

api\:test-all: # Run every Python test, integration included (needs api:up)
	$(MAKE) -C $(API_DIR) test-all

api\:check: # Lint and typecheck the API
	$(MAKE) -C $(API_DIR) check

api\:up: # Start the API stack (Postgres + server)
	$(MAKE) -C $(API_DIR) up

api\:down: # Stop the API stack
	$(MAKE) -C $(API_DIR) down

api\:logs: # Follow API logs
	$(MAKE) -C $(API_DIR) logs

api\:migrate: # Apply database migrations
	$(MAKE) -C $(API_DIR) migrate

## ui
ui\:install: # Install UI dependencies
	cd $(UI_DIR) && bun install

ui\:dev: # Run the UI dev server
	cd $(UI_DIR) && bun run web:dev

ui\:build: # Production build for the UI
	cd $(UI_DIR) && bun run web:build

ui\:check: # Format check and typecheck the UI
	cd $(UI_DIR) && bun run web:fmt.chk && bun run web:chk

## both
check: # Check both halves, the way CI does
	$(MAKE) api:check
	$(MAKE) ui:check

# help
# The name class carries `\:` so the escaped targets are listed too: a plain
# `[a-zA-Z_-]+:` stops dead at the backslash. The backslash is stripped for
# display, since it is make's escape and not part of the name an operator types.
help:
	@grep -E '^[a-zA-Z_-]+(\\:[a-zA-Z_-]+)*:.*#' $(MAKEFILE_LIST) | awk 'match($$0, /^[a-zA-Z_-]+(\\:[a-zA-Z_-]+)*/) { name = substr($$0, 1, RLENGTH); gsub(/\\/, "", name); help = $$0; sub(/^[^#]*#/, "", help); printf "\033[36m%-16s\033[0m %s\n", name, help }'
