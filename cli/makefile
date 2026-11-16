## core
# variables
UV := uv
UVX := $(UV)x

# phony targets
.PHONY: install check check-ci test test-all run tool-install tool-uninstall help

# python tools (uv)
install: # Install all dependencies
	$(UV) sync

check: # Run lint + type checks
	make install
	$(UVX) ruff check --fix
	$(UV) run ty check

check-ci: # Run lint + type checks without fixing anything. For CI.
	make install
	$(UVX) ruff check
	$(UV) run ty check

test: # Run the unit test suite (skips slow, torrent and live tests)
	$(UV) run pytest

test-all: # Run every test except the live ones, slow and torrent included
	$(UV) run pytest -m "not live"

run: # Run the CLI, e.g. make run ARGS="get https://example.com/a.bin"
	$(UV) run anydm $(ARGS)

tool-install: # Install the anydm command on your PATH (editable, from this checkout)
	$(UV) tool install --editable --force .

tool-uninstall: # Remove the installed anydm command
	-$(UV) tool uninstall anydm-cli

# help
help:
	@grep -E '^[a-zA-Z_-]+:.*?#' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?#"}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
