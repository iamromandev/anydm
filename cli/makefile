## core
# variables
UV := uv
UVX := $(UV)x

# phony targets
.PHONY: install check check-ci test test-all test-docker test-docker-slow build-media build-example-plugin run tool-install tool-uninstall build build-prod docker-run down help

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

test-all: # Run every test except the live and Docker ones, slow and torrent included
	$(UV) run pytest -m "not live and not docker"

test-docker: # Run the tests that need a Docker daemon (builds the media and example plugin images first)
	$(MAKE) build-media
	$(MAKE) build-example-plugin PLATFORMS=
	$(UV) run pytest -m "docker and not slow"

test-docker-slow: # Run the slow Docker tests (interrupt and kill runs, timing)
	$(MAKE) build-media
	$(MAKE) build-example-plugin PLATFORMS=
	$(UV) run pytest -m "docker and slow"

run: # Run the CLI, e.g. make run ARGS="get https://example.com/a.bin"
	$(UV) run anydm $(ARGS)

tool-install: # Install the anydm command on your PATH (editable, from this checkout)
	$(UV) tool install --editable --force .

tool-uninstall: # Remove the installed anydm command
	-$(UV) tool uninstall anydm-cli

# docker control
DOCKER_COMPOSE := docker compose -f docker-compose.yml -p anydm
TAG ?= anydm-cli:latest

build: # Build the local Docker image
	$(DOCKER_COMPOSE) build

build-prod: # Build the prod image, e.g. make build-prod TAG=<repository>:<tag>
	docker buildx build --platform linux/arm64 --target prod \
		--build-arg ENV=prod --build-arg WORK_DIR=/workdir --build-arg INSTALL_DIR=/opt/install \
		--build-arg DOWNLOAD_DIR=/downloads -t $(TAG) --load .

# Read only when a target needs it, and "latest" when the package is not installed (matches the CLI's own default).
VERSION = $(or $(shell $(UV) run --no-sync python -c "import importlib.metadata as m; print(m.version('anydm-cli'))" 2>/dev/null),latest)
MEDIA_IMAGE ?= anydm-media:$(VERSION)

build-media: # Build the media image anydm starts for media downloads (default tag anydm-media:<version>)
	docker build --target media \
		--build-arg ENV=prod --build-arg WORK_DIR=/workdir --build-arg INSTALL_DIR=/opt/install \
		--build-arg DOWNLOAD_DIR=/downloads -t $(MEDIA_IMAGE) .

EXAMPLE_IMAGE ?= anydm-example:latest
PLATFORMS ?= linux/amd64,linux/arm64

build-example-plugin: # Build the example plugin image: both linux platforms when buildx is present (PLATFORMS= for this machine only)
	@# The multi-platform build only validates that every platform compiles (buildx cannot --load several
	@# platforms into the local daemon); the second, --load build produces the local image for this machine.
	@if [ -n "$(PLATFORMS)" ] && docker buildx version >/dev/null 2>&1; then \
		docker buildx build --platform $(PLATFORMS) -t $(EXAMPLE_IMAGE) plugins/example && \
		docker buildx build --load -t $(EXAMPLE_IMAGE) plugins/example; \
	else docker build -t $(EXAMPLE_IMAGE) plugins/example; fi

docker-run: # Run the CLI in the local image, e.g. make docker-run ARGS="get URL" (saves to ./downloads)
	$(DOCKER_COMPOSE) run --rm --service-ports cli $(ARGS)

down: # Remove the local containers
	$(DOCKER_COMPOSE) down

# help
help:
	@grep -E '^[a-zA-Z_-]+:.*?#' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?#"}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
