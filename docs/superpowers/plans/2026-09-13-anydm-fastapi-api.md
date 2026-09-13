# AnyDM FastAPI API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace anydm's Bun/Hono API with a Python FastAPI service at `api/` in the repository root, structured after `exateks/auth`, covering extract and YouTube download with server-side background downloads.

**Architecture:** FastAPI + Tortoise ORM on Postgres, single container. Download tasks are rows; an in-process `asyncio` worker pool claims them with `SELECT ... FOR UPDATE SKIP LOCKED`, streams bytes to disk with HTTP `Range` resume, post-processes with ffmpeg, and publishes progress to SSE subscribers in the same process. Every layer mirrors auth's: `Success`/`Error` envelopes, `BaseSchema`/`BaseService`/`BaseRepo`, router aggregation per package.

**Tech Stack:** Python 3.14.6, FastAPI, Tortoise ORM (asyncpg), Postgres, pytubefix, httpx, ffmpeg, sse-starlette, loguru, uv, ruff, ty, pytest.

**Spec:** [`docs/superpowers/specs/2026-09-13-anydm-fastapi-api-design.md`](../specs/2026-09-13-anydm-fastapi-api-design.md)

**Reference codebase:** `/Users/roman/projects/exateks/auth/api` — several files are copied from it verbatim. Where a task says "copy from auth", read that file and reproduce it, applying only the deletions the task names.

## Global Constraints

- **Python 3.14.6 exactly.** `.python-version` contains `3.14.6`; `pyproject.toml` sets `requires-python = "==3.14.6"`.
- **All runtime dependencies pinned with `==`.** auth pins every one; match that.
- **ruff:** `target-version = "py314"`, `line-length = 120`, double quotes, `src = ["src"]`. Config lives in `api/ruff.toml`, not `pyproject.toml`.
- **Type checker is `ty`**, not mypy. `uv run ty check` must pass.
- **Every route returns `Success[...].to_resp()`.** Failures raise `Error` (e.g. `Error.not_found(...)`); never return a bare dict or raise `HTTPException` in domain code.
- **All schema fields are snake_case.** No camelCase anywhere in a response body.
- **No network in unit tests.** `lib/youtube` sits behind a Protocol; tests inject a fake.
- **Logging is loguru.** Never `print`.
- **Tortoise models annotate the value type** (`title: str = fields.CharField(...)`) and are covered by a `[[tool.ty.overrides]]` block, exactly as auth does.
- **TDD, every task:** write the failing test, run it and see it fail, write the minimal implementation, run it and see it pass, commit.
- **Working directory for all commands is `api/`** unless a step says otherwise.
- Phases 1–6 touch only `api/`. Nothing in the running app changes until Phase 7.

## File Structure

| File | Responsibility |
|---|---|
| `api/src/main.py` | `create_app`, lifespan (worker pool, orphan recovery), CORS, error handlers, router include, `init_db` |
| `api/src/config/settings.py` | `Settings` (pydantic-settings) + `get_settings()` |
| `api/src/config/logging.py` | loguru setup |
| `api/src/core/type.py` | `Status`, `ErrorType`, `Code` |
| `api/src/core/mixin.py` | `BaseMixin` |
| `api/src/core/common.py` | `now()`, `get_app_version()`, `serialize()` |
| `api/src/core/format.py` | `utc_iso_timestamp()` |
| `api/src/core/runtime.py` | uptime + listen address for health |
| `api/src/core/constant.py` | exception → code/type maps |
| `api/src/core/success.py` | `Success[T]`, `Meta` |
| `api/src/core/error.py` | `Error`, `ErrorDetail`, `Violation`, `init_global_errors` |
| `api/src/core/base.py` | `Base` model, `CrudRepo`, `BaseRepo`, `BaseSchema`, `BaseService` |
| `api/src/data/db/__init__.py` | `DB_CONFIG`, `init_db`, `get_db_health`, `get_db_version`, `run_migration` |
| `api/src/data/db/model/download/task.py` | The `Task` model |
| `api/src/data/type/download/task.py` | `Platform`, `Preset`, `Kind`, `TaskStatus` |
| `api/src/data/repo/download/interface/task.py` | `TaskRepo` abstract contract |
| `api/src/data/repo/download/task_db.py` | `TaskDatabaseRepo` — claim, recover, progress flush |
| `api/src/data/schema/health/health.py` | `HealthSchema`, `DatabaseSchema` |
| `api/src/data/schema/extract/extract.py` | `ExtractRequest`, `ExtractSchema`, `FormatSchema` |
| `api/src/data/schema/download/download.py` | `YoutubeDownloadRequest`, `UrlDownloadRequest`, `TaskSchema` |
| `api/src/lib/youtube/url.py` | Video-ID parsing — pure |
| `api/src/lib/youtube/format.py` | Preset → itag selection — pure |
| `api/src/lib/youtube/protocol.py` | `YouTubeClient` Protocol + plain data types |
| `api/src/lib/youtube/client.py` | pytubefix adapter — the only pytubefix importer |
| `api/src/lib/media/ffmpeg.py` | ffmpeg argument construction + subprocess runner |
| `api/src/lib/event/hub.py` | SSE pub/sub with bounded queues |
| `api/src/service/health/health_service.py` | Health check |
| `api/src/service/extract/extract_service.py` | Extract orchestration |
| `api/src/service/download/download_service.py` | Enqueue, list, get, pause, resume, delete |
| `api/src/service/download/downloader.py` | Resumable byte fetch with progress callback |
| `api/src/service/download/progress.py` | EWMA speed, ETA, flush throttle — pure |
| `api/src/service/download/download_worker.py` | Claim loop, pipeline, retry, cancel registry |
| `api/src/route/{health,extract,download}/` | Routers, one package each |

---

# Phase 1 — Scaffold

Ends with `curl :8000/health/check` reporting the Postgres version.

### Task 1: Toolchain and core primitives

**Files:**
- Create: `api/pyproject.toml`, `api/ruff.toml`, `api/.python-version`, `api/.gitignore`, `api/.dockerignore`, `api/.env.example`
- Create: `api/src/__init__.py`, `api/src/core/{__init__,type,mixin,common,format,runtime}.py`
- Test: `api/tests/__init__.py`, `api/tests/conftest.py`, `api/tests/core/__init__.py`, `api/tests/core/test_runtime.py`

**Interfaces:**
- Consumes: nothing
- Produces: `src.core.type.Status` (StrEnum: `SUCCESS`, `ERROR`), `src.core.type.ErrorType` (StrEnum), `src.core.type.Code` (IntEnum of HTTP statuses); `src.core.mixin.BaseMixin` (property `_tag -> str`); `src.core.common.now() -> datetime`, `get_app_version() -> str`, `serialize(obj, instructions=None, strip=False) -> Any`; `src.core.format.utc_iso_timestamp() -> str`; `src.core.runtime.get_uptime() -> float`, `format_uptime(seconds: float) -> str`, `set_listen_addr(host, port) -> None`, `get_listen_addr() -> tuple[str | None, int | None]`

- [ ] **Step 1: Create the directory skeleton**

```bash
cd /Users/roman/projects/github/anydm
mkdir -p api/src/core api/tests/core api/scripts
touch api/src/__init__.py api/src/core/__init__.py
touch api/tests/__init__.py api/tests/core/__init__.py
echo "3.14.6" > api/.python-version
```

- [ ] **Step 2: Write `api/pyproject.toml`**

```toml
[project]
name = "anydm-api"
version = "0.0.1"
description = "AnyDM download API"
requires-python = "==3.14.6"
dependencies = [
    ### core ###
    "toml==0.10.2",
    "loguru==0.7.3",
    "fastapi[all]==0.136.1",
    ### db ###
    "tortoise-orm[asyncpg, accel]==1.1.8",
    ### media ###
    "pytubefix==11.1.0",
    "httpx==0.28.1",
    "sse-starlette==3.4.11",
]

[dependency-groups]
dev = [
    "ruff==0.16.6",
    "ty==0.0.80",
    "pytest==9.1.1",
    "pytest-asyncio==1.4.0",
]

[tool.tortoise]
tortoise_orm = "src.data.db.DB_CONFIG"

[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
asyncio_default_fixture_loop_scope = "function"
markers = [
    "integration: needs a live Postgres and disk; excluded from the default run",
]

# Tortoise's ``Field`` is a typed descriptor: ``fields.CharField(...)`` returns a
# ``CharField[str]`` while ``__get__`` yields ``str`` on instance access. The
# models annotate the value type because that documents the column, which ty
# reads as assigning a descriptor to a ``str``. Tortoise also generates the
# ``<relation>_id`` attributes at runtime.
[[tool.ty.overrides]]
include = ["src/data/db/model/**"]
rules = { invalid-assignment = "ignore", unresolved-attribute = "ignore" }

# Tortoise writes these migrations; both complaints come from its own stubs.
[[tool.ty.overrides]]
include = ["src/data/db/migration/**"]
rules = { invalid-attribute-override = "ignore", invalid-argument-type = "ignore" }
```

- [ ] **Step 3: Write `api/ruff.toml`**

Copy `/Users/roman/projects/exateks/auth/api/ruff.toml` verbatim, then reduce `[lint.per-file-ignores]` to exactly these two entries:

```toml
# FastAPI injects dependencies via default callables.
"src/route/**/*.py" = ["B008"]
# Tortoise writes these files itself. Its import order and bare `operations = [...]`
# class attributes are not ours to own, and re-styling them means every
# makemigrations run reintroduces a lint failure.
"src/data/db/migration/*.py" = ["I001", "RUF012"]
```

auth's third entry, `"src/deps/**/*.py"`, is dropped: anydm has no `src/deps/` package — its routes need no injected auth, tenant or RBAC dependencies — so the rule would match nothing.

- [ ] **Step 4: Write `api/.gitignore`, `api/.dockerignore`, `api/.env.example`**

`.gitignore`:
```
.env
.env.*
.venv
__pycache__
.pytest_cache
.ruff_cache
downloads

### allow ###
!.env.example
```

`.dockerignore`:
```
.venv
__pycache__
.pytest_cache
.ruff_cache
downloads
.env
```

`.env.example`:
```
# core
ENV=local
DEBUG=true
LOG_FORMAT=text
ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
# db
DB_SCHEMA=postgresql
DB_HOST=localhost
DB_PORT=5432
DB_USER=user
DB_PASSWORD=password
DB_NAME=anydm
# downloads
DOWNLOADS_DIR=./downloads
DOWNLOAD_WORKERS=2
DOWNLOAD_CHUNK_SIZE=1048576
PROGRESS_FLUSH_MS=1000
MAX_ATTEMPTS=3
FFMPEG_PATH=ffmpeg
```

- [ ] **Step 5: Write the failing test**

`api/tests/core/test_runtime.py`:

```python
import pytest

from src.core.runtime import format_uptime, get_listen_addr, set_listen_addr


@pytest.mark.parametrize(
    ("seconds", "expected"),
    [
        (0, "0s"),
        (5, "5s"),
        (65, "1m 5s"),
        (3600, "1h"),
        (3665, "1h 1m 5s"),
        (90061, "1d 1h 1m 1s"),
    ],
)
def test_format_uptime(seconds: float, expected: str) -> None:
    assert format_uptime(seconds) == expected


def test_listen_addr_roundtrip() -> None:
    set_listen_addr("0.0.0.0", 8000)
    assert get_listen_addr() == ("0.0.0.0", 8000)
```

`api/tests/conftest.py`:

```python
"""Shared fixtures. Nothing here touches a database: these tests cover pure functions."""
```

- [ ] **Step 6: Run the test to verify it fails**

```bash
cd api && uv sync && uv run pytest tests/core/test_runtime.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.core.runtime'`

- [ ] **Step 7: Copy the five core primitives from auth**

Reproduce these files from `/Users/roman/projects/exateks/auth/api/src/core/` unchanged:

| File | Change on copy |
|---|---|
| `runtime.py` | none |
| `mixin.py` | none |
| `format.py` | none |
| `type.py` | none — `Status`, `ErrorType` and `Code` are all domain-agnostic |
| `common.py` | **delete** `host_of`, `origin_of`, `slugify`, `blank_as`, `blank_as_none`, `as_relations`, `app_path`, and every phone-number branch of `serialize` (the `tel:` string handling and the `PhoneNumber` branch). Delete the `ulid`, `pydantic_extra_types`, `RedisDsn` and `Prefetch` imports that then go unused. Keep `now`, `get_app_version`, `serialize`. |

- [ ] **Step 8: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/core/test_runtime.py -v
```
Expected: PASS, 7 passed

- [ ] **Step 9: Lint and typecheck**

```bash
cd api && uvx ruff check --fix && uv run ty check
```
Expected: no errors

- [ ] **Step 10: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add api
git commit -m "feat(api): scaffold Python project with core primitives"
```

---

### Task 2: Settings and logging

**Files:**
- Create: `api/src/config/{__init__,settings,logging}.py`
- Test: `api/tests/config/__init__.py`, `api/tests/config/test_settings.py`

**Interfaces:**
- Consumes: `src.core.type` (nothing directly), `src.data.type.Env` is *not* used — `Env` lives in `src/config/settings.py` for now and moves in Task 9
- Produces: `src.config.get_settings() -> Settings` (lru_cached). `Settings` fields: `env: Env`, `debug: bool`, `log_format: str`, `allowed_origins: str`, `db_schema/db_host/db_port/db_name/db_user: str|int`, `db_password: SecretStr`, `downloads_dir: str`, `download_workers: int`, `download_chunk_size: int`, `progress_flush_ms: int`, `max_attempts: int`, `ffmpeg_path: str`. Properties: `is_local -> bool`, `origins -> list[str]`. Also `src.config.logging.configure_logging() -> None`.

- [ ] **Step 1: Write the failing test**

`api/tests/config/test_settings.py`:

```python
from src.config.settings import Settings


def _settings(**overrides: object) -> Settings:
    base = {
        "env": "local",
        "debug": True,
        "db_host": "localhost",
        "db_port": 5432,
        "db_name": "anydm",
        "db_user": "user",
        "db_password": "password",
    }
    base.update(overrides)
    return Settings(**base)  # ty: ignore[missing-argument]


def test_origins_splits_and_trims() -> None:
    settings = _settings(allowed_origins=" http://a.test , http://b.test ")
    assert settings.origins == ["http://a.test", "http://b.test"]


def test_origins_drops_blanks() -> None:
    assert _settings(allowed_origins="http://a.test,,  ,").origins == ["http://a.test"]


def test_origins_empty_when_unset() -> None:
    assert _settings(allowed_origins="").origins == []


def test_is_local() -> None:
    assert _settings(env="local").is_local is True
    assert _settings(env="prod").is_local is False


def test_download_defaults() -> None:
    settings = _settings()
    assert settings.download_workers == 2
    assert settings.progress_flush_ms == 1000
    assert settings.max_attempts == 3
    assert settings.ffmpeg_path == "ffmpeg"
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/config -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.config'`

- [ ] **Step 3: Write `api/src/config/settings.py`**

```python
from __future__ import annotations

from enum import StrEnum
from functools import lru_cache
from typing import Annotated

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Env(StrEnum):
    LOCAL = "local"
    DEV = "dev"
    PROD = "prod"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")
    # core
    env: Annotated[Env, Field(description="Application environment")]
    debug: Annotated[bool, Field(description="Enable debug mode")]
    log_format: Annotated[str, Field(default="text", description="text | json")]
    allowed_origins: Annotated[
        str,
        Field(default="", description="Comma-separated CORS origins allowed to call this API"),
    ]
    # db
    db_schema: Annotated[str, Field(default="postgresql", description="Database dialect (PostgreSQL only)")]
    db_host: Annotated[str, Field(description="Database host")]
    db_port: Annotated[int, Field(description="Database port")]
    db_name: Annotated[str, Field(description="Database name")]
    db_user: Annotated[str, Field(description="Database user")]
    db_password: Annotated[SecretStr, Field(description="Database password")]
    # downloads
    downloads_dir: Annotated[str, Field(default="./downloads", description="Where completed files land")]
    download_workers: Annotated[int, Field(default=2, ge=1, description="Concurrent download workers")]
    download_chunk_size: Annotated[int, Field(default=1048576, ge=1024, description="Read chunk size in bytes")]
    progress_flush_ms: Annotated[int, Field(default=1000, ge=100, description="How often progress reaches the DB")]
    max_attempts: Annotated[int, Field(default=3, ge=1, description="Total tries per task, including the first")]
    ffmpeg_path: Annotated[str, Field(default="ffmpeg", description="ffmpeg executable")]

    @property
    def is_local(self) -> bool:
        return self.env == Env.LOCAL

    @property
    def origins(self) -> list[str]:
        """``allowed_origins`` as a list, trimmed, with blanks dropped."""
        return [origin.strip() for origin in self.allowed_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()  # ty: ignore[missing-argument]
```

- [ ] **Step 4: Write `api/src/config/logging.py` and `api/src/config/__init__.py`**

Copy `logging.py` from `/Users/roman/projects/exateks/auth/api/src/config/logging.py` verbatim.

`__init__.py`:
```python
from .settings import Env as Env
from .settings import Settings as Settings
from .settings import get_settings as get_settings
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/config -v
```
Expected: PASS, 6 passed

- [ ] **Step 6: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): settings and logging configuration"
```

---

### Task 3: Response envelopes and base classes

**Files:**
- Create: `api/src/core/{constant,success,error,base}.py`
- Test: `api/tests/core/test_success.py`, `api/tests/core/test_error.py`

**Interfaces:**
- Consumes: `src.core.type.{Status, Code, ErrorType}`, `src.core.mixin.BaseMixin`, `src.core.format.utc_iso_timestamp`
- Produces:
  - `src.core.success.Success[T]` with `.ok(data=..., message=None, meta=None)`, `.created(...)`, `.to_resp(exclude_none=True, log=False) -> Response`; `src.core.success.Meta(page, page_size, total, total_pages)`
  - `src.core.error.Error(Exception)` with classmethods `bad_request`, `not_found`, `forbidden`, `conflict`, `internal`, `request_timeout`, `unauthorized`, `create(...)`, and attributes `code: Code`, `type: ErrorType | None`, `message: str | None`, `retry_able: bool`; plus `init_global_errors(app) -> None` and `error_api_responses() -> dict`
  - `src.core.base.Base` (Tortoise abstract model: `id`, `created_at`, `updated_at`, `deleted_at`, `soft_delete()`, `get_active()`), `CrudRepo[M]`, `BaseRepo[M]`, `BaseSchema`, `BaseService`

- [ ] **Step 1: Write the failing test**

`api/tests/core/test_success.py`:

```python
import json

from src.core.success import Meta, Success
from src.core.type import Code, Status


def test_ok_wraps_data() -> None:
    resp = Success.ok(data={"a": 1}).to_resp()
    body = json.loads(bytes(resp.body))
    assert resp.status_code == 200
    assert body["status"] == Status.SUCCESS
    assert body["code"] == Code.OK
    assert body["data"] == {"a": 1}
    assert body["timestamp"].endswith("Z")


def test_no_content_has_empty_body() -> None:
    resp = Success(code=Code.NO_CONTENT).to_resp()
    assert resp.status_code == 204
    assert resp.body == b""


def test_meta_is_carried() -> None:
    resp = Success.ok(data=[], meta=Meta(page=2, page_size=10, total=25, total_pages=3)).to_resp()
    body = json.loads(bytes(resp.body))
    assert body["meta"]["page"] == 2
    assert body["meta"]["total_pages"] == 3
```

`api/tests/core/test_error.py`:

```python
from src.core.error import Error
from src.core.type import Code, ErrorType


def test_not_found_carries_code_and_type() -> None:
    err = Error.not_found(message="Task not found")
    assert err.code == Code.NOT_FOUND
    assert err.message == "Task not found"
    assert err.retry_able is False


def test_retry_able_is_settable() -> None:
    err = Error.create(
        code=Code.BAD_GATEWAY,
        message="upstream broke",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )
    assert err.retry_able is True
    assert err.type == ErrorType.EXTERNAL_API_ERROR


def test_to_resp_uses_the_code_as_status() -> None:
    assert Error.not_found().to_resp().status_code == 404
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd api && uv run pytest tests/core/test_success.py tests/core/test_error.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.core.success'`

- [ ] **Step 3: Copy `constant.py`, `success.py` and `error.py` from auth**

Reproduce `/Users/roman/projects/exateks/auth/api/src/core/{constant,success,error}.py` verbatim. All three are domain-agnostic — no deletions. If `Error.create` does not exist with that exact signature in auth's file, add it alongside the other classmethods:

```python
@classmethod
def create(
    cls,
    code: Code = Code.INTERNAL_SERVER_ERROR,
    message: str | None = None,
    error_type: ErrorType | None = None,
    details: list[ErrorDetail] | None = None,
    retry_able: bool = False,
) -> Error:
    return cls(code=code, message=message, error_type=error_type, details=details, retry_able=retry_able)
```

- [ ] **Step 4: Copy `base.py` from auth with deletions**

Reproduce `/Users/roman/projects/exateks/auth/api/src/core/base.py`, then delete:

- `PartialUniqueIndex`, `NullsNotDistinctUniqueIndex`, `_declares_unique_index`, `install_unique_index_support` and its module-level call — anydm has no partial unique indexes
- `LinkBase` — there are no association tables
- the `as_relations` import and every `prefetch_related` branch that calls it; replace those with the same `isinstance(x, str)` normalisation used for `select_related`

Keep `Base`, `CrudRepo`, `BaseRepo`, `BaseSchema`, `BaseService` intact.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests/core -v
```
Expected: PASS, 13 passed

- [ ] **Step 6: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): Success/Error envelopes and base classes"
```

---

### Task 4: Database wiring, health endpoint, app entrypoint, Docker

**Files:**
- Create: `api/src/data/__init__.py`, `api/src/data/db/__init__.py`, `api/src/data/db/model/__init__.py`, `api/src/data/db/migration/__init__.py`
- Create: `api/src/data/schema/{__init__,health/__init__,health/health}.py`
- Create: `api/src/service/{__init__,health/__init__,health/health_service}.py`
- Create: `api/src/route/{__init__,health/__init__,health/health}.py`
- Create: `api/src/main.py`, `api/scripts/{__init__,migrate}.py`
- Create: `api/dockerfile`, `api/docker-compose.yml`, `api/makefile`
- Test: `api/tests/service/__init__.py`, `api/tests/service/health/__init__.py`, `api/tests/service/health/test_health_service.py`

**Interfaces:**
- Consumes: `src.config.get_settings`, `src.core.{base,success,error,runtime,common,type}`
- Produces: `src.data.db.DB_CONFIG`, `init_db(app)`, `get_db_health() -> bool`, `get_db_version() -> str | None`, `run_migration()`; `src.data.schema.health.HealthSchema`; `src.service.health.HealthService.check_health(host, port) -> HealthSchema` and `get_health_service()`; `src.route.router` (the aggregate `APIRouter`); `src.main.create_app() -> FastAPI` and module-level `app`

- [ ] **Step 1: Write the failing test**

`api/tests/service/health/test_health_service.py`:

```python
import pytest

from src.core.type import Status
from src.service.health.health_service import HealthService


@pytest.mark.asyncio
async def test_check_health_reports_the_request_address(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _true)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _version)

    health = await HealthService().check_health(host="127.0.0.1", port=9999)

    assert health.host == "127.0.0.1"
    assert health.port == 9999
    assert health.db is not None
    assert health.db.status == Status.SUCCESS
    assert health.db.version == "PostgreSQL 17"


@pytest.mark.asyncio
async def test_check_health_marks_db_error_when_unreachable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _false)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _none)

    health = await HealthService().check_health(host="127.0.0.1", port=9999)

    assert health.db is not None
    assert health.db.status == Status.ERROR


async def _true() -> bool:
    return True


async def _false() -> bool:
    return False


async def _version() -> str:
    return "PostgreSQL 17"


async def _none() -> None:
    return None
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service'`

- [ ] **Step 3: Write the database module**

`api/src/data/db/__init__.py` — copy `/Users/roman/projects/exateks/auth/api/src/data/db/__init__.py` verbatim, then delete the commented-out `async_main` block at the bottom. Everything else (`DB_CONFIG`, `init`, `close`, `get_db_health`, `get_db_version`, `init_db`, `run_migration`) is already generic.

`api/src/data/db/model/__init__.py`: empty for now — Task 10 fills it.
`api/src/data/db/migration/__init__.py`: empty.

- [ ] **Step 4: Write the health slice**

`api/src/data/schema/health/health.py` — copy from auth, then **delete `CacheSchema`** and the `cache` field on `HealthSchema` (anydm has no cache).

`api/src/data/schema/health/__init__.py`:
```python
from .health import DatabaseSchema as DatabaseSchema
from .health import HealthSchema as HealthSchema
```

`api/src/service/health/health_service.py` — copy from auth, then delete the commented-out cache block.

`api/src/service/health/__init__.py`:
```python
from .health_service import HealthService as HealthService
```

`api/src/service/__init__.py`:
```python
from src.service.health import HealthService as HealthService


def get_health_service() -> HealthService:
    return HealthService()
```

`api/src/route/health/health.py` — copy from auth verbatim.

`api/src/route/health/__init__.py`:
```python
from fastapi import APIRouter

from .health import router as _health_router

_subrouters = [
    _health_router,
]

router = APIRouter(prefix="/health", tags=["Health"])

for subrouter in _subrouters:
    router.include_router(subrouter)
```

`api/src/route/__init__.py`:
```python
from fastapi import APIRouter

from .health import router as _health_router

_subrouters = [
    _health_router,
]

router = APIRouter()

for subrouter in _subrouters:
    router.include_router(subrouter)
```

- [ ] **Step 5: Write `api/src/main.py`**

```python
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.config import get_settings
from src.config.logging import configure_logging
from src.core.common import get_app_version
from src.core.error import init_global_errors
from src.data.db import init_db
from src.route import router as _router


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """App-specific startup and shutdown.

    Tortoise's own startup runs first — ``register_tortoise`` merges it ahead of
    this one — so the database is reachable here. Task 17 hangs orphan recovery
    and the worker pool off this function.
    """
    yield


def create_app() -> FastAPI:
    configure_logging()
    settings = get_settings()
    app = FastAPI(
        title="AnyDM API",
        description="Extract and download media from URLs.",
        version=get_app_version(),
        debug=settings.debug,
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    init_global_errors(app)

    _routers = [
        _router
    ]
    for router in _routers:
        app.include_router(router)

    # Must run while building the app: ``register_tortoise`` merges its
    # init/shutdown into ``lifespan``, so calling it from inside is too late.
    init_db(app)

    return app


app = create_app()


def run() -> None:
    uvicorn.run(
        "src.main:app",
        host="0.0.0.0",
        port=8000,
        reload=False,
        loop="uvloop",
    )
```

`api/scripts/migrate.py` — copy from auth verbatim. `api/scripts/__init__.py` — empty.

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd api && uv run pytest tests -v
```
Expected: PASS, 15 passed

- [ ] **Step 7: Write `api/dockerfile`**

Copy `/Users/roman/projects/exateks/auth/api/dockerfile`, changing `ARG PYTHON_VERSION=3.14.6` to stay as-is and adding ffmpeg to the `base` stage immediately after the `ENV PATH=` line:

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 8: Write `api/docker-compose.yml`**

Copy auth's, renaming every `exa-auth-api` to `anydm-api`, changing the published Postgres port from `5400:5432` to `5401:5432`, dropping the `POSTGRES_DB: ${DB_NAME}` default to `anydm`, and adding a downloads mount plus the compose project name:

```yaml
  server:
    # ...as auth, then:
    volumes:
      - .:/workdir
      - downloads:/workdir/downloads
```
and under the top-level `volumes:` key:
```yaml
  downloads:
    name: downloads-anydm-api
```

- [ ] **Step 9: Write `api/makefile`**

Copy auth's makefile and delete every seed target (`seed\:default`, `seed\:service`, `seed\:user`, `setup`, `SEED_*` variables and the `SEED_GUARD` block) — anydm seeds nothing. Change `DOCKER_COMPOSE := docker compose -f docker-compose.yml -p anydm` and `SERVER_CONTAINER := server-anydm-api`. Keep `clean-system`, `clean-db`, `clean`, `ps`, `build`, `up`, `stop`, `down`, `restart`, `logs`, `install`, `install-dev`, `check`, `test`, `run`, `export`, `add`, `migrate`, `help`. Add:

```makefile
test-all: # Run every test, integration included
	$(UV) run pytest -m ""
```

- [ ] **Step 10: Verify the stack answers**

```bash
cd api && cp .env.example .env && make up
sleep 5
curl -s localhost:8000/health/check | python3 -m json.tool
```
Expected: `status: "success"`, `data.db.status: "success"`, `data.db.version` naming PostgreSQL, `data.environment: "local"`

- [ ] **Step 11: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): database wiring, health endpoint and Docker stack"
```

---

# Phase 2 — Extract

Ends with `POST /extract` returning real metadata for a YouTube URL.

### Task 5: YouTube URL parsing

The highest-value test in the project. This is a faithful port of `extractYouTubeVideoId` in `apps/api/src/service/youtube.ts:52`. Pure, no network, no pytubefix.

**Files:**
- Create: `api/src/lib/__init__.py`, `api/src/lib/youtube/__init__.py`, `api/src/lib/youtube/url.py`
- Test: `api/tests/lib/__init__.py`, `api/tests/lib/youtube/__init__.py`, `api/tests/lib/youtube/test_url.py`

**Interfaces:**
- Consumes: nothing
- Produces: `src.lib.youtube.url.extract_video_id(url: str) -> str | None`, `src.lib.youtube.url.is_youtube_url(url: str) -> bool`

- [ ] **Step 1: Write the failing test**

`api/tests/lib/youtube/test_url.py`:

```python
import pytest

from src.lib.youtube.url import extract_video_id, is_youtube_url

VIDEO_ID = "dQw4w9WgXcQ"


@pytest.mark.parametrize(
    "url",
    [
        f"https://www.youtube.com/watch?v={VIDEO_ID}",
        f"https://youtube.com/watch?v={VIDEO_ID}",
        f"https://m.youtube.com/watch?v={VIDEO_ID}",
        f"https://music.youtube.com/watch?v={VIDEO_ID}",
        f"https://www.youtube.com/watch?v={VIDEO_ID}&t=42s",
        f"https://youtu.be/{VIDEO_ID}",
        f"https://youtu.be/{VIDEO_ID}?t=42",
        f"https://www.youtube.com/embed/{VIDEO_ID}",
        f"https://www.youtube.com/shorts/{VIDEO_ID}",
        f"  https://www.youtube.com/watch?v={VIDEO_ID}  ",
    ],
)
def test_extracts_the_video_id(url: str) -> None:
    assert extract_video_id(url) == VIDEO_ID


@pytest.mark.parametrize(
    "url",
    [
        "",
        "   ",
        "not a url",
        "https://example.com/watch?v=abc",
        "https://www.youtube.com/",
        "https://www.youtube.com/watch",
        "https://www.youtube.com/feed/subscriptions",
        "https://vimeo.com/123456",
        "ftp://youtube.com/watch?v=abc",
    ],
)
def test_returns_none_for_anything_else(url: str) -> None:
    assert extract_video_id(url) is None


def test_is_youtube_url_mirrors_extraction() -> None:
    assert is_youtube_url(f"https://youtu.be/{VIDEO_ID}") is True
    assert is_youtube_url("https://example.com") is False
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/youtube/test_url.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.lib'`

- [ ] **Step 3: Write `api/src/lib/youtube/url.py`**

```python
"""YouTube URL parsing. Pure — no network, no pytubefix."""

from __future__ import annotations

from urllib.parse import parse_qs, urlparse

_WATCH_HOSTS = frozenset({"youtube.com", "music.youtube.com"})
_PATH_PREFIXES = ("/embed/", "/shorts/")
_ALLOWED_SCHEMES = frozenset({"http", "https"})


def _first_segment(path: str) -> str:
    return path.lstrip("/").split("/", 1)[0]


def extract_video_id(url: str) -> str | None:
    """The video id in ``url``, or ``None`` if it is not a YouTube video link.

    Handles the five shapes the UI can produce: ``/watch?v=``, ``youtu.be/``,
    ``/embed/``, ``/shorts/``, and the ``music.`` host. ``www.`` and ``m.``
    prefixes are folded away first, so each form is written once.
    """
    parsed = urlparse((url or "").strip())
    if parsed.scheme not in _ALLOWED_SCHEMES:
        return None

    host = (parsed.hostname or "").removeprefix("www.").removeprefix("m.")

    if host == "youtu.be":
        return _first_segment(parsed.path) or None

    if host in _WATCH_HOSTS:
        if parsed.path == "/watch":
            values = parse_qs(parsed.query).get("v") or []
            return values[0] if values else None
        for prefix in _PATH_PREFIXES:
            if parsed.path.startswith(prefix):
                return _first_segment(parsed.path[len(prefix):]) or None

    return None


def is_youtube_url(url: str) -> bool:
    return extract_video_id(url) is not None
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/youtube/test_url.py -v
```
Expected: PASS, 20 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): YouTube URL parsing"
```

---

### Task 6: YouTube client protocol and error mapping

Defines the boundary everything else codes against, so no later task needs pytubefix to be testable.

**Files:**
- Create: `api/src/lib/youtube/protocol.py`, `api/src/lib/youtube/error.py`
- Test: `api/tests/lib/youtube/test_error.py`

**Interfaces:**
- Consumes: `src.core.error.Error`, `src.core.type.{Code, ErrorType}`
- Produces:
  - `src.lib.youtube.protocol.StreamInfo` — frozen dataclass: `itag: int`, `mime_type: str | None`, `quality: str | None`, `height: int | None`, `bitrate: int | None`, `has_video: bool`, `has_audio: bool`, `content_length: int | None`
  - `src.lib.youtube.protocol.VideoInfo` — frozen dataclass: `video_id: str`, `title: str`, `author: str`, `channel_id: str`, `description: str`, `length_seconds: int`, `view_count: int`, `upload_date: str`, `is_live: bool`, `thumbnails: list[Thumbnail]`, `streams: list[StreamInfo]`
  - `src.lib.youtube.protocol.Thumbnail` — frozen dataclass: `url: str`, `width: int`, `height: int`
  - `src.lib.youtube.protocol.YouTubeClient` — Protocol with `async def fetch_info(self, video_id: str) -> VideoInfo` and `async def stream_url(self, video_id: str, itag: int) -> str`
  - `src.lib.youtube.error.not_a_youtube_url()`, `video_unavailable(reason)`, `video_forbidden(reason)`, `no_format_for_preset(preset)`, `extraction_failed(reason)` — each returns an `Error`

- [ ] **Step 1: Write the failing test**

`api/tests/lib/youtube/test_error.py`:

```python
from src.core.type import Code, ErrorType
from src.lib.youtube import error as yt_error


def test_not_a_youtube_url_is_a_permanent_400() -> None:
    err = yt_error.not_a_youtube_url()
    assert err.code == Code.BAD_REQUEST
    assert err.type == ErrorType.UNSUPPORTED_OPERATION
    assert err.retry_able is False


def test_video_unavailable_is_a_permanent_404() -> None:
    err = yt_error.video_unavailable("private video")
    assert err.code == Code.NOT_FOUND
    assert err.type == ErrorType.DOES_NOT_EXIST
    assert err.retry_able is False
    assert err.message is not None
    assert "private video" in err.message


def test_video_forbidden_is_a_permanent_403() -> None:
    err = yt_error.video_forbidden("age restricted")
    assert err.code == Code.FORBIDDEN
    assert err.retry_able is False


def test_no_format_for_preset_is_a_permanent_422() -> None:
    err = yt_error.no_format_for_preset("2160")
    assert err.code == Code.UNPROCESSABLE_ENTITY
    assert err.retry_able is False
    assert err.message is not None
    assert "2160" in err.message


def test_extraction_failed_is_a_retryable_502() -> None:
    err = yt_error.extraction_failed("player script changed")
    assert err.code == Code.BAD_GATEWAY
    assert err.type == ErrorType.EXTERNAL_API_ERROR
    assert err.retry_able is True
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/youtube/test_error.py -v
```
Expected: FAIL — `ImportError: cannot import name 'error' from 'src.lib.youtube'`

- [ ] **Step 3: Write `api/src/lib/youtube/error.py`**

```python
"""YouTube failures as this project's ``Error``.

Every one carries ``retry_able``, which is the only thing the worker consults
when deciding between a retry and a dead task — see
``service/download/download_worker.py``.
"""

from __future__ import annotations

from src.core.error import Error
from src.core.type import Code, ErrorType


def not_a_youtube_url() -> Error:
    return Error.create(
        code=Code.BAD_REQUEST,
        message="Not a YouTube URL",
        error_type=ErrorType.UNSUPPORTED_OPERATION,
    )


def video_unavailable(reason: str) -> Error:
    return Error.create(
        code=Code.NOT_FOUND,
        message=f"Video unavailable: {reason}",
        error_type=ErrorType.DOES_NOT_EXIST,
    )


def video_forbidden(reason: str) -> Error:
    return Error.create(
        code=Code.FORBIDDEN,
        message=f"Video not accessible: {reason}",
        error_type=ErrorType.FORBIDDEN,
    )


def no_format_for_preset(preset: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=f'No stream available for preset "{preset}"',
        error_type=ErrorType.UNPROCESSABLE_ENTITY,
    )


def extraction_failed(reason: str) -> Error:
    """Retryable: this is what a YouTube-side change looks like from here."""
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"YouTube extraction failed: {reason}",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )
```

- [ ] **Step 4: Write `api/src/lib/youtube/protocol.py`**

```python
"""The boundary between this project and whatever library talks to YouTube.

Nothing outside ``client.py`` imports pytubefix. Everything else codes against
these dataclasses and this Protocol, which is also what lets the tests run
without a network.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True, slots=True)
class Thumbnail:
    url: str
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class StreamInfo:
    itag: int
    mime_type: str | None = None
    quality: str | None = None
    height: int | None = None
    bitrate: int | None = None
    has_video: bool = False
    has_audio: bool = False
    content_length: int | None = None


@dataclass(frozen=True, slots=True)
class VideoInfo:
    video_id: str
    title: str
    author: str = ""
    channel_id: str = ""
    description: str = ""
    length_seconds: int = 0
    view_count: int = 0
    upload_date: str = ""
    is_live: bool = False
    thumbnails: list[Thumbnail] = field(default_factory=list)
    streams: list[StreamInfo] = field(default_factory=list)


class YouTubeClient(Protocol):
    async def fetch_info(self, video_id: str) -> VideoInfo:
        """Metadata and the full stream list for ``video_id``."""
        ...

    async def stream_url(self, video_id: str, itag: int) -> str:
        """A fresh, playable URL for one stream.

        Always re-resolved rather than cached: these expire within hours and
        bind to the requesting IP.
        """
        ...
```

Update `api/src/lib/youtube/__init__.py`:

```python
from .error import extraction_failed as extraction_failed
from .error import no_format_for_preset as no_format_for_preset
from .error import not_a_youtube_url as not_a_youtube_url
from .error import video_forbidden as video_forbidden
from .error import video_unavailable as video_unavailable
from .protocol import StreamInfo as StreamInfo
from .protocol import Thumbnail as Thumbnail
from .protocol import VideoInfo as VideoInfo
from .protocol import YouTubeClient as YouTubeClient
from .url import extract_video_id as extract_video_id
from .url import is_youtube_url as is_youtube_url
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/youtube -v
```
Expected: PASS, 25 passed

- [ ] **Step 6: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): YouTube client protocol and error mapping"
```

---

### Task 7: pytubefix adapter

The only module that imports pytubefix. When YouTube breaks it, this file is the blast radius.

**Files:**
- Create: `api/src/lib/youtube/client.py`
- Test: `api/tests/lib/youtube/test_client.py`

**Interfaces:**
- Consumes: `src.lib.youtube.protocol.{VideoInfo, StreamInfo, Thumbnail, YouTubeClient}`, `src.lib.youtube.error.*`
- Produces: `src.lib.youtube.client.PytubefixClient` (implements `YouTubeClient`), `src.lib.youtube.client.get_youtube_client() -> YouTubeClient` (lru_cached)

- [ ] **Step 1: Write the failing test**

Only the pure translation layer is tested — `_to_stream_info` and `_classify`. The network call itself is not unit-tested; Phase 2's manual checkpoint covers it.

`api/tests/lib/youtube/test_client.py`:

```python
from types import SimpleNamespace

import pytest

from src.core.type import Code
from src.lib.youtube.client import _classify, _to_stream_info


def _raw(**overrides: object) -> SimpleNamespace:
    base = {
        "itag": 137,
        "mime_type": "video/mp4",
        "resolution": "1080p",
        "bitrate": 2_500_000,
        "includes_video_track": True,
        "includes_audio_track": False,
        "filesize": 104_857_600,
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def test_to_stream_info_maps_every_field() -> None:
    info = _to_stream_info(_raw())
    assert info.itag == 137
    assert info.mime_type == "video/mp4"
    assert info.quality == "1080p"
    assert info.height == 1080
    assert info.bitrate == 2_500_000
    assert info.has_video is True
    assert info.has_audio is False
    assert info.content_length == 104_857_600


def test_to_stream_info_handles_audio_only() -> None:
    info = _to_stream_info(
        _raw(itag=140, mime_type="audio/mp4", resolution=None, includes_video_track=False, includes_audio_track=True)
    )
    assert info.height is None
    assert info.has_audio is True
    assert info.has_video is False


def test_to_stream_info_survives_missing_attributes() -> None:
    info = _to_stream_info(SimpleNamespace(itag=18))
    assert info.itag == 18
    assert info.mime_type is None
    assert info.height is None
    assert info.content_length is None


@pytest.mark.parametrize(
    ("message", "expected_code"),
    [
        ("Video is private", Code.NOT_FOUND),
        ("This video is unavailable", Code.NOT_FOUND),
        ("Video is age restricted", Code.FORBIDDEN),
        ("members-only content", Code.FORBIDDEN),
        ("something nobody predicted", Code.BAD_GATEWAY),
    ],
)
def test_classify_maps_library_failures(message: str, expected_code: Code) -> None:
    assert _classify(RuntimeError(message)).code == expected_code


def test_only_unknown_failures_are_retryable() -> None:
    assert _classify(RuntimeError("Video is private")).retry_able is False
    assert _classify(RuntimeError("who knows")).retry_able is True
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/youtube/test_client.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.lib.youtube.client'`

- [ ] **Step 3: Write `api/src/lib/youtube/client.py`**

```python
"""pytubefix adapter. The only module in this project that imports pytubefix.

Two things earn the wrapper. pytubefix is synchronous and does blocking HTTP, so
every call goes through ``asyncio.to_thread``. And its failures arrive as loosely
typed exceptions with human-readable messages, which ``_classify`` turns into
this project's ``Error`` — including the ``retry_able`` flag the worker reads.
"""

from __future__ import annotations

import asyncio
import re
from functools import lru_cache
from typing import Any

from loguru import logger
from pytubefix import YouTube

from src.core.error import Error
from src.lib.youtube import error as yt_error
from src.lib.youtube.protocol import StreamInfo, Thumbnail, VideoInfo, YouTubeClient

_WATCH_URL = "https://www.youtube.com/watch?v={video_id}"
_HEIGHT = re.compile(r"(\d{3,4})p")

_MISSING_MARKERS = ("private", "unavailable", "removed", "deleted", "does not exist")
_FORBIDDEN_MARKERS = ("age restricted", "age-restricted", "members-only", "members only", "login required")


def _height_of(resolution: str | None) -> int | None:
    if not resolution:
        return None
    match = _HEIGHT.search(resolution)
    return int(match.group(1)) if match else None


def _to_stream_info(raw: Any) -> StreamInfo:
    """One pytubefix ``Stream`` as a plain ``StreamInfo``.

    Every attribute is read with ``getattr`` and a default: pytubefix omits
    fields per stream type, and a missing one should narrow the choices rather
    than raise.
    """
    resolution = getattr(raw, "resolution", None)
    return StreamInfo(
        itag=int(getattr(raw, "itag", 0)),
        mime_type=getattr(raw, "mime_type", None),
        quality=resolution,
        height=_height_of(resolution),
        bitrate=getattr(raw, "bitrate", None),
        has_video=bool(getattr(raw, "includes_video_track", False)),
        has_audio=bool(getattr(raw, "includes_audio_track", False)),
        content_length=getattr(raw, "filesize", None),
    )


def _classify(exc: Exception) -> Error:
    """A pytubefix exception as an ``Error``, with the retry decision attached.

    Matched on the message rather than the exception class on purpose:
    pytubefix raises several near-identical types and renames them between
    releases, while the wording of these three cases has been stable.
    """
    text = str(exc).lower()
    if any(marker in text for marker in _MISSING_MARKERS):
        return yt_error.video_unavailable(str(exc))
    if any(marker in text for marker in _FORBIDDEN_MARKERS):
        return yt_error.video_forbidden(str(exc))
    return yt_error.extraction_failed(str(exc))


class PytubefixClient(YouTubeClient):
    async def fetch_info(self, video_id: str) -> VideoInfo:
        return await asyncio.to_thread(self._fetch_info_blocking, video_id)

    async def stream_url(self, video_id: str, itag: int) -> str:
        return await asyncio.to_thread(self._stream_url_blocking, video_id, itag)

    def _youtube(self, video_id: str) -> YouTube:
        return YouTube(_WATCH_URL.format(video_id=video_id))

    def _fetch_info_blocking(self, video_id: str) -> VideoInfo:
        try:
            yt = self._youtube(video_id)
            thumbnails = [Thumbnail(url=yt.thumbnail_url, width=0, height=0)] if yt.thumbnail_url else []
            return VideoInfo(
                video_id=video_id,
                title=yt.title or "",
                author=yt.author or "",
                channel_id=yt.channel_id or "",
                description=yt.description or "",
                length_seconds=int(yt.length or 0),
                view_count=int(yt.views or 0),
                upload_date=yt.publish_date.date().isoformat() if yt.publish_date else "",
                is_live=bool(getattr(yt, "live_streaming", False)),
                thumbnails=thumbnails,
                streams=[_to_stream_info(stream) for stream in yt.streams],
            )
        except Exception as exc:
            logger.error("PytubefixClient|fetch_info({}): {}", video_id, exc)
            raise _classify(exc) from exc

    def _stream_url_blocking(self, video_id: str, itag: int) -> str:
        try:
            stream = self._youtube(video_id).streams.get_by_itag(itag)
        except Exception as exc:
            logger.error("PytubefixClient|stream_url({}, {}): {}", video_id, itag, exc)
            raise _classify(exc) from exc
        if stream is None or not stream.url:
            raise yt_error.no_format_for_preset(str(itag))
        return str(stream.url)


@lru_cache
def get_youtube_client() -> YouTubeClient:
    return PytubefixClient()
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/youtube/test_client.py -v
```
Expected: PASS, 9 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): pytubefix adapter behind the YouTubeClient protocol"
```

---

### Task 8: Extract endpoint

**Files:**
- Create: `api/src/data/schema/extract/{__init__,extract}.py`
- Create: `api/src/service/extract/{__init__,extract_service}.py`
- Create: `api/src/route/extract/{__init__,extract}.py`
- Modify: `api/src/route/__init__.py`, `api/src/service/__init__.py`
- Test: `api/tests/service/extract/__init__.py`, `api/tests/service/extract/test_extract_service.py`

**Interfaces:**
- Consumes: `src.lib.youtube.{extract_video_id, YouTubeClient, VideoInfo, StreamInfo, not_a_youtube_url}`, `src.core.base.{BaseSchema, BaseService}`, `src.core.success.Success`
- Produces:
  - `src.data.schema.extract.ExtractRequest` — `url: str`
  - `src.data.schema.extract.FormatSchema` — `itag`, `quality`, `container`, `has_video`, `has_audio`, `content_length`, `mime_type`
  - `src.data.schema.extract.ExtractSchema` — `platform`, `video_id`, `title`, `author`, `channel_id`, `description`, `length_seconds`, `view_count`, `upload_date`, `is_live`, `thumbnail`, `thumbnails`, `formats`
  - `src.service.extract.ExtractService(client: YouTubeClient)` with `async def extract(url: str) -> ExtractSchema`
  - `src.service.get_extract_service() -> ExtractService`

- [ ] **Step 1: Write the failing test**

`api/tests/service/extract/test_extract_service.py`:

```python
import pytest

from src.core.error import Error
from src.core.type import Code
from src.lib.youtube.protocol import StreamInfo, Thumbnail, VideoInfo
from src.service.extract.extract_service import ExtractService

VIDEO_ID = "dQw4w9WgXcQ"


class FakeClient:
    def __init__(self, info: VideoInfo) -> None:
        self._info = info
        self.calls: list[str] = []

    async def fetch_info(self, video_id: str) -> VideoInfo:
        self.calls.append(video_id)
        return self._info

    async def stream_url(self, video_id: str, itag: int) -> str:
        raise AssertionError("extract must not resolve stream URLs")


def _info() -> VideoInfo:
    return VideoInfo(
        video_id=VIDEO_ID,
        title="Never Gonna Give You Up",
        author="Rick Astley",
        length_seconds=213,
        thumbnails=[Thumbnail(url="https://i.ytimg.com/small.jpg", width=120, height=90),
                    Thumbnail(url="https://i.ytimg.com/large.jpg", width=1280, height=720)],
        streams=[
            StreamInfo(itag=18, mime_type="video/mp4", quality="360p", height=360,
                       has_video=True, has_audio=True, content_length=1000),
            StreamInfo(itag=140, mime_type="audio/mp4", bitrate=128000, has_audio=True),
        ],
    )


@pytest.mark.asyncio
async def test_extract_maps_metadata_and_formats() -> None:
    client = FakeClient(_info())
    result = await ExtractService(client).extract(f"https://youtu.be/{VIDEO_ID}")

    assert client.calls == [VIDEO_ID]
    assert result.platform == "youtube"
    assert result.video_id == VIDEO_ID
    assert result.title == "Never Gonna Give You Up"
    assert result.length_seconds == 213
    assert len(result.formats) == 2
    assert result.formats[0].itag == 18
    assert result.formats[0].container == "mp4"
    assert result.formats[1].container == "mp4"


@pytest.mark.asyncio
async def test_extract_uses_the_largest_thumbnail() -> None:
    result = await ExtractService(FakeClient(_info())).extract(f"https://youtu.be/{VIDEO_ID}")
    assert result.thumbnail == "https://i.ytimg.com/large.jpg"


@pytest.mark.asyncio
async def test_extract_rejects_a_non_youtube_url() -> None:
    with pytest.raises(Error) as caught:
        await ExtractService(FakeClient(_info())).extract("https://example.com/video")
    assert caught.value.code == Code.BAD_REQUEST


@pytest.mark.asyncio
async def test_extract_handles_a_video_with_no_thumbnails() -> None:
    info = VideoInfo(video_id=VIDEO_ID, title="t", thumbnails=[], streams=[])
    result = await ExtractService(FakeClient(info)).extract(f"https://youtu.be/{VIDEO_ID}")
    assert result.thumbnail == ""
    assert result.formats == []
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/extract -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service.extract'`

- [ ] **Step 3: Write `api/src/data/schema/extract/extract.py`**

```python
from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema


class ExtractRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="The URL to inspect")]


class ThumbnailSchema(BaseSchema):
    url: str
    width: int = 0
    height: int = 0


class FormatSchema(BaseSchema):
    itag: int
    quality: Annotated[str, Field(default="unknown")]
    container: Annotated[str, Field(default="unknown")]
    has_video: bool = False
    has_audio: bool = False
    content_length: int | None = None
    mime_type: str | None = None


class ExtractSchema(BaseSchema):
    platform: Annotated[str, Field(default="youtube")]
    video_id: str
    title: str = ""
    author: str = ""
    channel_id: str = ""
    description: str = ""
    length_seconds: int = 0
    view_count: int = 0
    upload_date: str = ""
    is_live: bool = False
    thumbnail: str = ""
    thumbnails: Annotated[list[ThumbnailSchema], Field(default_factory=list)]
    formats: Annotated[list[FormatSchema], Field(default_factory=list)]
```

`api/src/data/schema/extract/__init__.py`:
```python
from .extract import ExtractRequest as ExtractRequest
from .extract import ExtractSchema as ExtractSchema
from .extract import FormatSchema as FormatSchema
from .extract import ThumbnailSchema as ThumbnailSchema
```

- [ ] **Step 4: Write `api/src/service/extract/extract_service.py`**

```python
from __future__ import annotations

from src.core.base import BaseService
from src.data.schema.extract import ExtractSchema, FormatSchema, ThumbnailSchema
from src.lib.youtube import StreamInfo, VideoInfo, YouTubeClient, extract_video_id, not_a_youtube_url


def _container_of(mime_type: str | None) -> str:
    """``"video/mp4; codecs=..."`` becomes ``"mp4"``."""
    if not mime_type:
        return "unknown"
    subtype = mime_type.split(";")[0].split("/")
    return subtype[1] if len(subtype) > 1 and subtype[1] else "unknown"


def _to_format(stream: StreamInfo) -> FormatSchema:
    return FormatSchema(
        itag=stream.itag,
        quality=stream.quality or "unknown",
        container=_container_of(stream.mime_type),
        has_video=stream.has_video,
        has_audio=stream.has_audio,
        content_length=stream.content_length,
        mime_type=stream.mime_type,
    )


class ExtractService(BaseService):
    def __init__(self, client: YouTubeClient) -> None:
        super().__init__()
        self._client = client

    async def extract(self, url: str) -> ExtractSchema:
        video_id = extract_video_id(url)
        if video_id is None:
            raise not_a_youtube_url()

        info: VideoInfo = await self._client.fetch_info(video_id)
        thumbnails = [ThumbnailSchema(url=t.url, width=t.width, height=t.height) for t in info.thumbnails]

        return ExtractSchema(
            platform="youtube",
            video_id=info.video_id,
            title=info.title,
            author=info.author,
            channel_id=info.channel_id,
            description=info.description,
            length_seconds=info.length_seconds,
            view_count=info.view_count,
            upload_date=info.upload_date,
            is_live=info.is_live,
            # Last wins: the client returns thumbnails smallest-first, and the
            # UI wants the biggest one it can get.
            thumbnail=thumbnails[-1].url if thumbnails else "",
            thumbnails=thumbnails,
            formats=[_to_format(stream) for stream in info.streams],
        )
```

`api/src/service/extract/__init__.py`:
```python
from .extract_service import ExtractService as ExtractService
```

Append to `api/src/service/__init__.py`:
```python
from src.lib.youtube.client import get_youtube_client
from src.service.extract import ExtractService as ExtractService


def get_extract_service() -> ExtractService:
    return ExtractService(client=get_youtube_client())
```

- [ ] **Step 5: Write the route**

`api/src/route/extract/extract.py`:

```python
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.extract import ExtractRequest, ExtractSchema
from src.service import ExtractService, get_extract_service

router = APIRouter()


@router.post(
    path="",
    response_model=Success[ExtractSchema],
)
async def extract(
    payload: ExtractRequest,
    extract_service: Annotated[ExtractService, Depends(get_extract_service)],
) -> Response:
    data: ExtractSchema = await extract_service.extract(payload.url.strip())
    return Success.ok(data=data).to_resp()
```

`api/src/route/extract/__init__.py`:
```python
from fastapi import APIRouter

from .extract import router as _extract_router

_subrouters = [
    _extract_router,
]

router = APIRouter(prefix="/extract", tags=["Extract"])

for subrouter in _subrouters:
    router.include_router(subrouter)
```

In `api/src/route/__init__.py`, add `from .extract import router as _extract_router` and append `_extract_router` to `_subrouters`.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests -v
```
Expected: PASS, all green

- [ ] **Step 7: Verify against a real video**

```bash
cd api && make restart && sleep 6
curl -s -X POST localhost:8000/extract \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}' | python3 -m json.tool | head -30
```
Expected: `status: "success"`, a real `title`, and a non-empty `formats` array

- [ ] **Step 8: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): POST /extract endpoint"
```

---

# Phase 3 — Task model and enqueue

Ends with `POST /download/youtube` writing a `pending` row. No bytes move yet.

### Task 9: Download enums

**Files:**
- Create: `api/src/data/type/__init__.py`, `api/src/data/type/download/{__init__,task}.py`
- Test: `api/tests/data/__init__.py`, `api/tests/data/type/__init__.py`, `api/tests/data/type/test_task_type.py`

**Interfaces:**
- Consumes: nothing
- Produces: `src.data.type.{Platform, Preset, Kind, TaskStatus}`; `Preset.target_height -> int | None`; `TaskStatus.is_terminal -> bool`; `ACTIVE_STATUSES: frozenset[TaskStatus]`

- [ ] **Step 1: Write the failing test**

`api/tests/data/type/test_task_type.py`:

```python
import pytest

from src.data.type import ACTIVE_STATUSES, Kind, Platform, Preset, TaskStatus


def test_preset_values_match_the_bun_api() -> None:
    assert {p.value for p in Preset} == {"best", "2160", "1440", "1080", "720", "480", "mp3"}


@pytest.mark.parametrize(
    ("preset", "height"),
    [(Preset.BEST, None), (Preset.P2160, 2160), (Preset.P1080, 1080), (Preset.P480, 480), (Preset.MP3, None)],
)
def test_target_height(preset: Preset, height: int | None) -> None:
    assert preset.target_height == height


def test_terminal_statuses() -> None:
    assert TaskStatus.COMPLETE.is_terminal is True
    assert TaskStatus.FAILED.is_terminal is True
    assert TaskStatus.CANCELED.is_terminal is True
    assert TaskStatus.DOWNLOADING.is_terminal is False
    assert TaskStatus.PAUSED.is_terminal is False


def test_active_statuses_are_the_ones_a_restart_must_requeue() -> None:
    assert ACTIVE_STATUSES == frozenset({TaskStatus.DOWNLOADING, TaskStatus.MUXING})


def test_platform_and_kind_values() -> None:
    assert {p.value for p in Platform} == {"youtube", "direct"}
    assert {k.value for k in Kind} == {"video", "audio", "file"}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/data -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.data.type'`

- [ ] **Step 3: Write `api/src/data/type/download/task.py`**

```python
"""Download domain enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``, exactly as auth's ``Env`` is.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    YOUTUBE = "youtube"
    DIRECT = "direct"


class Preset(StrEnum):
    BEST = "best"
    P2160 = "2160"
    P1440 = "1440"
    P1080 = "1080"
    P720 = "720"
    P480 = "480"
    MP3 = "mp3"

    @property
    def target_height(self) -> int | None:
        """The pixel height this preset asks for, or ``None`` when it names no height."""
        if self in (Preset.BEST, Preset.MP3):
            return None
        return int(self.value)


class Kind(StrEnum):
    VIDEO = "video"
    AUDIO = "audio"
    #: An arbitrary fetched file — what ``Platform.DIRECT`` produces. It has no
    #: notion of stream quality, so no preset applies to it.
    FILE = "file"


class TaskStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    MUXING = "muxing"
    PAUSED = "paused"
    COMPLETE = "complete"
    FAILED = "failed"
    CANCELED = "canceled"

    @property
    def is_terminal(self) -> bool:
        return self in (TaskStatus.COMPLETE, TaskStatus.FAILED, TaskStatus.CANCELED)


#: Statuses that mean "a worker was mid-flight". Every row in one of these at
#: startup is an orphan by definition — this process is the only one that runs
#: workers, and it has just started.
ACTIVE_STATUSES = frozenset({TaskStatus.DOWNLOADING, TaskStatus.MUXING})
```

`api/src/data/type/download/__init__.py`:
```python
from .task import ACTIVE_STATUSES as ACTIVE_STATUSES
from .task import Kind as Kind
from .task import Platform as Platform
from .task import Preset as Preset
from .task import TaskStatus as TaskStatus
```

`api/src/data/type/__init__.py`:
```python
from .download import ACTIVE_STATUSES as ACTIVE_STATUSES
from .download import Kind as Kind
from .download import Platform as Platform
from .download import Preset as Preset
from .download import TaskStatus as TaskStatus
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/data -v
```
Expected: PASS, 10 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): download domain enums"
```

---

### Task 10: Task model and initial migration

**Files:**
- Create: `api/src/data/db/model/download/{__init__,task}.py`
- Modify: `api/src/data/db/model/__init__.py`
- Create: `api/src/data/db/migration/0001_initial.py` (generated)

**Interfaces:**
- Consumes: `src.core.base.Base`, `src.data.type.{Platform, Preset, Kind, TaskStatus}`
- Produces: `src.data.db.model.Task` with every column from the spec's data model

- [ ] **Step 1: Write `api/src/data/db/model/download/task.py`**

```python
from __future__ import annotations

from datetime import datetime

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import Base
from src.data.type import Kind, Platform, Preset, TaskStatus


class Task(Base):
    """One download, from the request that created it to the file it produced."""

    # source
    source_url: str = fields.TextField()
    platform: Platform = fields.CharEnumField(Platform, max_length=16, db_index=True)
    video_id: str | None = fields.CharField(max_length=64, null=True, db_index=True)

    # request
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    kind: Kind = fields.CharEnumField(Kind, max_length=8)

    # resolved plan. The itags rather than a URL: stream URLs expire within
    # hours and bind to the requesting IP, so a resumed download re-resolves.
    title: str = fields.CharField(max_length=512, default="")
    filename: str = fields.CharField(max_length=512, default="")
    mime_type: str | None = fields.CharField(max_length=128, null=True)
    video_itag: int | None = fields.IntField(null=True)
    audio_itag: int | None = fields.IntField(null=True)

    # progress. Always byte-download progress: it reaches 100 when the last
    # byte lands and stays there through muxing, which ``status`` reports.
    status: TaskStatus = fields.CharEnumField(TaskStatus, max_length=16, db_index=True)
    progress: int = fields.IntField(default=0)
    downloaded_bytes: int = fields.BigIntField(default=0)
    total_bytes: int | None = fields.BigIntField(null=True)
    speed_bps: int = fields.BigIntField(default=0)
    eta_seconds: int | None = fields.IntField(null=True)

    # result
    file_path: str | None = fields.CharField(max_length=1024, null=True)
    file_size: int | None = fields.BigIntField(null=True)

    # lifecycle
    error: str | None = fields.TextField(null=True)
    error_code: str | None = fields.CharField(max_length=64, null=True)
    attempts: int = fields.IntField(default=0)
    next_attempt_at: datetime | None = fields.DatetimeField(null=True)
    started_at: datetime | None = fields.DatetimeField(null=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)
    #: Written by the progress flush. Startup recovery does not consult it —
    #: with one process every in-flight row at boot is an orphan. It is here for
    #: observability, and so a second process needs no migration.
    heartbeat_at: datetime | None = fields.DatetimeField(null=True)

    class Meta:
        table = "download_task"
        indexes = (
            Index(fields=("status", "created_at"), name="idx_task_status_created"),
        )
```

`api/src/data/db/model/download/__init__.py`:
```python
from .task import Task as Task
```

`api/src/data/db/model/__init__.py`:
```python
from .download import Task as Task
```

- [ ] **Step 2: Generate the migration**

```bash
cd api && make up && sleep 5
docker exec server-anydm-api uv run tortoise-cli --config src.data.db.DB_CONFIG migrate makemigrations --name initial
```
If the CLI entrypoint differs in tortoise-orm 1.1.8, run the equivalent from inside the container:
```bash
docker exec server-anydm-api python -c "
import asyncio
from tortoise.migrations.api import makemigrations
from src.data.db import DB_CONFIG
asyncio.run(makemigrations(config=DB_CONFIG, name='initial'))
"
```
Expected: `api/src/data/db/migration/0001_initial.py` created

- [ ] **Step 3: Inspect the generated migration**

Open `api/src/data/db/migration/0001_initial.py` and confirm it creates `download_task` with every column above and the `idx_task_status_created` index. Do not hand-edit it.

- [ ] **Step 4: Apply the migration**

```bash
cd api && make migrate
docker exec db-anydm-api psql -U user -d anydm -c '\d download_task'
```
Expected: the table exists with all columns and both indexes

- [ ] **Step 5: Confirm the suite still passes**

```bash
cd api && uv run pytest tests -v && uvx ruff check --fix && uv run ty check
```
Expected: PASS, no lint or type errors

- [ ] **Step 6: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): Task model and initial migration"
```

---

### Task 11: Preset → stream selection

Pure port of `resolveYouTubeDownload` in `apps/api/src/service/youtube.ts:281`, minus the URL resolution it interleaved. No network.

**Files:**
- Create: `api/src/lib/youtube/format.py`
- Test: `api/tests/lib/youtube/test_format.py`

**Interfaces:**
- Consumes: `src.lib.youtube.protocol.StreamInfo`, `src.data.type.{Preset, Kind}`, `src.lib.youtube.error.no_format_for_preset`
- Produces: `src.lib.youtube.format.DownloadPlan` — frozen dataclass `kind: Kind`, `video_itag: int | None`, `audio_itag: int | None`, `mime_type: str`, `extension: str`, `quality: str`; and `select_plan(streams: list[StreamInfo], preset: Preset) -> DownloadPlan`, `safe_filename(title: str, suffix: str, extension: str) -> str`

- [ ] **Step 1: Write the failing test**

`api/tests/lib/youtube/test_format.py`:

```python
import pytest

from src.core.error import Error
from src.data.type import Kind, Preset
from src.lib.youtube.format import safe_filename, select_plan
from src.lib.youtube.protocol import StreamInfo


def _video(itag: int, height: int, *, audio: bool = False) -> StreamInfo:
    return StreamInfo(
        itag=itag,
        mime_type="video/mp4",
        quality=f"{height}p",
        height=height,
        has_video=True,
        has_audio=audio,
    )


def _audio(itag: int, bitrate: int) -> StreamInfo:
    return StreamInfo(itag=itag, mime_type="audio/mp4", bitrate=bitrate, has_audio=True)


def test_mp3_picks_the_highest_bitrate_audio() -> None:
    plan = select_plan([_audio(139, 48000), _audio(140, 128000), _video(137, 1080)], Preset.MP3)
    assert plan.kind == Kind.AUDIO
    assert plan.audio_itag == 140
    assert plan.video_itag is None
    assert plan.extension == "mp3"
    assert plan.mime_type == "audio/mpeg"


def test_mp3_without_audio_is_rejected() -> None:
    with pytest.raises(Error):
        select_plan([_video(137, 1080)], Preset.MP3)


def test_combined_stream_wins_when_it_is_at_least_as_tall() -> None:
    plan = select_plan([_video(18, 720, audio=True), _video(136, 720), _audio(140, 128000)], Preset.P720)
    assert plan.kind == Kind.VIDEO
    assert plan.video_itag == 18
    assert plan.audio_itag is None


def test_video_plus_audio_when_the_combined_stream_is_shorter() -> None:
    plan = select_plan([_video(18, 360, audio=True), _video(137, 1080), _audio(140, 128000)], Preset.P1080)
    assert plan.kind == Kind.VIDEO
    assert plan.video_itag == 137
    assert plan.audio_itag == 140


def test_best_takes_the_tallest_available() -> None:
    plan = select_plan([_video(137, 1080), _video(313, 2160), _audio(140, 128000)], Preset.BEST)
    assert plan.video_itag == 313


def test_height_target_never_exceeds_the_request() -> None:
    plan = select_plan([_video(313, 2160), _video(137, 1080), _video(135, 480), _audio(140, 128000)], Preset.P1080)
    assert plan.video_itag == 137


def test_falls_back_to_the_tallest_when_everything_exceeds_the_target() -> None:
    plan = select_plan([_video(313, 2160), _video(137, 1080), _audio(140, 128000)], Preset.P480)
    assert plan.video_itag == 137


def test_no_video_at_all_is_rejected() -> None:
    with pytest.raises(Error) as caught:
        select_plan([_audio(140, 128000)], Preset.P1080)
    assert caught.value.code.value == 422


def test_video_only_without_audio_is_rejected() -> None:
    with pytest.raises(Error):
        select_plan([_video(137, 1080)], Preset.P1080)


@pytest.mark.parametrize(
    ("title", "suffix", "extension", "expected"),
    [
        ("Never Gonna Give You Up", "1080p", "mp4", "Never_Gonna_Give_You_Up_1080p.mp4"),
        ("Rick / Astley: Live!", "720p", "mp4", "Rick_Astley_Live_720p.mp4"),
        ("  spaced  out  ", "", "mp3", "spaced_out.mp3"),
        ("", "", "mp4", "download.mp4"),
        ("...", "", "mp4", "download.mp4"),
    ],
)
def test_safe_filename(title: str, suffix: str, extension: str, expected: str) -> None:
    assert safe_filename(title, suffix, extension) == expected


def test_safe_filename_truncates_very_long_titles() -> None:
    name = safe_filename("x" * 500, "1080p", "mp4")
    assert len(name) <= 200
    assert name.endswith("_1080p.mp4")
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/youtube/test_format.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.lib.youtube.format'`

- [ ] **Step 3: Write `api/src/lib/youtube/format.py`**

```python
"""Preset to stream selection. Pure — no network, no pytubefix.

A faithful port of ``resolveYouTubeDownload`` in the Bun API, with one
difference: that function resolved every stream URL before choosing, because
YouTube frequently locks adaptive streams. Here the choice is made on metadata
alone and the URL is resolved once, later, by the worker — so a locked stream
surfaces as a retryable download failure rather than as a silent downgrade.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from src.data.type import Kind, Preset
from src.lib.youtube.error import no_format_for_preset
from src.lib.youtube.protocol import StreamInfo

_UNSAFE = re.compile(r"[^\w\s-]", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
_MAX_STEM = 150


@dataclass(frozen=True, slots=True)
class DownloadPlan:
    kind: Kind
    video_itag: int | None
    audio_itag: int | None
    mime_type: str
    extension: str
    quality: str


def safe_filename(title: str, suffix: str, extension: str) -> str:
    """A filesystem-safe name built from ``title``.

    Unlike the Bun version this keeps non-ASCII letters — Python's ``\\w`` is
    Unicode-aware — so a Japanese or Cyrillic title survives instead of
    collapsing to an empty stem. The stem is capped so the full path stays
    inside the 255-byte limit every common filesystem enforces.
    """
    stem = _WHITESPACE.sub("_", _UNSAFE.sub("", (title or "").strip())).strip("_")
    stem = stem[:_MAX_STEM] or "download"
    tail = f"_{suffix}" if suffix else ""
    return f"{stem}{tail}.{extension}"


def _extension_of(mime_type: str | None, fallback: str) -> str:
    if not mime_type:
        return fallback
    parts = mime_type.split(";")[0].split("/")
    return parts[1] if len(parts) > 1 and parts[1] else fallback


def _height(stream: StreamInfo) -> int:
    return stream.height or 0


def _bitrate(stream: StreamInfo) -> int:
    return stream.bitrate or 0


def _pick_by_height(streams: list[StreamInfo], target: int | None) -> StreamInfo | None:
    """The tallest stream not exceeding ``target``, else the tallest there is.

    ``streams`` arrives sorted tallest-first. A ``target`` of ``None`` means
    "best", so the first entry wins outright.
    """
    if not streams:
        return None
    if target is None:
        return streams[0]
    return next((s for s in streams if _height(s) <= target), streams[0])


def select_plan(streams: list[StreamInfo], preset: Preset) -> DownloadPlan:
    combined = sorted((s for s in streams if s.has_video and s.has_audio), key=_height, reverse=True)
    video_only = sorted((s for s in streams if s.has_video and not s.has_audio), key=_height, reverse=True)
    audio_only = sorted((s for s in streams if s.has_audio and not s.has_video), key=_bitrate, reverse=True)

    if preset == Preset.MP3:
        if not audio_only:
            raise no_format_for_preset(preset.value)
        return DownloadPlan(
            kind=Kind.AUDIO,
            video_itag=None,
            audio_itag=audio_only[0].itag,
            mime_type="audio/mpeg",
            extension="mp3",
            quality="mp3",
        )

    target = preset.target_height
    best_combined = _pick_by_height(combined, target)
    best_video = _pick_by_height(video_only, target)
    best_audio = audio_only[0] if audio_only else None

    # Prefer the combined stream whenever it is at least as tall, since it needs
    # no muxing pass at all.
    if best_combined is not None and (best_video is None or _height(best_combined) >= _height(best_video)):
        return DownloadPlan(
            kind=Kind.VIDEO,
            video_itag=best_combined.itag,
            audio_itag=None,
            mime_type=(best_combined.mime_type or "video/mp4").split(";")[0],
            extension=_extension_of(best_combined.mime_type, "mp4"),
            quality=best_combined.quality or preset.value,
        )

    if best_video is not None and best_audio is not None:
        return DownloadPlan(
            kind=Kind.VIDEO,
            video_itag=best_video.itag,
            audio_itag=best_audio.itag,
            mime_type="video/mp4",
            extension="mp4",
            quality=best_video.quality or preset.value,
        )

    raise no_format_for_preset(preset.value)
```

Add to `api/src/lib/youtube/__init__.py`:
```python
from .format import DownloadPlan as DownloadPlan
from .format import safe_filename as safe_filename
from .format import select_plan as select_plan
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/youtube/test_format.py -v
```
Expected: PASS, 16 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): preset to stream selection"
```

---

### Task 12: Task repository

The claim query is the one piece of concurrency control in the system. It is database-stateful, so its tests carry `@pytest.mark.integration` and run against the compose Postgres — this is the known gap the spec names, and this task is where it is paid down as far as it goes.

**Files:**
- Create: `api/src/data/repo/{__init__,download/__init__}.py`
- Create: `api/src/data/repo/download/interface/{__init__,task}.py`
- Create: `api/src/data/repo/download/task_db.py`
- Test: `api/tests/integration/__init__.py`, `api/tests/integration/conftest.py`, `api/tests/integration/test_task_repo.py`

**Interfaces:**
- Consumes: `src.core.base.{BaseRepo, CrudRepo}`, `src.core.success.Meta`, `src.data.db.model.Task`, `src.data.type.{TaskStatus, ACTIVE_STATUSES}`, `src.core.common.now`
- Produces: `src.data.repo.download.interface.TaskRepo` (abstract) and `src.data.repo.TaskDatabaseRepo` with:
  - `async claim_next() -> Task | None`
  - `async recover_orphans() -> int`
  - `async flush_progress(task_id: UUID, *, downloaded_bytes: int, total_bytes: int | None, progress: int, speed_bps: int, eta_seconds: int | None) -> None`
  - `async list_page(page: int, page_size: int) -> tuple[list[Task], Meta]`
  - `async get_active_by_id(task_id: UUID) -> Task | None`

- [ ] **Step 1: Write the integration fixtures**

`api/tests/integration/conftest.py`:

```python
"""Fixtures for tests that need a real Postgres.

Every test here is marked ``integration`` and excluded from ``make test``. Run
them with ``make test-all`` while ``make up`` is running.
"""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from tortoise import Tortoise

from src.data.db import DB_CONFIG
from src.data.db.model import Task


@pytest_asyncio.fixture
async def db() -> AsyncIterator[None]:
    await Tortoise.init(config=DB_CONFIG)
    await Task.all().delete()
    yield
    await Task.all().delete()
    await Tortoise.close_connections()


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
```

- [ ] **Step 2: Write the failing test**

`api/tests/integration/test_task_repo.py`:

```python
import asyncio

import pytest

from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def _pending(title: str = "t") -> Task:
    return await Task.create(
        source_url="https://youtu.be/x",
        platform=Platform.YOUTUBE,
        preset=Preset.BEST,
        kind=Kind.VIDEO,
        status=TaskStatus.PENDING,
        title=title,
        filename=f"{title}.mp4",
    )


async def test_claim_next_returns_and_marks_downloading(db: None) -> None:
    await _pending()
    claimed = await TaskDatabaseRepo().claim_next()
    assert claimed is not None
    assert claimed.status == TaskStatus.DOWNLOADING
    assert claimed.started_at is not None


async def test_claim_next_returns_none_when_empty(db: None) -> None:
    assert await TaskDatabaseRepo().claim_next() is None


async def test_two_concurrent_claims_never_take_the_same_row(db: None) -> None:
    await _pending("only")
    repo = TaskDatabaseRepo()
    first, second = await asyncio.gather(repo.claim_next(), repo.claim_next())
    claimed = [task for task in (first, second) if task is not None]
    assert len(claimed) == 1


async def test_claim_next_is_oldest_first(db: None) -> None:
    old = await _pending("old")
    await _pending("new")
    claimed = await TaskDatabaseRepo().claim_next()
    assert claimed is not None
    assert claimed.id == old.id


async def test_claim_next_skips_a_backed_off_task(db: None) -> None:
    from datetime import timedelta

    from src.core.common import now

    task = await _pending()
    task.next_attempt_at = now() + timedelta(minutes=5)
    await task.save()
    assert await TaskDatabaseRepo().claim_next() is None


async def test_recover_orphans_requeues_and_keeps_bytes(db: None) -> None:
    task = await _pending()
    task.status = TaskStatus.DOWNLOADING
    task.downloaded_bytes = 4096
    await task.save()

    recovered = await TaskDatabaseRepo().recover_orphans()

    await task.refresh_from_db()
    assert recovered == 1
    assert task.status == TaskStatus.PENDING
    assert task.downloaded_bytes == 4096


async def test_recover_orphans_leaves_paused_alone(db: None) -> None:
    task = await _pending()
    task.status = TaskStatus.PAUSED
    await task.save()

    assert await TaskDatabaseRepo().recover_orphans() == 0
    await task.refresh_from_db()
    assert task.status == TaskStatus.PAUSED


async def test_flush_progress_writes_and_heartbeats(db: None) -> None:
    task = await _pending()
    await TaskDatabaseRepo().flush_progress(
        task.id, downloaded_bytes=512, total_bytes=1024, progress=50, speed_bps=256, eta_seconds=2
    )
    await task.refresh_from_db()
    assert task.downloaded_bytes == 512
    assert task.progress == 50
    assert task.speed_bps == 256
    assert task.eta_seconds == 2
    assert task.heartbeat_at is not None


async def test_list_page_paginates(db: None) -> None:
    for index in range(5):
        await _pending(f"t{index}")
    tasks, meta = await TaskDatabaseRepo().list_page(page=1, page_size=2)
    assert len(tasks) == 2
    assert meta.total == 5
    assert meta.total_pages == 3
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd api && make up && sleep 5 && uv run pytest tests/integration -m integration -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.data.repo'`

- [ ] **Step 4: Write the repository interface**

`api/src/data/repo/download/interface/task.py`:

```python
from __future__ import annotations

import uuid
from abc import abstractmethod

from src.core.base import CrudRepo
from src.core.success import Meta
from src.data.db.model import Task


class TaskRepo(CrudRepo[Task]):
    @abstractmethod
    async def claim_next(self) -> Task | None:
        """Take the oldest runnable pending task and mark it ``downloading``.

        Runnable means ``next_attempt_at`` is unset or already past. Returns
        ``None`` when the queue is empty.
        """
        ...

    @abstractmethod
    async def recover_orphans(self) -> int:
        """Requeue every task left mid-flight by a dead process. Returns the count."""
        ...

    @abstractmethod
    async def flush_progress(
        self,
        task_id: uuid.UUID,
        *,
        downloaded_bytes: int,
        total_bytes: int | None,
        progress: int,
        speed_bps: int,
        eta_seconds: int | None,
    ) -> None:
        """Write one progress sample and stamp ``heartbeat_at``."""
        ...

    @abstractmethod
    async def list_page(self, page: int, page_size: int) -> tuple[list[Task], Meta]:
        ...

    @abstractmethod
    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        ...
```

`api/src/data/repo/download/interface/__init__.py`:
```python
from .task import TaskRepo as TaskRepo
```

- [ ] **Step 5: Write the implementation**

`api/src/data/repo/download/task_db.py`:

```python
from __future__ import annotations

import uuid

from tortoise.expressions import Q
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Task
from src.data.repo.download.interface import TaskRepo
from src.data.type import ACTIVE_STATUSES, TaskStatus


class TaskDatabaseRepo(BaseRepo[Task], TaskRepo):
    def __init__(self) -> None:
        super().__init__(Task)

    async def claim_next(self) -> Task | None:
        """Postgres arbitrates the queue.

        ``FOR UPDATE SKIP LOCKED`` inside a transaction is what lets several
        worker coroutines — and, later, several processes — pull from one table
        without ever handing the same row to two of them. The status flip
        commits with the lock, so the row is unclaimable the moment it is taken.
        """
        async with in_transaction() as conn:
            task = await (
                Task.filter(status=TaskStatus.PENDING, deleted_at__isnull=True)
                .filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
                .order_by("created_at")
                .limit(1)
                .select_for_update(skip_locked=True)
                .using_db(conn)
                .first()
            )
            if task is None:
                return None

            task.status = TaskStatus.DOWNLOADING
            task.started_at = task.started_at or now()
            task.heartbeat_at = now()
            await task.save(using_db=conn)
            return task

    async def recover_orphans(self) -> int:
        """Every in-flight row at startup belongs to a process that is gone.

        ``downloaded_bytes`` is deliberately left alone: the ``.part`` file on
        disk still holds those bytes, and the next claim resumes from there.
        """
        return await Task.filter(status__in=list(ACTIVE_STATUSES), deleted_at__isnull=True).update(
            status=TaskStatus.PENDING,
            speed_bps=0,
            eta_seconds=None,
        )

    async def flush_progress(
        self,
        task_id: uuid.UUID,
        *,
        downloaded_bytes: int,
        total_bytes: int | None,
        progress: int,
        speed_bps: int,
        eta_seconds: int | None,
    ) -> None:
        await Task.filter(id=task_id).update(
            downloaded_bytes=downloaded_bytes,
            total_bytes=total_bytes,
            progress=progress,
            speed_bps=speed_bps,
            eta_seconds=eta_seconds,
            heartbeat_at=now(),
        )

    async def list_page(self, page: int, page_size: int) -> tuple[list[Task], Meta]:
        tasks, meta = await self.get_paginated(
            deleted_at__isnull=True,
            order_by="-created_at",
            page=page,
            page_size=page_size,
        )
        return tasks, Meta(**meta)

    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        return await self.get_one(id=task_id, deleted_at__isnull=True)
```

`api/src/data/repo/download/__init__.py`:
```python
from .task_db import TaskDatabaseRepo as TaskDatabaseRepo
```

`api/src/data/repo/__init__.py`:
```python
from .download import TaskDatabaseRepo as TaskDatabaseRepo
```

- [ ] **Step 6: Run the integration test to verify it passes**

```bash
cd api && uv run pytest tests/integration -m integration -v
```
Expected: PASS, 9 passed

- [ ] **Step 7: Confirm the default run still excludes them**

```bash
cd api && uv run pytest tests -v
```
Expected: the integration module is collected but every test in it is deselected or skipped; everything else passes

If they are not excluded, add to `api/pyproject.toml` under `[tool.pytest.ini_options]`:
```toml
addopts = "-m 'not integration'"
```
and re-run both commands.

- [ ] **Step 8: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): task repository with SKIP LOCKED claim and orphan recovery"
```

---

### Task 13: Download schemas, service and read endpoints

**Files:**
- Create: `api/src/data/schema/download/{__init__,download}.py`
- Create: `api/src/service/download/{__init__,download_service}.py`
- Create: `api/src/route/download/{__init__,download}.py`
- Modify: `api/src/route/__init__.py`, `api/src/service/__init__.py`
- Test: `api/tests/service/download/__init__.py`, `api/tests/service/download/test_download_service.py`

**Interfaces:**
- Consumes: `src.data.repo.download.interface.TaskRepo`, `src.lib.youtube.{YouTubeClient, extract_video_id, select_plan, safe_filename, not_a_youtube_url}`, `src.data.type.*`, `src.core.success.{Success, Meta}`, `src.core.error.Error`
- Produces:
  - `src.data.schema.download.YoutubeDownloadRequest` — `url: str`, `preset: Preset = Preset.BEST`
  - `src.data.schema.download.TaskSchema` — every API-visible column, snake_case
  - `src.service.download.DownloadService(repo: TaskRepo, client: YouTubeClient)` with `async enqueue_youtube(url, preset) -> TaskSchema`, `async list_tasks(page, page_size) -> tuple[list[TaskSchema], Meta]`, `async get_task(task_id) -> TaskSchema`
  - `src.service.get_download_service() -> DownloadService`

- [ ] **Step 1: Write the failing test**

`api/tests/service/download/test_download_service.py`:

```python
import uuid
from typing import Any

import pytest

from src.core.error import Error
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.youtube.protocol import StreamInfo, VideoInfo
from src.service.download.download_service import DownloadService

VIDEO_ID = "dQw4w9WgXcQ"


class FakeClient:
    async def fetch_info(self, video_id: str) -> VideoInfo:
        return VideoInfo(
            video_id=video_id,
            title="Never Gonna Give You Up",
            streams=[
                StreamInfo(itag=137, mime_type="video/mp4", quality="1080p", height=1080, has_video=True),
                StreamInfo(itag=140, mime_type="audio/mp4", bitrate=128000, has_audio=True),
            ],
        )

    async def stream_url(self, video_id: str, itag: int) -> str:
        raise AssertionError("enqueue must not resolve stream URLs")


class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()


def _service() -> tuple[DownloadService, FakeRepo]:
    repo = FakeRepo()
    return DownloadService(repo=repo, client=FakeClient()), repo  # ty: ignore[invalid-argument-type]


@pytest.mark.asyncio
async def test_enqueue_writes_a_pending_row() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    assert len(repo.created) == 1
    row = repo.created[0]
    assert row["status"] == TaskStatus.PENDING
    assert row["platform"] == Platform.YOUTUBE
    assert row["video_id"] == VIDEO_ID
    assert row["preset"] == Preset.P1080
    assert row["progress"] == 0


@pytest.mark.asyncio
async def test_enqueue_stores_the_resolved_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    row = repo.created[0]
    assert row["kind"] == Kind.VIDEO
    assert row["video_itag"] == 137
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up_1080p.mp4"
    assert row["title"] == "Never Gonna Give You Up"


@pytest.mark.asyncio
async def test_enqueue_stores_an_mp3_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.MP3)

    row = repo.created[0]
    assert row["kind"] == Kind.AUDIO
    assert row["video_itag"] is None
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up.mp3"


@pytest.mark.asyncio
async def test_enqueue_rejects_a_non_youtube_url() -> None:
    service, _ = _service()
    with pytest.raises(Error) as caught:
        await service.enqueue_youtube("https://example.com/v", Preset.BEST)
    assert caught.value.code.value == 400


@pytest.mark.asyncio
async def test_a_taller_preset_than_available_falls_back_to_the_tallest() -> None:
    # 1080p is the tallest on offer, so 2160 degrades rather than failing.
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P2160)
    assert repo.created[0]["video_itag"] == 137
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service.download'`

- [ ] **Step 3: Write `api/src/data/schema/download/download.py`**

```python
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema
from src.data.type import Kind, Platform, Preset, TaskStatus


class YoutubeDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A YouTube video URL")]
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset")]


class TaskSchema(BaseSchema):
    id: uuid.UUID
    source_url: str
    platform: Platform
    video_id: str | None = None
    preset: Preset
    kind: Kind
    title: str = ""
    filename: str = ""
    mime_type: str | None = None
    status: TaskStatus
    progress: int = 0
    downloaded_bytes: int = 0
    total_bytes: int | None = None
    speed_bps: int = 0
    eta_seconds: int | None = None
    file_size: int | None = None
    error: str | None = None
    error_code: str | None = None
    attempts: int = 0
    created_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
```

`file_path` is deliberately absent — it is a server-side path, and the client reaches the bytes through `GET /download/{id}/file`.

`api/src/data/schema/download/__init__.py`:
```python
from .download import TaskSchema as TaskSchema
from .download import YoutubeDownloadRequest as YoutubeDownloadRequest
```

- [ ] **Step 4: Write `api/src/service/download/download_service.py`**

```python
from __future__ import annotations

import uuid

from src.core.base import BaseService
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.interface import TaskRepo
from src.data.schema.download import TaskSchema
from src.data.type import Platform, Preset, TaskStatus
from src.lib.youtube import (
    YouTubeClient,
    extract_video_id,
    not_a_youtube_url,
    safe_filename,
    select_plan,
)


class DownloadService(BaseService):
    def __init__(self, repo: TaskRepo, client: YouTubeClient) -> None:
        super().__init__()
        self._repo = repo
        self._client = client

    async def enqueue_youtube(self, url: str, preset: Preset) -> TaskSchema:
        """Resolve the plan now, move the bytes later.

        Everything that can fail on the caller's behalf — a bad URL, a private
        video, a preset with no matching stream — fails here, as a 4xx they see
        immediately. What reaches the queue is a decision, not a guess.
        """
        video_id = extract_video_id(url)
        if video_id is None:
            raise not_a_youtube_url()

        info = await self._client.fetch_info(video_id)
        plan = select_plan(info.streams, preset)
        suffix = "" if preset == Preset.MP3 else plan.quality

        task = await self._repo.create(
            source_url=url,
            platform=Platform.YOUTUBE,
            video_id=video_id,
            preset=preset,
            kind=plan.kind,
            title=info.title,
            filename=safe_filename(info.title, suffix, plan.extension),
            mime_type=plan.mime_type,
            video_itag=plan.video_itag,
            audio_itag=plan.audio_itag,
            status=TaskStatus.PENDING,
            progress=0,
        )
        return TaskSchema.model_validate(task)

    async def list_tasks(self, page: int, page_size: int) -> tuple[list[TaskSchema], Meta]:
        tasks, meta = await self._repo.list_page(page=page, page_size=page_size)
        return [TaskSchema.model_validate(task) for task in tasks], meta

    async def get_task(self, task_id: uuid.UUID) -> TaskSchema:
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")
        return TaskSchema.model_validate(task)
```

`api/src/service/download/__init__.py`:
```python
from .download_service import DownloadService as DownloadService
```

Append to `api/src/service/__init__.py`:
```python
from src.data.repo import TaskDatabaseRepo
from src.service.download import DownloadService as DownloadService


def get_download_service() -> DownloadService:
    return DownloadService(repo=TaskDatabaseRepo(), client=get_youtube_client())
```

- [ ] **Step 5: Write the routes**

`api/src/route/download/download.py`:

```python
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.download import TaskSchema, YoutubeDownloadRequest
from src.service import DownloadService, get_download_service

router = APIRouter()


@router.post(
    path="/youtube",
    response_model=Success[TaskSchema],
)
async def enqueue_youtube(
    payload: YoutubeDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.enqueue_youtube(payload.url.strip(), payload.preset)
    return Success.created(data=data).to_resp()


@router.get(
    path="",
    response_model=Success[list[TaskSchema]],
)
async def list_tasks(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
) -> Response:
    data, meta = await download_service.list_tasks(page=page, page_size=page_size)
    return Success.ok(data=data, meta=meta).to_resp()


@router.get(
    path="/{task_id}",
    response_model=Success[TaskSchema],
)
async def get_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.get_task(task_id)
    return Success.ok(data=data).to_resp()
```

`api/src/route/download/__init__.py`:
```python
from fastapi import APIRouter

from .download import router as _download_router

_subrouters = [
    _download_router,
]

router = APIRouter(prefix="/download", tags=["Download"])

for subrouter in _subrouters:
    router.include_router(subrouter)
```

In `api/src/route/__init__.py`, add `from .download import router as _download_router` and append it to `_subrouters`.

If `Success.created` does not exist in auth's `success.py`, add it beside `ok`:
```python
@classmethod
def created(cls, data: Any = None, message: str | None = None) -> Success[Any]:
    return cls(code=Code.CREATED, message=message, data=data)
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests -v
```
Expected: PASS, all green

- [ ] **Step 7: Verify end to end**

```bash
cd api && make restart && sleep 6
curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","preset":"720"}' | python3 -m json.tool
curl -s localhost:8000/download | python3 -m json.tool | head -25
```
Expected: a 201 carrying a task with `status: "pending"`, then a list containing it. Nothing downloads yet — that is Phase 4.

- [ ] **Step 8: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): download schemas, service and read endpoints"
```

---

# Phase 4 — Downloader and worker pool

Ends with a real 1080p download completing to disk, and resuming after the process is killed mid-transfer.

### Task 14: Progress tracking

Pure arithmetic with an injected clock, so the throttle and the smoothing are testable without sleeping.

**Files:**
- Create: `api/src/service/download/progress.py`
- Test: `api/tests/service/download/test_progress.py`

**Interfaces:**
- Consumes: nothing
- Produces: `src.service.download.progress.ProgressSample` — frozen dataclass `downloaded_bytes: int`, `total_bytes: int | None`, `progress: int`, `speed_bps: int`, `eta_seconds: int | None`; and `ProgressTracker(*, total_bytes, initial_bytes=0, flush_interval_ms=1000, alpha=0.3, started_at=0.0)` with `record(chunk_len: int, at: float) -> ProgressSample | None` and `snapshot(at: float) -> ProgressSample`

- [ ] **Step 1: Write the failing test**

`api/tests/service/download/test_progress.py`:

```python
from src.service.download.progress import ProgressTracker


def _tracker(**overrides: object) -> ProgressTracker:
    base: dict[str, object] = {"total_bytes": 1000, "flush_interval_ms": 1000, "started_at": 0.0}
    base.update(overrides)
    return ProgressTracker(**base)  # ty: ignore[missing-argument]


def test_record_returns_nothing_before_the_interval_elapses() -> None:
    tracker = _tracker()
    assert tracker.record(100, at=0.5) is None
    assert tracker.record(100, at=0.9) is None


def test_record_emits_once_the_interval_elapses() -> None:
    tracker = _tracker()
    tracker.record(100, at=0.5)
    sample = tracker.record(400, at=1.0)
    assert sample is not None
    assert sample.downloaded_bytes == 500
    assert sample.progress == 50


def test_progress_is_capped_at_100() -> None:
    tracker = _tracker(total_bytes=100)
    sample = tracker.record(250, at=1.0)
    assert sample is not None
    assert sample.progress == 100


def test_progress_is_zero_without_a_known_total() -> None:
    tracker = _tracker(total_bytes=None)
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.progress == 0
    assert sample.eta_seconds is None


def test_first_sample_seeds_the_speed_rather_than_smoothing_from_zero() -> None:
    tracker = _tracker()
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.speed_bps == 500


def test_later_samples_are_smoothed() -> None:
    tracker = _tracker(total_bytes=100_000, alpha=0.5)
    tracker.record(500, at=1.0)          # seeds at 500 B/s
    sample = tracker.record(1500, at=2.0)  # instant 1500 B/s
    assert sample is not None
    assert sample.speed_bps == 1000       # 0.5 * 1500 + 0.5 * 500


def test_eta_uses_the_smoothed_speed() -> None:
    tracker = _tracker(total_bytes=2000)
    sample = tracker.record(500, at=1.0)
    assert sample is not None
    assert sample.eta_seconds == 3        # 1500 remaining at 500 B/s


def test_eta_is_none_when_stalled() -> None:
    tracker = _tracker()
    sample = tracker.record(0, at=1.0)
    assert sample is not None
    assert sample.eta_seconds is None


def test_resuming_counts_the_bytes_already_on_disk() -> None:
    tracker = _tracker(total_bytes=1000, initial_bytes=400)
    sample = tracker.record(100, at=1.0)
    assert sample is not None
    assert sample.downloaded_bytes == 500
    assert sample.progress == 50
    # Only the 100 new bytes count toward speed; the 400 came from a prior run.
    assert sample.speed_bps == 100


def test_snapshot_emits_regardless_of_the_interval() -> None:
    tracker = _tracker()
    sample = tracker.snapshot(at=0.1)
    assert sample is not None
    assert sample.downloaded_bytes == 0
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download/test_progress.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service.download.progress'`

- [ ] **Step 3: Write `api/src/service/download/progress.py`**

```python
"""Download progress arithmetic. Pure — the clock is a parameter.

The throttle lives here rather than in the downloader because it is the part
worth testing: a 4 GB file at 20 MB/s produces thousands of chunk callbacks a
second, and every one of them must not become a database write.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ProgressSample:
    downloaded_bytes: int
    total_bytes: int | None
    progress: int
    speed_bps: int
    eta_seconds: int | None


class ProgressTracker:
    def __init__(
        self,
        *,
        total_bytes: int | None,
        initial_bytes: int = 0,
        flush_interval_ms: int = 1000,
        alpha: float = 0.3,
        started_at: float = 0.0,
    ) -> None:
        self._total = total_bytes
        self._downloaded = initial_bytes
        self._interval = flush_interval_ms / 1000
        self._alpha = alpha
        self._speed = 0.0
        self._seeded = False
        self._last_flush_at = started_at
        self._bytes_at_last_flush = initial_bytes

    def record(self, chunk_len: int, at: float) -> ProgressSample | None:
        """Count ``chunk_len`` bytes; return a sample only when one is due."""
        self._downloaded += chunk_len
        if at - self._last_flush_at < self._interval:
            return None
        return self._flush(at)

    def snapshot(self, at: float) -> ProgressSample:
        """A sample now, whatever the throttle says — for the final write."""
        return self._flush(at)

    def _flush(self, at: float) -> ProgressSample:
        elapsed = at - self._last_flush_at
        if elapsed > 0:
            instant = (self._downloaded - self._bytes_at_last_flush) / elapsed
            # The first sample seeds the average. Smoothing it against an
            # initial zero would report roughly a third of the true speed for
            # the first several seconds of every download.
            self._speed = instant if not self._seeded else self._alpha * instant + (1 - self._alpha) * self._speed
            self._seeded = True

        self._last_flush_at = at
        self._bytes_at_last_flush = self._downloaded

        return ProgressSample(
            downloaded_bytes=self._downloaded,
            total_bytes=self._total,
            progress=self._percent(),
            speed_bps=int(self._speed),
            eta_seconds=self._eta(),
        )

    def _percent(self) -> int:
        if not self._total:
            return 0
        return min(100, self._downloaded * 100 // self._total)

    def _eta(self) -> int | None:
        if not self._total or self._speed <= 0:
            return None
        remaining = max(0, self._total - self._downloaded)
        return int(remaining / self._speed)
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/service/download/test_progress.py -v
```
Expected: PASS, 10 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): download progress tracking"
```

---

### Task 15: Resumable downloader

Knows nothing about YouTube or tasks: a URL, a destination, a progress callback. Tested with `httpx.MockTransport`, so no network.

**Files:**
- Create: `api/src/service/download/downloader.py`
- Test: `api/tests/service/download/test_downloader.py`

**Interfaces:**
- Consumes: `src.core.error.Error`, `src.core.type.{Code, ErrorType}`, `src.service.download.progress.{ProgressTracker, ProgressSample}`
- Produces: `src.service.download.downloader.Downloader(client: httpx.AsyncClient, chunk_size: int, flush_interval_ms: int)` with `async fetch(url: str, dest: Path, *, resume_from: int = 0, on_sample: Callable[[ProgressSample], Awaitable[None]] | None = None, should_stop: Callable[[], bool] | None = None) -> int`; and `src.service.download.downloader.Stopped` (exception)

- [ ] **Step 1: Write the failing test**

`api/tests/service/download/test_downloader.py`:

```python
from pathlib import Path

import httpx
import pytest

from src.core.error import Error
from src.service.download.downloader import Downloader, Stopped
from src.service.download.progress import ProgressSample

BODY = b"0123456789" * 10  # 100 bytes


def _client(handler: object) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))  # ty: ignore[invalid-argument-type]


def _ok(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, content=BODY, headers={"content-length": str(len(BODY))})


@pytest.mark.asyncio
async def test_fetch_writes_the_whole_body(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    async with _client(_ok) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest
        )
    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_fetch_sends_a_range_header_when_resuming(tmp_path: Path) -> None:
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("range"))
        return httpx.Response(206, content=BODY[40:], headers={"content-range": f"bytes 40-99/{len(BODY)}"})

    dest = tmp_path / "out.bin"
    dest.write_bytes(BODY[:40])

    async with _client(handler) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest, resume_from=40
        )

    assert seen == ["bytes=40-"]
    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_a_server_that_ignores_range_restarts_from_zero(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    dest.write_bytes(b"stale-partial")

    async with _client(_ok) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest, resume_from=13
        )

    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_progress_samples_are_delivered(tmp_path: Path) -> None:
    samples: list[ProgressSample] = []

    async def collect(sample: ProgressSample) -> None:
        samples.append(sample)

    async with _client(_ok) as client:
        await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", tmp_path / "out.bin", on_sample=collect
        )

    assert samples
    assert samples[-1].downloaded_bytes == 100
    assert samples[-1].progress == 100


@pytest.mark.asyncio
async def test_should_stop_raises_and_keeps_the_partial_file(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"

    async with _client(_ok) as client:
        with pytest.raises(Stopped):
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", dest, should_stop=lambda: True
            )

    assert dest.exists()


@pytest.mark.asyncio
async def test_a_403_is_retryable(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(403)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_500_is_retryable(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(500)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_404_is_permanent(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(404)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is False


@pytest.mark.asyncio
async def test_a_transport_failure_is_retryable(tmp_path: Path) -> None:
    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    async with _client(boom) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download/test_downloader.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service.download.downloader'`

- [ ] **Step 3: Write `api/src/service/download/downloader.py`**

```python
"""Pull a URL to a file, resumably. Knows nothing about YouTube or tasks."""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable
from pathlib import Path

import httpx
from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.progress import ProgressSample, ProgressTracker

#: Statuses worth trying again. 403 is here because an expired stream URL
#: presents as one, and re-resolving fixes it.
_RETRYABLE_STATUSES = frozenset({403, 408, 409, 425, 429, 500, 502, 503, 504})


class Stopped(Exception):
    """Raised when ``should_stop`` asked the transfer to end — a pause or a cancel.

    Not an ``Error``: it is a control signal, not a failure, and the caller
    decides which status the task lands in.
    """


def _status_error(status: int) -> Error:
    retry_able = status in _RETRYABLE_STATUSES
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Upstream returned status {status}",
        error_type=ErrorType.EXTERNAL_API_ERROR if retry_able else ErrorType.DOES_NOT_EXIST,
        retry_able=retry_able,
    )


def _transport_error(exc: Exception) -> Error:
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Transfer failed: {exc}",
        error_type=ErrorType.DEPENDENCY_FAILURE,
        retry_able=True,
    )


class Downloader:
    def __init__(self, client: httpx.AsyncClient, chunk_size: int, flush_interval_ms: int) -> None:
        self._client = client
        self._chunk_size = chunk_size
        self._flush_interval_ms = flush_interval_ms

    async def fetch(
        self,
        url: str,
        dest: Path,
        *,
        resume_from: int = 0,
        on_sample: Callable[[ProgressSample], Awaitable[None]] | None = None,
        should_stop: Callable[[], bool] | None = None,
    ) -> int:
        """Stream ``url`` into ``dest``, returning the total bytes on disk.

        ``resume_from`` sends a ``Range`` request and appends. A server that
        answers 200 anyway has ignored the range, so the file is truncated and
        started over rather than silently corrupted by appending a full body to
        a partial one.
        """
        dest.parent.mkdir(parents=True, exist_ok=True)
        headers = {"Range": f"bytes={resume_from}-"} if resume_from > 0 else {}

        try:
            async with self._client.stream("GET", url, headers=headers, follow_redirects=True) as response:
                if response.status_code >= 400:
                    raise _status_error(response.status_code)

                resuming = resume_from > 0 and response.status_code == 206
                if resume_from > 0 and not resuming:
                    logger.warning("Downloader|fetch(): server ignored Range, restarting {}", dest.name)

                start_bytes = resume_from if resuming else 0
                total = self._total_bytes(response, start_bytes)
                tracker = ProgressTracker(
                    total_bytes=total,
                    initial_bytes=start_bytes,
                    flush_interval_ms=self._flush_interval_ms,
                    started_at=time.monotonic(),
                )

                with dest.open("ab" if resuming else "wb") as handle:
                    async for chunk in response.aiter_bytes(self._chunk_size):
                        if should_stop is not None and should_stop():
                            raise Stopped
                        handle.write(chunk)
                        sample = tracker.record(len(chunk), at=time.monotonic())
                        if sample is not None and on_sample is not None:
                            await on_sample(sample)

                if on_sample is not None:
                    await on_sample(tracker.snapshot(at=time.monotonic()))

                return dest.stat().st_size
        except (Stopped, Error):
            raise
        except httpx.HTTPError as exc:
            logger.error("Downloader|fetch({}): {}", url, exc)
            raise _transport_error(exc) from exc

    @staticmethod
    def _total_bytes(response: httpx.Response, start_bytes: int) -> int | None:
        """The full size of the file, not of this response.

        A 206 reports the remaining length in ``Content-Length`` and the whole
        size after the slash in ``Content-Range``; preferring the latter keeps
        the percentage honest across a resume.
        """
        content_range = response.headers.get("content-range")
        if content_range and "/" in content_range:
            tail = content_range.rsplit("/", 1)[1].strip()
            if tail.isdigit():
                return int(tail)
        length = response.headers.get("content-length")
        if length and length.isdigit():
            return int(length) + start_bytes
        return None
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/service/download/test_downloader.py -v
```
Expected: PASS, 9 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): resumable downloader"
```

---

### Task 16: Worker pool, retry policy and orphan recovery

The largest task in the plan. It is one task because none of its pieces is independently useful: a claim loop with no retry policy is not shippable, and a retry policy with no loop has nothing to govern.

Post-processing is injected rather than inlined. Phase 4 ships `SingleStreamPostProcessor`, which handles a plan naming one stream and refuses a plan needing a mux with a clear permanent error. Task 18 swaps in the ffmpeg implementation without touching the worker.

**Files:**
- Create: `api/src/service/download/control.py`, `api/src/service/download/retry.py`, `api/src/service/download/paths.py`
- Create: `api/src/service/download/post_process.py`, `api/src/service/download/download_worker.py`
- Modify: `api/src/main.py`, `api/src/service/__init__.py`
- Test: `api/tests/service/download/{test_control,test_retry,test_paths,test_post_process}.py`
- Test: `api/tests/integration/test_worker.py`

**Interfaces:**
- Consumes: `src.data.repo.download.interface.TaskRepo`, `src.service.download.downloader.{Downloader, Stopped}`, `src.lib.youtube.YouTubeClient`, `src.core.error.Error`, `src.config.get_settings`
- Produces:
  - `src.service.download.control.DownloadControl` — `wake()`, `async wait_for_work(timeout: float)`, `request_stop(task_id)`, `clear_stop(task_id)`, `is_stopping(task_id) -> bool`
  - `src.service.download.retry.backoff_seconds(attempt: int) -> int`, `RetryDecision` (frozen dataclass: `retry: bool`, `delay_seconds: int`), `decide(error: Error, attempts: int, max_attempts: int) -> RetryDecision`
  - `src.service.download.paths.task_dir(root: Path, task_id: UUID) -> Path`, `part_path(root, task_id, name) -> Path`, `final_path(root, task_id, filename) -> Path`
  - `src.service.download.post_process.PostProcessor` (Protocol: `async run(task, parts: dict[str, Path], destination: Path) -> None`), `SingleStreamPostProcessor`
  - `src.service.download.download_worker.WorkerPool(...)` with `async start()`, `async stop()`

- [ ] **Step 1: Write the failing unit tests**

`api/tests/service/download/test_retry.py`:

```python
import pytest

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.retry import backoff_seconds, decide


def _retryable() -> Error:
    return Error.create(code=Code.BAD_GATEWAY, error_type=ErrorType.EXTERNAL_API_ERROR, retry_able=True)


def _permanent() -> Error:
    return Error.create(code=Code.NOT_FOUND, error_type=ErrorType.DOES_NOT_EXIST, retry_able=False)


@pytest.mark.parametrize(("attempt", "seconds"), [(1, 5), (2, 30), (3, 120), (4, 120), (99, 120)])
def test_backoff_schedule(attempt: int, seconds: int) -> None:
    assert backoff_seconds(attempt) == seconds


def test_a_permanent_error_never_retries() -> None:
    assert decide(_permanent(), attempts=1, max_attempts=3).retry is False


def test_a_retryable_error_retries_while_attempts_remain() -> None:
    decision = decide(_retryable(), attempts=1, max_attempts=3)
    assert decision.retry is True
    assert decision.delay_seconds == 5


def test_the_second_retry_waits_longer() -> None:
    assert decide(_retryable(), attempts=2, max_attempts=3).delay_seconds == 30


def test_the_last_attempt_does_not_retry() -> None:
    # attempts counts tries, so with max_attempts=3 the third failure is final.
    assert decide(_retryable(), attempts=3, max_attempts=3).retry is False


def test_max_attempts_of_one_never_retries() -> None:
    assert decide(_retryable(), attempts=1, max_attempts=1).retry is False
```

`api/tests/service/download/test_control.py`:

```python
import asyncio
import uuid

import pytest

from src.service.download.control import DownloadControl


def test_stop_registry_roundtrip() -> None:
    control = DownloadControl()
    task_id = uuid.uuid4()

    assert control.is_stopping(task_id) is False
    control.request_stop(task_id)
    assert control.is_stopping(task_id) is True
    control.clear_stop(task_id)
    assert control.is_stopping(task_id) is False


def test_stops_are_independent() -> None:
    control = DownloadControl()
    first, second = uuid.uuid4(), uuid.uuid4()
    control.request_stop(first)
    assert control.is_stopping(second) is False


@pytest.mark.asyncio
async def test_wait_for_work_returns_immediately_once_woken() -> None:
    control = DownloadControl()
    control.wake()
    await asyncio.wait_for(control.wait_for_work(timeout=5), timeout=1)


@pytest.mark.asyncio
async def test_wait_for_work_times_out_when_idle() -> None:
    control = DownloadControl()
    await asyncio.wait_for(control.wait_for_work(timeout=0.05), timeout=1)


@pytest.mark.asyncio
async def test_the_wake_flag_is_consumed() -> None:
    control = DownloadControl()
    control.wake()
    await control.wait_for_work(timeout=5)
    # Second call must block until the timeout rather than returning at once.
    loop = asyncio.get_running_loop()
    started = loop.time()
    await control.wait_for_work(timeout=0.1)
    assert loop.time() - started >= 0.05
```

`api/tests/service/download/test_paths.py`:

```python
import uuid
from pathlib import Path

from src.service.download.paths import final_path, part_path, task_dir


def test_task_dir_is_namespaced_by_id() -> None:
    task_id = uuid.uuid4()
    assert task_dir(Path("/downloads"), task_id) == Path("/downloads") / str(task_id)


def test_part_path_appends_the_part_suffix() -> None:
    task_id = uuid.uuid4()
    assert part_path(Path("/downloads"), task_id, "video").name == "video.part"


def test_final_path_uses_the_filename() -> None:
    task_id = uuid.uuid4()
    assert final_path(Path("/downloads"), task_id, "clip.mp4").name == "clip.mp4"


def test_final_path_strips_directory_components_from_the_filename() -> None:
    task_id = uuid.uuid4()
    path = final_path(Path("/downloads"), task_id, "../../etc/passwd")
    assert path.parent == task_dir(Path("/downloads"), task_id)
    assert path.name == "passwd"
```

`api/tests/service/download/test_post_process.py`:

```python
from pathlib import Path
from types import SimpleNamespace

import pytest

from src.core.error import Error
from src.data.type import Kind
from src.service.download.post_process import SingleStreamPostProcessor


@pytest.mark.asyncio
async def test_a_single_stream_is_renamed_into_place(tmp_path: Path) -> None:
    part = tmp_path / "video.part"
    part.write_bytes(b"data")
    destination = tmp_path / "clip.mp4"

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=None, video_itag=137)
    await SingleStreamPostProcessor().run(task, {"video": part}, destination)

    assert destination.read_bytes() == b"data"
    assert not part.exists()


@pytest.mark.asyncio
async def test_a_plan_needing_a_mux_is_refused_permanently(tmp_path: Path) -> None:
    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=140, video_itag=137)
    with pytest.raises(Error) as caught:
        await SingleStreamPostProcessor().run(
            task, {"video": tmp_path / "v.part", "audio": tmp_path / "a.part"}, tmp_path / "out.mp4"
        )
    assert caught.value.retry_able is False


@pytest.mark.asyncio
async def test_an_mp3_plan_is_refused_permanently(tmp_path: Path) -> None:
    task = SimpleNamespace(kind=Kind.AUDIO, audio_itag=140, video_itag=None)
    with pytest.raises(Error):
        await SingleStreamPostProcessor().run(task, {"audio": tmp_path / "a.part"}, tmp_path / "out.mp3")
```

- [ ] **Step 2: Run the unit tests to verify they fail**

```bash
cd api && uv run pytest tests/service/download -v
```
Expected: FAIL — four `ModuleNotFoundError`s for `control`, `retry`, `paths`, `post_process`

- [ ] **Step 3: Write `api/src/service/download/control.py`**

```python
"""In-process signalling between the API and the workers.

Both live in one process, which is what makes this an ``asyncio.Event`` and a
set rather than a table or a message broker. Enqueue wakes a worker in
microseconds, and a pause reaches a running transfer between chunks.
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid


class DownloadControl:
    def __init__(self) -> None:
        self._work = asyncio.Event()
        self._stopping: set[uuid.UUID] = set()

    def wake(self) -> None:
        """Tell an idle worker there is something to claim."""
        self._work.set()

    async def wait_for_work(self, timeout: float) -> None:
        """Block until woken or ``timeout`` elapses, then consume the signal.

        The timeout is the fallback poll: it covers a row that appeared without
        going through enqueue, such as one a retry backoff has just made
        runnable.
        """
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self._work.wait(), timeout=timeout)
        self._work.clear()

    def request_stop(self, task_id: uuid.UUID) -> None:
        self._stopping.add(task_id)

    def clear_stop(self, task_id: uuid.UUID) -> None:
        self._stopping.discard(task_id)

    def is_stopping(self, task_id: uuid.UUID) -> bool:
        return task_id in self._stopping
```

- [ ] **Step 4: Write `api/src/service/download/retry.py`**

```python
"""The retry policy. Pure.

``Error.retry_able`` is the whole decision — it is set where the failure is
raised, by the code that knows whether trying again could possibly help.
"""

from __future__ import annotations

from dataclasses import dataclass

from src.core.error import Error

#: Seconds to wait before attempt N+1. The last entry repeats for any attempt
#: beyond the schedule.
_SCHEDULE = (5, 30, 120)


def backoff_seconds(attempt: int) -> int:
    index = min(max(attempt, 1), len(_SCHEDULE)) - 1
    return _SCHEDULE[index]


@dataclass(frozen=True, slots=True)
class RetryDecision:
    retry: bool
    delay_seconds: int = 0


def decide(error: Error, attempts: int, max_attempts: int) -> RetryDecision:
    """``attempts`` counts tries including this one, so the Nth failure is final."""
    if not error.retry_able or attempts >= max_attempts:
        return RetryDecision(retry=False)
    return RetryDecision(retry=True, delay_seconds=backoff_seconds(attempts))
```

- [ ] **Step 5: Write `api/src/service/download/paths.py`**

```python
"""Where a task's bytes live: ``<downloads>/<task_id>/<filename>``.

Namespacing by task id is what makes cancel a directory removal and keeps two
downloads of the same video from colliding. It also matches the Bun API's
layout, so the torrent port can reuse it.
"""

from __future__ import annotations

import uuid
from pathlib import Path


def task_dir(root: Path, task_id: uuid.UUID) -> Path:
    return root / str(task_id)


def part_path(root: Path, task_id: uuid.UUID, name: str) -> Path:
    return task_dir(root, task_id) / f"{name}.part"


def final_path(root: Path, task_id: uuid.UUID, filename: str) -> Path:
    """The finished file.

    ``Path(filename).name`` is not decoration: ``filename`` is derived from a
    video title, and a title containing slashes must not be able to place a
    file outside the task's own directory.
    """
    return task_dir(root, task_id) / Path(filename).name
```

- [ ] **Step 6: Write `api/src/service/download/post_process.py`**

```python
"""What happens to the downloaded parts once the bytes are on disk.

A Protocol rather than a function so Task 18 can substitute the ffmpeg
implementation without the worker changing at all.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Protocol

from src.core.error import Error
from src.core.type import Code, ErrorType


class PostProcessor(Protocol):
    async def run(self, task: Any, parts: dict[str, Path], destination: Path) -> None:
        """Turn ``parts`` into the single file at ``destination``."""
        ...


class SingleStreamPostProcessor(PostProcessor):
    """Handles the one case that needs no transcoding: a single combined stream.

    Anything requiring ffmpeg is refused permanently rather than left half-done.
    Task 18 replaces this class; until then a mux or an MP3 request fails with a
    message saying exactly that, which is better than a task that sits at
    ``muxing`` forever.
    """

    async def run(self, task: Any, parts: dict[str, Path], destination: Path) -> None:
        if len(parts) != 1 or "video" not in parts:
            raise Error.create(
                code=Code.NOT_IMPLEMENTED,
                message="This download needs ffmpeg muxing, which is not available yet",
                error_type=ErrorType.NOT_IMPLEMENTED,
            )
        parts["video"].replace(destination)
```

- [ ] **Step 7: Run the unit tests to verify they pass**

```bash
cd api && uv run pytest tests/service/download -v
```
Expected: PASS, all green

- [ ] **Step 8: Write `api/src/service/download/download_worker.py`**

```python
"""The claim loop.

One coroutine per worker, all sharing a ``DownloadControl`` and pulling from
Postgres. The database arbitrates who gets which row; everything else here is
sequencing and bookkeeping.
"""

from __future__ import annotations

import asyncio
import shutil
from datetime import timedelta
from pathlib import Path

import httpx
from loguru import logger

from src.core.common import now
from src.core.error import Error
from src.data.db.model import Task
from src.data.repo.download.interface import TaskRepo
from src.data.type import Kind, TaskStatus
from src.lib.youtube import YouTubeClient
from src.service.download.control import DownloadControl
from src.service.download.downloader import Downloader, Stopped
from src.service.download.paths import final_path, part_path, task_dir
from src.service.download.post_process import PostProcessor
from src.service.download.progress import ProgressSample
from src.service.download import retry as retry_policy

_IDLE_POLL_SECONDS = 5.0


class DownloadWorker:
    def __init__(
        self,
        *,
        name: str,
        repo: TaskRepo,
        client: YouTubeClient,
        downloader: Downloader,
        post_processor: PostProcessor,
        control: DownloadControl,
        downloads_root: Path,
        max_attempts: int,
    ) -> None:
        self._name = name
        self._repo = repo
        self._client = client
        self._downloader = downloader
        self._post_processor = post_processor
        self._control = control
        self._root = downloads_root
        self._max_attempts = max_attempts

    async def run_forever(self) -> None:
        while True:
            try:
                task = await self._repo.claim_next()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("{}|claim failed: {}", self._name, exc)
                await asyncio.sleep(_IDLE_POLL_SECONDS)
                continue

            if task is None:
                await self._control.wait_for_work(timeout=_IDLE_POLL_SECONDS)
                continue

            await self._run_task(task)

    async def _run_task(self, task: Task) -> None:
        logger.info("{}|starting {} ({})", self._name, task.id, task.filename)
        task.attempts += 1
        await task.save()

        try:
            parts = await self._download_parts(task)
            destination = final_path(self._root, task.id, task.filename)
            await self._post_processor.run(task, parts, destination)
            await self._mark_complete(task, destination)
        except Stopped:
            # A pause or a cancel already set the row's status; leave it alone
            # and leave the ``.part`` files where they are.
            logger.info("{}|stopped {}", self._name, task.id)
            self._control.clear_stop(task.id)
        except Error as error:
            await self._mark_failed(task, error)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("{}|unexpected failure on {}", self._name, task.id)
            await self._mark_failed(task, Error.internal(message=str(exc)))

    async def _download_parts(self, task: Task) -> dict[str, Path]:
        """Fetch every stream the plan names, resuming any ``.part`` already there."""
        wanted: list[tuple[str, int]] = []
        if task.video_itag is not None:
            wanted.append(("video", task.video_itag))
        if task.audio_itag is not None:
            wanted.append(("audio", task.audio_itag))
        if not wanted:
            raise Error.internal(message="Task names no stream to download")

        parts: dict[str, Path] = {}
        for name, itag in wanted:
            destination = part_path(self._root, task.id, name)
            resume_from = destination.stat().st_size if destination.exists() else 0
            # Always re-resolved: these URLs expire within hours and bind to the
            # requesting IP, so a stored one is worthless on a resume.
            url = await self._client.stream_url(task.video_id or "", itag)
            await self._downloader.fetch(
                url,
                destination,
                resume_from=resume_from,
                on_sample=lambda sample, task_id=task.id: self._flush(task_id, sample),
                should_stop=lambda task_id=task.id: self._control.is_stopping(task_id),
            )
            parts[name] = destination
        return parts

    async def _flush(self, task_id: object, sample: ProgressSample) -> None:
        await self._repo.flush_progress(
            task_id,  # ty: ignore[invalid-argument-type]
            downloaded_bytes=sample.downloaded_bytes,
            total_bytes=sample.total_bytes,
            progress=sample.progress,
            speed_bps=sample.speed_bps,
            eta_seconds=sample.eta_seconds,
        )

    async def _mark_complete(self, task: Task, destination: Path) -> None:
        task.status = TaskStatus.COMPLETE
        task.progress = 100
        task.speed_bps = 0
        task.eta_seconds = None
        task.file_path = str(destination.relative_to(self._root))
        task.file_size = destination.stat().st_size
        task.completed_at = now()
        task.error = None
        task.error_code = None
        await task.save()
        logger.success("{}|completed {} -> {}", self._name, task.id, task.file_path)

    async def _mark_failed(self, task: Task, error: Error) -> None:
        decision = retry_policy.decide(error, attempts=task.attempts, max_attempts=self._max_attempts)
        task.error = error.message
        task.error_code = error.type.value if error.type else None
        task.speed_bps = 0
        task.eta_seconds = None

        if decision.retry:
            task.status = TaskStatus.PENDING
            task.next_attempt_at = now() + timedelta(seconds=decision.delay_seconds)
            logger.warning(
                "{}|retrying {} in {}s (attempt {}): {}",
                self._name, task.id, decision.delay_seconds, task.attempts, error.message,
            )
        else:
            task.status = TaskStatus.FAILED
            task.next_attempt_at = None
            logger.error("{}|failed {}: {}", self._name, task.id, error.message)

        await task.save()


class WorkerPool:
    def __init__(self, workers: list[DownloadWorker], http_client: httpx.AsyncClient) -> None:
        self._workers = workers
        self._http_client = http_client
        self._tasks: list[asyncio.Task[None]] = []

    async def start(self) -> None:
        self._tasks = [asyncio.create_task(worker.run_forever()) for worker in self._workers]
        logger.info("WorkerPool|started {} worker(s)", len(self._tasks))

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        await self._http_client.aclose()
        logger.info("WorkerPool|stopped")


def remove_task_files(root: Path, task_id: object) -> None:
    """Delete a task's whole directory. Used by cancel."""
    shutil.rmtree(task_dir(root, task_id), ignore_errors=True)  # ty: ignore[invalid-argument-type]
```

- [ ] **Step 9: Wire the pool into the app**

Append to `api/src/service/__init__.py`:

```python
from functools import lru_cache
from pathlib import Path

import httpx

from src.config import get_settings
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker, WorkerPool
from src.service.download.downloader import Downloader
from src.service.download.post_process import SingleStreamPostProcessor


@lru_cache
def get_download_control() -> DownloadControl:
    return DownloadControl()


def build_worker_pool() -> WorkerPool:
    settings = get_settings()
    http_client = httpx.AsyncClient(timeout=httpx.Timeout(30.0, read=None))
    downloader = Downloader(
        http_client,
        chunk_size=settings.download_chunk_size,
        flush_interval_ms=settings.progress_flush_ms,
    )
    workers = [
        DownloadWorker(
            name=f"worker-{index}",
            repo=TaskDatabaseRepo(),
            client=get_youtube_client(),
            downloader=downloader,
            post_processor=SingleStreamPostProcessor(),
            control=get_download_control(),
            downloads_root=Path(settings.downloads_dir),
            max_attempts=settings.max_attempts,
        )
        for index in range(settings.download_workers)
    ]
    return WorkerPool(workers, http_client)
```

Replace `lifespan` in `api/src/main.py`:

```python
@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Recover orphans, then run the workers for the life of the process.

    Tortoise's startup runs first — ``register_tortoise`` merges it ahead of
    this one — so the database is reachable here.

    Recovery is unconditional because this process is the only one that runs
    workers: every row still marked in-flight at boot belonged to a process
    that is gone. ``downloaded_bytes`` survives and the ``.part`` files stay on
    disk, so each requeued task resumes from where it stopped rather than
    starting over.
    """
    settings = get_settings()
    Path(settings.downloads_dir).mkdir(parents=True, exist_ok=True)

    recovered = await TaskDatabaseRepo().recover_orphans()
    if recovered:
        logger.warning("lifespan|requeued {} orphaned task(s)", recovered)

    pool = build_worker_pool()
    await pool.start()
    try:
        yield
    finally:
        await pool.stop()
```

Add the imports `from pathlib import Path`, `from loguru import logger`, `from src.data.repo import TaskDatabaseRepo`, `from src.service import build_worker_pool` to `main.py`.

- [ ] **Step 10: Wake a worker on enqueue**

In `api/src/service/download/download_service.py`, accept the control object and signal it. Change `__init__` to `def __init__(self, repo: TaskRepo, client: YouTubeClient, control: DownloadControl) -> None:` storing `self._control = control`, and add `self._control.wake()` as the last statement before `return TaskSchema.model_validate(task)` in `enqueue_youtube`.

Update `get_download_service()` in `api/src/service/__init__.py` to pass `control=get_download_control()`.

Update `api/tests/service/download/test_download_service.py`'s `_service()` helper to pass `control=DownloadControl()`, importing it from `src.service.download.control`.

- [ ] **Step 11: Write the worker integration test**

`api/tests/integration/test_worker.py`:

```python
from pathlib import Path

import httpx
import pytest

from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Downloader
from src.service.download.post_process import SingleStreamPostProcessor

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

BODY = b"z" * 500


class FakeYouTube:
    async def fetch_info(self, video_id: str):  # noqa: ANN201 - unused by the worker
        raise AssertionError("the worker must not re-fetch info")

    async def stream_url(self, video_id: str, itag: int) -> str:
        return f"https://cdn.test/{video_id}/{itag}"


def _worker(tmp_path: Path, control: DownloadControl, client: httpx.AsyncClient) -> DownloadWorker:
    return DownloadWorker(
        name="test-worker",
        repo=TaskDatabaseRepo(),
        client=FakeYouTube(),  # ty: ignore[invalid-argument-type]
        downloader=Downloader(client, chunk_size=64, flush_interval_ms=0),
        post_processor=SingleStreamPostProcessor(),
        control=control,
        downloads_root=tmp_path,
        max_attempts=3,
    )


async def _task(**overrides: object) -> Task:
    fields: dict[str, object] = {
        "source_url": "https://youtu.be/x",
        "platform": Platform.YOUTUBE,
        "video_id": "x",
        "preset": Preset.P720,
        "kind": Kind.VIDEO,
        "status": TaskStatus.PENDING,
        "title": "clip",
        "filename": "clip.mp4",
        "video_itag": 22,
    }
    fields.update(overrides)
    return await Task.create(**fields)


async def test_a_claimed_task_downloads_and_completes(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.COMPLETE
    assert task.progress == 100
    assert task.file_size == 500
    assert (tmp_path / str(task.id) / "clip.mp4").read_bytes() == BODY


async def test_a_retryable_failure_requeues_with_a_backoff(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(503))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.PENDING
    assert task.attempts == 1
    assert task.next_attempt_at is not None
    assert task.error is not None


async def test_a_permanent_failure_stops_at_once(db: None, tmp_path: Path) -> None:
    task = await _task()
    transport = httpx.MockTransport(lambda request: httpx.Response(404))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.FAILED
    assert task.next_attempt_at is None


async def test_the_last_attempt_fails_rather_than_retrying(db: None, tmp_path: Path) -> None:
    task = await _task(attempts=2)
    transport = httpx.MockTransport(lambda request: httpx.Response(503))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    await task.refresh_from_db()
    assert task.attempts == 3
    assert task.status == TaskStatus.FAILED


async def test_a_stop_request_leaves_the_partial_file(db: None, tmp_path: Path) -> None:
    task = await _task()
    control = DownloadControl()
    control.request_stop(task.id)
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, control, client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    assert (tmp_path / str(task.id) / "video.part").exists()


async def test_a_mux_plan_is_refused_permanently(db: None, tmp_path: Path) -> None:
    task = await _task(video_itag=137, audio_itag=140)
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=BODY))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = _worker(tmp_path, DownloadControl(), client)
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    await task.refresh_from_db()
    assert task.status == TaskStatus.FAILED
    assert task.error is not None
    assert "ffmpeg" in task.error
```

- [ ] **Step 12: Run every test**

```bash
cd api && uv run pytest tests -v && uv run pytest tests/integration -m integration -v
```
Expected: both green

- [ ] **Step 13: Verify a real download**

```bash
cd api && make restart && sleep 6
TASK=$(curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","preset":"720"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
echo "task $TASK"
sleep 20
curl -s "localhost:8000/download/$TASK" | python3 -m json.tool
```
Expected: `status` progresses to `"complete"` with `progress: 100` and a non-null `file_size`. `preset: "720"` is chosen because YouTube's combined streams top out there; a preset needing a mux correctly fails with the ffmpeg message until Task 18.

- [ ] **Step 14: Verify resume**

```bash
cd api && make restart && sleep 6
curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=aqz-KE-bpKQ","preset":"720"}' > /dev/null
sleep 3
docker restart server-anydm-api && sleep 8
curl -s localhost:8000/download | python3 -m json.tool | head -30
```
Expected: the log shows `requeued 1 orphaned task(s)`, `downloaded_bytes` continues from where it stopped rather than resetting to 0, and the task reaches `complete`.

- [ ] **Step 15: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): worker pool, retry policy and orphan recovery"
```

---

# Phase 5 — ffmpeg, file serving and task control

Ends with an MP3 and a muxed 4K file both downloadable and playable, and pause/resume/delete working.

### Task 17: ffmpeg wrapper

**Files:**
- Create: `api/src/lib/media/{__init__,ffmpeg}.py`
- Test: `api/tests/lib/media/__init__.py`, `api/tests/lib/media/test_ffmpeg.py`

**Interfaces:**
- Consumes: `src.core.error.Error`, `src.core.type.{Code, ErrorType}`
- Produces: `src.lib.media.ffmpeg.mux_args(ffmpeg, video, audio, destination) -> list[str]`, `mp3_args(ffmpeg, audio, destination) -> list[str]`, `async run(args: list[str]) -> None`

- [ ] **Step 1: Write the failing test**

`api/tests/lib/media/test_ffmpeg.py`:

```python
from pathlib import Path

import pytest

from src.core.error import Error
from src.lib.media.ffmpeg import mp3_args, mux_args, run


def test_mux_args_copies_both_streams_without_re_encoding() -> None:
    args = mux_args("ffmpeg", Path("/t/v.part"), Path("/t/a.part"), Path("/t/out.mp4"))
    assert args[0] == "ffmpeg"
    assert args.count("-i") == 2
    assert args[args.index("-i") + 1] == "/t/v.part"
    assert "-c" in args and args[args.index("-c") + 1] == "copy"
    assert args[-1] == "/t/out.mp4"


def test_mux_args_moves_the_index_to_the_front() -> None:
    args = mux_args("ffmpeg", Path("/t/v.part"), Path("/t/a.part"), Path("/t/out.mp4"))
    assert "-movflags" in args
    assert args[args.index("-movflags") + 1] == "+faststart"


def test_mux_args_overwrites_without_prompting() -> None:
    assert "-y" in mux_args("ffmpeg", Path("/t/v"), Path("/t/a"), Path("/t/o"))


def test_mp3_args_drop_video_and_encode_audio() -> None:
    args = mp3_args("ffmpeg", Path("/t/a.part"), Path("/t/out.mp3"))
    assert "-vn" in args
    assert args[args.index("-c:a") + 1] == "libmp3lame"
    assert args[args.index("-q:a") + 1] == "2"
    assert args[-1] == "/t/out.mp3"


def test_the_configured_binary_is_used() -> None:
    assert mp3_args("/opt/bin/ffmpeg", Path("/t/a"), Path("/t/o"))[0] == "/opt/bin/ffmpeg"


@pytest.mark.asyncio
async def test_run_raises_on_a_non_zero_exit() -> None:
    with pytest.raises(Error) as caught:
        await run(["python3", "-c", "import sys; sys.stderr.write('boom'); sys.exit(1)"])
    assert caught.value.message is not None
    assert "boom" in caught.value.message


@pytest.mark.asyncio
async def test_run_succeeds_quietly_on_a_zero_exit() -> None:
    await run(["python3", "-c", "pass"])


@pytest.mark.asyncio
async def test_run_reports_a_missing_binary_permanently() -> None:
    with pytest.raises(Error) as caught:
        await run(["definitely-not-a-real-binary-xyz"])
    assert caught.value.retry_able is False
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/media -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.lib.media'`

- [ ] **Step 3: Write `api/src/lib/media/ffmpeg.py`**

```python
"""ffmpeg invocation.

Argument construction is separated from execution so the arguments can be
tested without running anything. Both operations read local files, unlike the
Bun implementation which piped two remote URLs and had to keep them alive for
the length of the transcode.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType

_STDERR_TAIL = 2000


def mux_args(ffmpeg: str, video: Path, audio: Path, destination: Path) -> list[str]:
    """Combine a video-only and an audio-only file without re-encoding either."""
    return [
        ffmpeg,
        "-y",
        "-i", str(video),
        "-i", str(audio),
        "-c", "copy",
        "-movflags", "+faststart",
        str(destination),
    ]


def mp3_args(ffmpeg: str, audio: Path, destination: Path) -> list[str]:
    """Transcode an audio stream to MP3 at V2 (roughly 190 kbps VBR)."""
    return [
        ffmpeg,
        "-y",
        "-i", str(audio),
        "-vn",
        "-c:a", "libmp3lame",
        "-q:a", "2",
        str(destination),
    ]


async def run(args: list[str]) -> None:
    """Run ffmpeg, raising an ``Error`` carrying its stderr tail on failure.

    Retryable, and governed by ``MAX_ATTEMPTS`` like every other retryable
    failure: the common cause is a truncated input, which a re-download fixes.
    A missing binary is not retryable — no number of attempts installs ffmpeg.
    """
    try:
        process = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        logger.error("ffmpeg|run(): binary not found: {}", args[0])
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffmpeg not found at {args[0]!r}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
        ) from exc

    _, stderr = await process.communicate()
    if process.returncode != 0:
        tail = (stderr or b"").decode(errors="replace")[-_STDERR_TAIL:]
        logger.error("ffmpeg|run(): exit {} — {}", process.returncode, tail)
        raise Error.create(
            code=Code.INTERNAL_SERVER_ERROR,
            message=f"ffmpeg exited with {process.returncode}: {tail}",
            error_type=ErrorType.DEPENDENCY_FAILURE,
            retry_able=True,
        )
```

`api/src/lib/media/__init__.py`:
```python
from .ffmpeg import mp3_args as mp3_args
from .ffmpeg import mux_args as mux_args
from .ffmpeg import run as run
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/media -v
```
Expected: PASS, 8 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): ffmpeg wrapper"
```

---

### Task 18: ffmpeg post-processor

Replaces `SingleStreamPostProcessor` in the worker. The worker itself does not change — that is what the Protocol bought.

**Files:**
- Modify: `api/src/service/download/post_process.py`
- Modify: `api/src/service/__init__.py:build_worker_pool`
- Test: `api/tests/service/download/test_post_process.py` (rewrite)

**Interfaces:**
- Consumes: `src.lib.media.{mux_args, mp3_args, run}`, `src.config.get_settings`, `src.data.type.Kind`
- Produces: `src.service.download.post_process.FfmpegPostProcessor(ffmpeg: str, runner=media.run)` implementing `PostProcessor`

- [ ] **Step 1: Rewrite the test**

Replace `api/tests/service/download/test_post_process.py` entirely:

```python
from pathlib import Path
from types import SimpleNamespace

import pytest

from src.core.error import Error
from src.data.type import Kind
from src.service.download.post_process import FfmpegPostProcessor


class SpyRunner:
    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    async def __call__(self, args: list[str]) -> None:
        self.calls.append(args)
        # Stand in for ffmpeg writing its output.
        Path(args[-1]).write_bytes(b"encoded")


@pytest.mark.asyncio
async def test_a_single_part_is_renamed_without_calling_ffmpeg(tmp_path: Path) -> None:
    part = tmp_path / "video.part"
    part.write_bytes(b"data")
    destination = tmp_path / "clip.mp4"
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=None, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"video": part}, destination)

    assert runner.calls == []
    assert destination.read_bytes() == b"data"
    assert not part.exists()


@pytest.mark.asyncio
async def test_a_direct_download_part_is_also_just_renamed(tmp_path: Path) -> None:
    part = tmp_path / "file.part"
    part.write_bytes(b"data")
    destination = tmp_path / "archive.zip"

    task = SimpleNamespace(kind=Kind.FILE, audio_itag=None, video_itag=None)
    await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(task, {"file": part}, destination)

    assert destination.read_bytes() == b"data"


@pytest.mark.asyncio
async def test_video_plus_audio_is_muxed(tmp_path: Path) -> None:
    video, audio = tmp_path / "video.part", tmp_path / "audio.part"
    video.write_bytes(b"v")
    audio.write_bytes(b"a")
    destination = tmp_path / "clip.mp4"
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=140, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"video": video, "audio": audio}, destination)

    assert len(runner.calls) == 1
    assert "-c" in runner.calls[0]
    assert destination.read_bytes() == b"encoded"


@pytest.mark.asyncio
async def test_muxing_removes_both_parts(tmp_path: Path) -> None:
    video, audio = tmp_path / "video.part", tmp_path / "audio.part"
    video.write_bytes(b"v")
    audio.write_bytes(b"a")

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=140, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(
        task, {"video": video, "audio": audio}, tmp_path / "clip.mp4"
    )

    assert not video.exists()
    assert not audio.exists()


@pytest.mark.asyncio
async def test_audio_is_transcoded_to_mp3(tmp_path: Path) -> None:
    audio = tmp_path / "audio.part"
    audio.write_bytes(b"a")
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.AUDIO, audio_itag=140, video_itag=None)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"audio": audio}, tmp_path / "clip.mp3")

    assert len(runner.calls) == 1
    assert "libmp3lame" in runner.calls[0]


@pytest.mark.asyncio
async def test_no_parts_at_all_is_an_error(tmp_path: Path) -> None:
    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=None, video_itag=137)
    with pytest.raises(Error):
        await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(task, {}, tmp_path / "clip.mp4")
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download/test_post_process.py -v
```
Expected: FAIL — `ImportError: cannot import name 'FfmpegPostProcessor'`

- [ ] **Step 3: Rewrite `api/src/service/download/post_process.py`**

Keep the `PostProcessor` Protocol exactly as it is, delete `SingleStreamPostProcessor`, and add:

```python
from collections.abc import Awaitable, Callable

from src.core.error import Error
from src.data.type import Kind
from src.lib import media

Runner = Callable[[list[str]], Awaitable[None]]


class FfmpegPostProcessor(PostProcessor):
    """Turns the downloaded parts into the finished file.

    A single part needs no ffmpeg at all — it is already the file, so it is
    renamed into place. That covers combined YouTube streams and every direct
    download, which is the majority of traffic.
    """

    def __init__(self, ffmpeg: str, runner: Runner = media.run) -> None:
        self._ffmpeg = ffmpeg
        self._run = runner

    async def run(self, task: Any, parts: dict[str, Path], destination: Path) -> None:
        if not parts:
            raise Error.internal(message="Nothing was downloaded")

        if len(parts) == 1:
            next(iter(parts.values())).replace(destination)
            return

        video, audio = parts.get("video"), parts.get("audio")
        if video is not None and audio is not None:
            await self._run(media.mux_args(self._ffmpeg, video, audio, destination))
            video.unlink(missing_ok=True)
            audio.unlink(missing_ok=True)
            return

        raise Error.internal(message=f"Cannot combine parts: {sorted(parts)}")
```

Note the MP3 path: an MP3 task has exactly one part, so it would be renamed rather than transcoded by the branch above. Guard it explicitly by putting this check **before** the single-part branch:

```python
        if task.kind == Kind.AUDIO:
            audio = parts.get("audio")
            if audio is None:
                raise Error.internal(message="Audio task has no audio part")
            await self._run(media.mp3_args(self._ffmpeg, audio, destination))
            audio.unlink(missing_ok=True)
            return
```

- [ ] **Step 4: Swap it into the pool**

In `api/src/service/__init__.py`, change the `build_worker_pool` import and construction from `SingleStreamPostProcessor()` to `FfmpegPostProcessor(settings.ffmpeg_path)`.

- [ ] **Step 5: Update the worker integration test**

In `api/tests/integration/test_worker.py`, replace the `SingleStreamPostProcessor()` in `_worker` with `FfmpegPostProcessor("ffmpeg")` and delete `test_a_mux_plan_is_refused_permanently` — muxing is supported now, and Step 7's manual checkpoint covers it end to end.

- [ ] **Step 6: Run every test**

```bash
cd api && uv run pytest tests -v && uv run pytest tests/integration -m integration -v
```
Expected: both green

- [ ] **Step 7: Verify an MP3 and a muxed download**

```bash
cd api && make restart && sleep 6
for PRESET in mp3 1080; do
  curl -s -X POST localhost:8000/download/youtube \
    -H 'content-type: application/json' \
    -d "{\"url\":\"https://www.youtube.com/watch?v=aqz-KE-bpKQ\",\"preset\":\"$PRESET\"}" > /dev/null
done
sleep 45
curl -s localhost:8000/download | python3 -c '
import json, sys
for task in json.load(sys.stdin)["data"]:
    print(task["preset"], task["status"], task["progress"], task["file_size"], task["error"])
'
docker exec server-anydm-api ls -la /workdir/downloads/*/
```
Expected: both `complete`, both with a real `file_size`, and the two files present. Copy one out and play it to confirm it is not truncated:
```bash
docker cp server-anydm-api:/workdir/downloads /tmp/anydm-check && open /tmp/anydm-check
```

- [ ] **Step 8: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): ffmpeg muxing and MP3 transcoding"
```

---

### Task 19: Serve the finished file

**Files:**
- Modify: `api/src/service/download/download_service.py`, `api/src/route/download/download.py`
- Test: `api/tests/service/download/test_download_service.py` (extend)

**Interfaces:**
- Consumes: `src.service.download.paths.task_dir`, `starlette.responses.FileResponse`
- Produces: `DownloadService.resolve_file(task_id) -> tuple[Path, str, str]` returning `(path, filename, media_type)`

- [ ] **Step 1: Write the failing test**

Append to `api/tests/service/download/test_download_service.py`:

```python
@pytest.mark.asyncio
async def test_resolve_file_rejects_an_incomplete_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING, file_path=None)

    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code.value == 409


@pytest.mark.asyncio
async def test_resolve_file_returns_the_path_for_a_complete_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    target = tmp_path / str(task_id) / "clip.mp4"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"x")
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/clip.mp4", filename="clip.mp4"
    )

    path, filename, media_type = await service.resolve_file(task_id)
    assert path == target
    assert filename == "clip.mp4"
    assert media_type == "video/mp4"


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_row_is_gone(tmp_path: Path) -> None:
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.resolve_file(uuid.uuid4())
    assert caught.value.code.value == 404


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_file_vanished(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.COMPLETE, file_path=f"{task_id}/gone.mp4", filename="gone.mp4"
    )
    with pytest.raises(Error) as caught:
        await service.resolve_file(task_id)
    assert caught.value.code.value == 404
```

This needs the fake repo to answer reads. Extend `FakeRepo` in that file:

```python
class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.rows: dict[uuid.UUID, Any] = {}

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()

    async def get_active_by_id(self, task_id: uuid.UUID) -> Any:
        return self.rows.get(task_id)
```

and add the row factory plus a `downloads_dir` parameter to `_service`:

```python
def _row(task_id: uuid.UUID, **overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "id": task_id,
        "source_url": "https://youtu.be/x",
        "platform": Platform.YOUTUBE,
        "video_id": "x",
        "preset": Preset.BEST,
        "kind": Kind.VIDEO,
        "title": "clip",
        "filename": "clip.mp4",
        "mime_type": "video/mp4",
        "status": TaskStatus.PENDING,
        "progress": 0,
        "downloaded_bytes": 0,
        "total_bytes": None,
        "speed_bps": 0,
        "eta_seconds": None,
        "file_path": None,
        "file_size": None,
        "error": None,
        "error_code": None,
        "attempts": 0,
        "created_at": None,
        "started_at": None,
        "completed_at": None,
    }
    fields.update(overrides)
    return type("Row", (), fields)()


def _service(downloads_dir: Path | None = None) -> tuple[DownloadService, FakeRepo]:
    repo = FakeRepo()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        client=FakeClient(),  # ty: ignore[invalid-argument-type]
        control=DownloadControl(),
        downloads_root=downloads_dir or Path("/tmp/anydm-test"),
    )
    return service, repo
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download/test_download_service.py -v
```
Expected: FAIL — `TypeError: DownloadService.__init__() got an unexpected keyword argument 'downloads_root'`

- [ ] **Step 3: Add `resolve_file` to the service**

Add `downloads_root: Path` to `DownloadService.__init__`, store it as `self._root`, and add:

```python
    async def resolve_file(self, task_id: uuid.UUID) -> tuple[Path, str, str]:
        """The finished file for ``task_id``.

        409 rather than 404 while a task is still running: the resource will
        exist, just not yet — which is what the Bun API said for a verifying
        torrent, and what a polling client needs to tell "wait" from "never".
        """
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")

        if task.status != TaskStatus.COMPLETE or not task.file_path:
            raise Error.conflict(message=f"Task is {task.status.value}, not complete")

        path = self._root / task.file_path
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")

        return path, task.filename, task.mime_type or "application/octet-stream"
```

Update `get_download_service()` to pass `downloads_root=Path(get_settings().downloads_dir)`.

- [ ] **Step 4: Add the route**

Append to `api/src/route/download/download.py`:

```python
@router.get(path="/{task_id}/file")
async def download_file(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> FileResponse:
    """Serve the finished file.

    ``FileResponse`` handles Range on its own, so a browser download that drops
    resumes instead of restarting.
    """
    path, filename, media_type = await download_service.resolve_file(task_id)
    return FileResponse(path=path, filename=filename, media_type=media_type)
```

with `from fastapi.responses import FileResponse` added to the imports.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests -v
```
Expected: PASS, all green

- [ ] **Step 6: Verify Range support against a real file**

```bash
cd api && make restart && sleep 6
TASK=$(curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=aqz-KE-bpKQ","preset":"720"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
sleep 30
curl -s -D- -o /dev/null -r 0-99 "localhost:8000/download/$TASK/file" | head -8
```
Expected: `HTTP/1.1 206 Partial Content` with `content-range: bytes 0-99/<total>` and `content-length: 100`

- [ ] **Step 7: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): serve finished files with Range support"
```

---

### Task 20: Pause, resume and delete

**Files:**
- Modify: `api/src/service/download/download_service.py`, `api/src/route/download/download.py`
- Test: `api/tests/service/download/test_download_service.py` (extend)

**Interfaces:**
- Consumes: `src.service.download.control.DownloadControl`, `src.service.download.download_worker.remove_task_files`
- Produces: `DownloadService.pause(task_id) -> TaskSchema`, `.resume(task_id) -> TaskSchema`, `.cancel(task_id) -> None`

- [ ] **Step 1: Write the failing test**

Append to `api/tests/service/download/test_download_service.py`:

```python
@pytest.mark.asyncio
async def test_pause_stops_a_running_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    result = await service.pause(task_id)

    assert result.status == TaskStatus.PAUSED
    assert service._control.is_stopping(task_id) is True


@pytest.mark.asyncio
async def test_pause_also_works_on_a_queued_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PENDING)
    assert (await service.pause(task_id)).status == TaskStatus.PAUSED


@pytest.mark.asyncio
async def test_pause_rejects_a_completed_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.COMPLETE)

    with pytest.raises(Error) as caught:
        await service.pause(task_id)
    assert caught.value.code.value == 409


@pytest.mark.asyncio
async def test_resume_requeues_a_paused_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.PAUSED)
    service._control.request_stop(task_id)

    result = await service.resume(task_id)

    assert result.status == TaskStatus.PENDING
    assert service._control.is_stopping(task_id) is False


@pytest.mark.asyncio
async def test_resume_clears_the_failure_state(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(
        task_id, status=TaskStatus.FAILED, error="boom", error_code="dependency_failure", attempts=3
    )

    result = await service.resume(task_id)

    assert result.status == TaskStatus.PENDING
    assert result.error is None
    assert result.error_code is None
    assert result.attempts == 0


@pytest.mark.asyncio
async def test_resume_rejects_a_running_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await service.resume(task_id)
    assert caught.value.code.value == 409


@pytest.mark.asyncio
async def test_cancel_stops_the_task_and_removes_its_files(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    task_id = uuid.uuid4()
    (tmp_path / str(task_id)).mkdir(parents=True)
    (tmp_path / str(task_id) / "video.part").write_bytes(b"x")
    repo.rows[task_id] = _row(task_id, status=TaskStatus.DOWNLOADING)

    await service.cancel(task_id)

    assert not (tmp_path / str(task_id)).exists()
    assert repo.rows[task_id].status == TaskStatus.CANCELED
    assert repo.rows[task_id].deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_404s_on_an_unknown_task(tmp_path: Path) -> None:
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.cancel(uuid.uuid4())
    assert caught.value.code.value == 404
```

The fake rows need a `save()` and a `deleted_at`. Extend `_row`'s field dict with `"deleted_at": None` and give the generated class a save method by replacing the return line with:

```python
    row = type("Row", (), fields)()
    async def _save(*_args: Any, **_kwargs: Any) -> None:
        return None
    row.save = _save
    return row
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/service/download/test_download_service.py -v
```
Expected: FAIL — `AttributeError: 'DownloadService' object has no attribute 'pause'`

- [ ] **Step 3: Add the three methods to `DownloadService`**

```python
    async def pause(self, task_id: uuid.UUID) -> TaskSchema:
        """Signal a running transfer to stop between chunks, keeping the ``.part``.

        The status is written here rather than by the worker so the caller's
        next read reflects the pause immediately, even if the worker is mid-chunk.
        """
        task = await self._require(task_id)
        if task.status not in (TaskStatus.PENDING, TaskStatus.DOWNLOADING):
            raise Error.conflict(message=f"Cannot pause a task that is {task.status.value}")

        self._control.request_stop(task_id)
        task.status = TaskStatus.PAUSED
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save()
        return TaskSchema.model_validate(task)

    async def resume(self, task_id: uuid.UUID) -> TaskSchema:
        """Put a paused or failed task back in the queue, from where its bytes stopped.

        ``attempts`` resets because this is a fresh decision by a person, not a
        continuation of the automatic retry budget that gave up.
        """
        task = await self._require(task_id)
        if task.status not in (TaskStatus.PAUSED, TaskStatus.FAILED):
            raise Error.conflict(message=f"Cannot resume a task that is {task.status.value}")

        self._control.clear_stop(task_id)
        task.status = TaskStatus.PENDING
        task.error = None
        task.error_code = None
        task.attempts = 0
        task.next_attempt_at = None
        await task.save()
        self._control.wake()
        return TaskSchema.model_validate(task)

    async def cancel(self, task_id: uuid.UUID) -> None:
        """Stop the task, delete its files, and soft-delete the row."""
        task = await self._require(task_id)
        self._control.request_stop(task_id)
        remove_task_files(self._root, task_id)
        task.status = TaskStatus.CANCELED
        task.deleted_at = now()
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save()

    async def _require(self, task_id: uuid.UUID) -> Any:
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")
        return task
```

Add the imports `from typing import Any`, `from src.core.common import now`, `from src.service.download.download_worker import remove_task_files`, and refactor `get_task` and `resolve_file` to call `self._require`.

- [ ] **Step 4: Add the routes**

Append to `api/src/route/download/download.py`:

```python
@router.post(path="/{task_id}/pause", response_model=Success[TaskSchema])
async def pause_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.pause(task_id)).to_resp()


@router.post(path="/{task_id}/resume", response_model=Success[TaskSchema])
async def resume_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.resume(task_id)).to_resp()


@router.delete(path="/{task_id}")
async def cancel_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    await download_service.cancel(task_id)
    return Success(code=Code.NO_CONTENT).to_resp()
```

with `from src.core.type import Code` added to the imports.

Route order matters: FastAPI matches in declaration order, so `/{task_id}/file`, `/{task_id}/pause` and `/{task_id}/resume` must all be declared **before** the bare `/{task_id}` GET, or the parameterised route swallows them. Verify by reading the file top to bottom after the edit.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests -v
```
Expected: PASS, all green

- [ ] **Step 6: Verify against the running stack**

```bash
cd api && make restart && sleep 6
TASK=$(curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=aqz-KE-bpKQ","preset":"1080"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
sleep 4
curl -s -X POST "localhost:8000/download/$TASK/pause" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["status"])'
sleep 2
curl -s -X POST "localhost:8000/download/$TASK/resume" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["status"])'
sleep 15
curl -s "localhost:8000/download/$TASK" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d["status"], d["progress"])'
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "localhost:8000/download/$TASK"
docker exec server-anydm-api ls /workdir/downloads/
```
Expected: `paused`, then `pending`, then the task resumes and progresses (not restarting from 0), then `204`, and the task's directory is gone.

- [ ] **Step 7: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): pause, resume and cancel endpoints"
```

---

### Task 21: Direct URL download

Closes the live bug: the UI calls `/download/url` (`apps/ui/src/route/index.tsx:242`) against a route that has never existed.

**Files:**
- Modify: `api/src/data/schema/download/download.py`, `api/src/service/download/download_service.py`
- Modify: `api/src/service/download/download_worker.py`, `api/src/route/download/download.py`
- Test: `api/tests/service/download/test_download_service.py` (extend), `api/tests/service/download/test_direct.py`

**Interfaces:**
- Consumes: `src.data.type.{Platform, Kind, Preset}`
- Produces: `src.data.schema.download.UrlDownloadRequest` — `url: str`; `DownloadService.enqueue_url(url) -> TaskSchema`; `src.service.download.direct.filename_from_url(url: str) -> str`

- [ ] **Step 1: Write the failing test**

`api/tests/service/download/test_direct.py`:

```python
import pytest

from src.service.download.direct import filename_from_url


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://cdn.test/files/report.pdf", "report.pdf"),
        ("https://cdn.test/files/report.pdf?token=abc", "report.pdf"),
        ("https://cdn.test/a/b/c/archive.tar.gz", "archive.tar.gz"),
        ("https://cdn.test/files/my%20file.zip", "my_file.zip"),
        ("https://cdn.test/", "download"),
        ("https://cdn.test", "download"),
        ("https://cdn.test/files/", "download"),
        ("https://cdn.test/../../etc/passwd", "passwd"),
    ],
)
def test_filename_from_url(url: str, expected: str) -> None:
    assert filename_from_url(url) == expected
```

Append to `api/tests/service/download/test_download_service.py`:

```python
@pytest.mark.asyncio
async def test_enqueue_url_writes_a_direct_task(tmp_path: Path) -> None:
    service, repo = _service(downloads_dir=tmp_path)
    await service.enqueue_url("https://cdn.test/files/report.pdf")

    row = repo.created[0]
    assert row["platform"] == Platform.DIRECT
    assert row["kind"] == Kind.FILE
    assert row["filename"] == "report.pdf"
    assert row["video_itag"] is None
    assert row["audio_itag"] is None
    assert row["video_id"] is None
    assert row["status"] == TaskStatus.PENDING


@pytest.mark.asyncio
async def test_enqueue_url_rejects_a_non_http_scheme(tmp_path: Path) -> None:
    service, _ = _service(downloads_dir=tmp_path)
    with pytest.raises(Error) as caught:
        await service.enqueue_url("file:///etc/passwd")
    assert caught.value.code.value == 400
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd api && uv run pytest tests/service/download -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.service.download.direct'`

- [ ] **Step 3: Write `api/src/service/download/direct.py`**

```python
"""Plain HTTP downloads — anything that is not a media platform."""

from __future__ import annotations

import re
from pathlib import PurePosixPath
from urllib.parse import unquote, urlparse

from src.core.error import Error
from src.core.type import Code, ErrorType

_UNSAFE = re.compile(r"[^\w.\-]", re.UNICODE)
_ALLOWED_SCHEMES = frozenset({"http", "https"})


def ensure_fetchable(url: str) -> None:
    """Reject anything the downloader has no business fetching.

    ``file://`` in particular: this endpoint takes a URL from a browser, and
    handing that straight to a client that would read the local filesystem is
    how a download manager becomes a file-disclosure endpoint.
    """
    if urlparse(url).scheme not in _ALLOWED_SCHEMES:
        raise Error.create(
            code=Code.BAD_REQUEST,
            message="Only http and https URLs can be downloaded",
            error_type=ErrorType.UNSUPPORTED_OPERATION,
        )


def filename_from_url(url: str) -> str:
    """A safe filename from the URL's last path segment.

    ``PurePosixPath(...).name`` drops every directory component, so a path
    walking upward cannot escape the task directory.
    """
    path = unquote(urlparse(url).path)
    stem = PurePosixPath(path).name
    cleaned = _UNSAFE.sub("_", stem).strip("._")
    return cleaned or "download"
```

- [ ] **Step 4: Add `enqueue_url` to the service**

```python
    async def enqueue_url(self, url: str) -> TaskSchema:
        ensure_fetchable(url)
        task = await self._repo.create(
            source_url=url,
            platform=Platform.DIRECT,
            video_id=None,
            preset=Preset.BEST,
            kind=Kind.FILE,
            title=filename_from_url(url),
            filename=filename_from_url(url),
            mime_type=None,
            video_itag=None,
            audio_itag=None,
            status=TaskStatus.PENDING,
            progress=0,
        )
        self._control.wake()
        return TaskSchema.model_validate(task)
```

`preset` is `BEST` because the column is not nullable and no preset applies to an arbitrary file; `kind=FILE` is what actually says "this has no quality dimension".

Add `from src.service.download.direct import ensure_fetchable, filename_from_url` and `Kind` to the imports.

- [ ] **Step 5: Teach the worker about direct tasks**

In `_download_parts`, insert before the itag collection:

```python
        if task.platform == Platform.DIRECT:
            destination = part_path(self._root, task.id, "file")
            resume_from = destination.stat().st_size if destination.exists() else 0
            await self._downloader.fetch(
                task.source_url,
                destination,
                resume_from=resume_from,
                on_sample=lambda sample, task_id=task.id: self._flush(task_id, sample),
                should_stop=lambda task_id=task.id: self._control.is_stopping(task_id),
            )
            return {"file": destination}
```

with `Platform` added to the `src.data.type` import.

- [ ] **Step 6: Add the schema and route**

In `api/src/data/schema/download/download.py`:

```python
class UrlDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A direct http or https URL")]
```

export it from the package `__init__`, and add the route **above** the `/{task_id}` routes:

```python
@router.post(path="/url", response_model=Success[TaskSchema])
async def enqueue_url(
    payload: UrlDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.enqueue_url(payload.url.strip())
    return Success.created(data=data).to_resp()
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd api && uv run pytest tests -v && uv run pytest tests/integration -m integration -v
```
Expected: both green

- [ ] **Step 8: Verify a direct download**

```bash
cd api && make restart && sleep 6
TASK=$(curl -s -X POST localhost:8000/download/url \
  -H 'content-type: application/json' \
  -d '{"url":"https://raw.githubusercontent.com/torvalds/linux/master/README"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
sleep 5
curl -s "localhost:8000/download/$TASK" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d["status"], d["filename"], d["file_size"])'
curl -s "localhost:8000/download/$TASK/file" | head -3
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8000/download/url \
  -H 'content-type: application/json' -d '{"url":"file:///etc/passwd"}'
```
Expected: `complete README <size>`, the README's first lines, then `400` for the `file://` attempt.

- [ ] **Step 9: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): direct URL downloads"
```

---

# Phase 6 — Server-sent events

Ends with `curl -N localhost:8000/download/events` printing live progress.

### Task 22: Event hub

A port of `apps/api/src/service/torrent/events.ts`, with one change: the queues are bounded, so a browser that stops reading drops frames instead of stalling a worker.

**Files:**
- Create: `api/src/lib/event/{__init__,hub}.py`
- Test: `api/tests/lib/event/__init__.py`, `api/tests/lib/event/test_hub.py`

**Interfaces:**
- Consumes: nothing
- Produces: `src.lib.event.hub.EventHub(max_queue: int = 100)` with `subscribe() -> Subscription`, `publish(event: str, data: Any) -> None`, `subscriber_count() -> int`; `Subscription` — async iterator yielding `(event, data)` tuples, with `close()`; and `src.lib.event.get_event_hub() -> EventHub` (lru_cached)

- [ ] **Step 1: Write the failing test**

`api/tests/lib/event/test_hub.py`:

```python
import asyncio

import pytest

from src.lib.event.hub import EventHub


@pytest.mark.asyncio
async def test_a_subscriber_receives_a_published_event() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    hub.publish("task", {"id": 1})

    event, data = await asyncio.wait_for(anext(aiter(subscription)), timeout=1)

    assert event == "task"
    assert data == {"id": 1}
    subscription.close()


@pytest.mark.asyncio
async def test_every_subscriber_receives_the_same_event() -> None:
    hub = EventHub()
    first, second = hub.subscribe(), hub.subscribe()
    hub.publish("stats", {"speed": 5})

    assert (await anext(aiter(first)))[1] == {"speed": 5}
    assert (await anext(aiter(second)))[1] == {"speed": 5}
    first.close()
    second.close()


@pytest.mark.asyncio
async def test_publishing_with_no_subscribers_is_harmless() -> None:
    EventHub().publish("task", {"id": 1})


@pytest.mark.asyncio
async def test_subscriber_count_tracks_open_subscriptions() -> None:
    hub = EventHub()
    assert hub.subscriber_count() == 0
    subscription = hub.subscribe()
    assert hub.subscriber_count() == 1
    subscription.close()
    assert hub.subscriber_count() == 0


@pytest.mark.asyncio
async def test_a_full_queue_drops_the_oldest_frame_instead_of_blocking() -> None:
    hub = EventHub(max_queue=2)
    subscription = hub.subscribe()

    for index in range(5):
        hub.publish("task", {"id": index})

    received = [(await anext(aiter(subscription)))[1] for _ in range(2)]

    # The two most recent survive; the earlier three were dropped.
    assert received == [{"id": 3}, {"id": 4}]
    subscription.close()


@pytest.mark.asyncio
async def test_a_closed_subscription_stops_iterating() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    subscription.close()

    with pytest.raises(StopAsyncIteration):
        await anext(aiter(subscription))


@pytest.mark.asyncio
async def test_publishing_to_a_closed_subscription_does_not_raise() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    subscription.close()
    hub.publish("task", {"id": 1})
    assert hub.subscriber_count() == 0
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/lib/event -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'src.lib.event'`

- [ ] **Step 3: Write `api/src/lib/event/hub.py`**

```python
"""In-process pub/sub for server-sent events.

Workers and SSE subscribers share a process, so a progress update reaches the
browser without a database round trip — this is the thing the in-process worker
pool buys that a separate worker container could not.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from functools import lru_cache
from typing import Any

_DEFAULT_MAX_QUEUE = 100


class Subscription:
    def __init__(self, hub: EventHub, max_queue: int) -> None:
        self._hub = hub
        self._queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue(maxsize=max_queue)
        self._closed = False

    def _offer(self, event: str, data: Any) -> None:
        """Enqueue, dropping the oldest frame when the reader has fallen behind.

        Never blocks and never raises: a slow browser must not be able to hold
        up a download. Progress frames are snapshots, so losing an old one
        costs nothing — the next one carries the current state anyway.
        """
        if self._closed:
            return
        if self._queue.full():
            try:
                self._queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            self._queue.put_nowait((event, data))
        except asyncio.QueueFull:
            pass

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._hub._remove(self)

    def __aiter__(self) -> AsyncIterator[tuple[str, Any]]:
        return self._iterate()

    async def _iterate(self) -> AsyncIterator[tuple[str, Any]]:
        while True:
            if self._closed and self._queue.empty():
                return
            yield await self._queue.get()


class EventHub:
    def __init__(self, max_queue: int = _DEFAULT_MAX_QUEUE) -> None:
        self._subscribers: set[Subscription] = set()
        self._max_queue = max_queue

    def subscribe(self) -> Subscription:
        subscription = Subscription(self, self._max_queue)
        self._subscribers.add(subscription)
        return subscription

    def publish(self, event: str, data: Any) -> None:
        for subscription in tuple(self._subscribers):
            subscription._offer(event, data)

    def subscriber_count(self) -> int:
        return len(self._subscribers)

    def _remove(self, subscription: Subscription) -> None:
        self._subscribers.discard(subscription)


@lru_cache
def get_event_hub() -> EventHub:
    return EventHub()
```

`api/src/lib/event/__init__.py`:
```python
from .hub import EventHub as EventHub
from .hub import Subscription as Subscription
from .hub import get_event_hub as get_event_hub
```

A closed subscription whose queue is empty must end iteration rather than hang. Since `_iterate` awaits `self._queue.get()`, `close()` also needs to unblock a waiting reader — add a sentinel push at the end of `close()`:

```python
        with contextlib.suppress(asyncio.QueueFull):
            self._queue.put_nowait(("__closed__", None))
```
and skip that sentinel in `_iterate`:
```python
            event, data = await self._queue.get()
            if event == "__closed__":
                return
            yield event, data
```
with `import contextlib` added.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd api && uv run pytest tests/lib/event -v
```
Expected: PASS, 7 passed

- [ ] **Step 5: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): in-process event hub"
```

---

### Task 23: SSE endpoint and worker publishing

**Files:**
- Modify: `api/src/service/download/download_worker.py`, `api/src/service/download/download_service.py`
- Modify: `api/src/service/__init__.py`, `api/src/route/download/download.py`
- Test: `api/tests/integration/test_events.py`

**Interfaces:**
- Consumes: `src.lib.event.{EventHub, get_event_hub}`, `sse_starlette.EventSourceResponse`
- Produces: `GET /download/events` streaming `tasks` (once, on connect) then `task` events; `DownloadWorker` and `DownloadService` both take `hub: EventHub`

- [ ] **Step 1: Write the failing test**

`api/tests/integration/test_events.py`:

```python
import asyncio
import json

import httpx
import pytest

from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.downloader import Downloader
from src.service.download.post_process import FfmpegPostProcessor

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def test_the_worker_publishes_progress_and_completion(db: None, tmp_path) -> None:
    task = await Task.create(
        source_url="https://cdn.test/f",
        platform=Platform.DIRECT,
        preset=Preset.BEST,
        kind=Kind.FILE,
        status=TaskStatus.PENDING,
        title="f",
        filename="f.bin",
    )
    hub = EventHub()
    subscription = hub.subscribe()
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=b"y" * 500))

    async with httpx.AsyncClient(transport=transport) as client:
        worker = DownloadWorker(
            name="w",
            repo=TaskDatabaseRepo(),
            client=None,  # ty: ignore[invalid-argument-type]
            downloader=Downloader(client, chunk_size=64, flush_interval_ms=0),
            post_processor=FfmpegPostProcessor("ffmpeg"),
            control=DownloadControl(),
            hub=hub,
            downloads_root=tmp_path,
            max_attempts=3,
        )
        claimed = await TaskDatabaseRepo().claim_next()
        assert claimed is not None
        await worker._run_task(claimed)

    events = []
    while hub.subscriber_count() and not subscription._queue.empty():
        events.append(await subscription._queue.get())

    names = [name for name, _ in events]
    assert "task" in names
    payloads = [payload for name, payload in events if name == "task"]
    assert payloads[-1]["status"] == TaskStatus.COMPLETE
    assert payloads[-1]["progress"] == 100
    subscription.close()
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd api && uv run pytest tests/integration/test_events.py -m integration -v
```
Expected: FAIL — `TypeError: DownloadWorker.__init__() got an unexpected keyword argument 'hub'`

- [ ] **Step 3: Publish from the worker**

Add `hub: EventHub` to `DownloadWorker.__init__` (stored as `self._hub`), and publish a task snapshot wherever the row changes. Add this helper:

```python
    def _emit(self, task: Task) -> None:
        self._hub.publish("task", TaskSchema.model_validate(task).to_json())
```

Call it at the end of `_mark_complete` and `_mark_failed`, and after the pause unwind in `_run_task`. In `_flush`, publish the sample too — it is the only place progress is known:

```python
    async def _flush(self, task_id: object, sample: ProgressSample) -> None:
        await self._repo.flush_progress(...)
        self._hub.publish(
            "progress",
            {
                "id": str(task_id),
                "downloaded_bytes": sample.downloaded_bytes,
                "total_bytes": sample.total_bytes,
                "progress": sample.progress,
                "speed_bps": sample.speed_bps,
                "eta_seconds": sample.eta_seconds,
            },
        )
```

Add `from src.data.schema.download import TaskSchema` and `from src.lib.event import EventHub` to the imports, and pass `hub=get_event_hub()` in `build_worker_pool`.

- [ ] **Step 4: Publish from the service**

Add `hub: EventHub` to `DownloadService.__init__` and publish `"task"` at the end of `enqueue_youtube`, `enqueue_url`, `pause`, `resume`, and `cancel`, so a change made through the API reaches every open browser without waiting for a worker. Pass `hub=get_event_hub()` in `get_download_service()`, and `hub=EventHub()` in the unit test's `_service` helper.

- [ ] **Step 5: Add the SSE route**

Append to `api/src/route/download/download.py`, **above** the `/{task_id}` routes:

```python
@router.get(path="/events")
async def stream_events(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    hub: Annotated[EventHub, Depends(get_event_hub)],
) -> EventSourceResponse:
    """Live task updates.

    A browser reconnects on its own, so every connection opens with the full
    list before any incremental event — otherwise a client that reconnected
    mid-download would show nothing until the next progress tick.
    """
    subscription = hub.subscribe()
    tasks, _ = await download_service.list_tasks(page=1, page_size=200)

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            yield {"event": "tasks", "data": json.dumps([task.to_json() for task in tasks])}
            async for event, data in subscription:
                yield {"event": event, "data": json.dumps(data)}
        finally:
            subscription.close()

    return EventSourceResponse(publisher(), ping=15)
```

with these imports added: `import json`, `from collections.abc import AsyncIterator`, `from sse_starlette import EventSourceResponse`, `from src.lib.event import EventHub, get_event_hub`.

`ping=15` is sse-starlette's own comment heartbeat, which is what keeps a proxy from reaping an idle connection.

- [ ] **Step 6: Run every test**

```bash
cd api && uv run pytest tests -v && uv run pytest tests/integration -m integration -v
```
Expected: both green

- [ ] **Step 7: Watch a live download**

```bash
cd api && make restart && sleep 6
curl -N -s localhost:8000/download/events &
SSE=$!
sleep 2
curl -s -X POST localhost:8000/download/youtube \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=aqz-KE-bpKQ","preset":"720"}' > /dev/null
sleep 25
kill $SSE
```
Expected: a `tasks` event on connect, then a stream of `progress` events with rising `downloaded_bytes`, then a `task` event with `"status": "complete"`

- [ ] **Step 8: Lint, typecheck, commit**

```bash
cd api && uvx ruff check --fix && uv run ty check
cd /Users/roman/projects/github/anydm
git add api && git commit -m "feat(api): SSE endpoint for live task updates"
```

---

# Phase 7 — Repository restructure

The first phase that touches anything outside `api/`. Ends with both halves building and CI green. `apps/api` is not touched — it keeps serving torrents.

### Task 24: Move the UI to `ui/apps/web`

**Files:**
- Move: `apps/ui/**` → `ui/apps/web/**`
- Create: `ui/package.json`, `ui/bunfig.toml`
- Delete: `package.json`, `bunfig.toml` (repository root)
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing
- Produces: `bun run --cwd ui web:dev`, `web:build`, `web:fmt`, `web:fmt.chk`, `web:chk`

- [ ] **Step 1: Move the app with git so history follows**

```bash
cd /Users/roman/projects/github/anydm
mkdir -p ui/apps
git mv apps/ui ui/apps/web
```

- [ ] **Step 2: Write `ui/package.json`**

This is the workspace root, mirroring `/Users/roman/projects/exateks/auth/ui/package.json`:

```json
{
    "name": "ui",
    "private": true,
    "version": "0.0.1",
    "description": "AnyDM UI",
    "type": "module",
    "workspaces": [
        "apps/*"
    ],
    "scripts": {
        "clean": "rm -rf node_modules bun.lock && bun run web:clean",
        "reinstall": "bun run clean && bun install",
        "web:clean": "bun run --cwd apps/web clean",
        "web:fmt": "bun run --cwd apps/web fmt",
        "web:fmt.chk": "bun run --cwd apps/web fmt.chk",
        "web:chk": "bun run --cwd apps/web chk",
        "web:dev": "bun run web:fmt && bun run web:chk && bun run --cwd apps/web dev",
        "web:build": "bun run --cwd apps/web build",
        "web:restart": "bun run reinstall && bun run web:dev",
        "web:start": "bun install && bun run web:dev"
    }
}
```

- [ ] **Step 3: Rename the app package and move bunfig**

```bash
cd /Users/roman/projects/github/anydm
git mv bunfig.toml ui/bunfig.toml
python3 - <<'PY'
import json, pathlib
path = pathlib.Path("ui/apps/web/package.json")
data = json.loads(path.read_text())
data["name"] = "web"
path.write_text(json.dumps(data, indent=4) + "\n")
PY
```

- [ ] **Step 4: Delete the old root workspace**

The root `package.json` declared `workspaces: ["apps/*"]` and delegated to both apps. `ui/` is now its own workspace root and `apps/api` is a standalone Bun project, so the root file has nothing left to own — the root makefile in Task 25 replaces it.

```bash
cd /Users/roman/projects/github/anydm
git rm package.json
```

- [ ] **Step 5: Give `apps/api` its own bunfig**

It relied on the root one, which just moved.

```bash
cd /Users/roman/projects/github/anydm
cp ui/bunfig.toml apps/api/bunfig.toml
git add apps/api/bunfig.toml
```

- [ ] **Step 6: Update `.gitignore`**

The root `.gitignore` currently ignores `bun.lock`. That has to change here, not later: there are now two lockfiles (`ui/bun.lock` and `apps/api/bun.lock`), and Task 26's CI runs `bun install --frozen-lockfile`, which fails outright without them committed.

Delete the `bun.lock` line from `.gitignore`. Leave `node_modules`, `dist` and `downloads` — none is anchored with a leading slash, so each still applies at any depth.

```bash
cd /Users/roman/projects/github/anydm
python3 - <<'PY'
import pathlib
path = pathlib.Path(".gitignore")
lines = [line for line in path.read_text().splitlines() if line.strip() != "bun.lock"]
path.write_text("\n".join(lines) + "\n")
PY
git add .gitignore
```

- [ ] **Step 7: Verify the UI builds from its new home**

```bash
cd /Users/roman/projects/github/anydm/ui && bun install
bun run web:fmt.chk && bun run web:chk && bun run web:build
```
Expected: all three succeed. If `web:chk` reports unresolved `@/component/...` imports, the path alias in `ui/apps/web/tsconfig.json` or `vite.config.ts` is relative to the old location — fix the alias base, not the imports.

- [ ] **Step 8: Verify `apps/api` still builds**

```bash
cd /Users/roman/projects/github/anydm/apps/api && bun install
bun run fmt.chk && bun run chk
```
Expected: both succeed. The Bun API must keep working — it is still serving torrents.

- [ ] **Step 9: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add -A
git commit -m "refactor: move apps/ui to ui/apps/web as its own workspace root"
```

---

### Task 25: Root makefile

**Files:**
- Create: `makefile` (repository root)

**Interfaces:**
- Consumes: `api/makefile`, `ui/package.json`
- Produces: `make api:run`, `make api:test`, `make api:check`, `make api:up`, `make api:down`, `make ui:dev`, `make ui:build`, `make ui:check`, `make check`, `make help`

- [ ] **Step 1: Write the root `makefile`**

```makefile
## core
# variables
API_DIR := api
UI_DIR := ui

# Every target with a colon in its name is escaped for GNU Make 3.81, which is
# what ships on macOS. Unescaped, `api:run` is parsed as target `api` with
# prerequisite `run` and silently does the wrong thing.
.PHONY: api\:install api\:run api\:test api\:check api\:up api\:down api\:logs api\:migrate ui\:install ui\:dev ui\:build ui\:check check help

## api
api\:install: # Install Python dependencies
	$(MAKE) -C $(API_DIR) install

api\:run: # Run the FastAPI dev server
	$(MAKE) -C $(API_DIR) run

api\:test: # Run the Python unit tests
	$(MAKE) -C $(API_DIR) test

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
```

- [ ] **Step 2: Verify every target resolves**

```bash
cd /Users/roman/projects/github/anydm
make help
make api:check
make ui:check
```
Expected: `make help` lists all fifteen targets with descriptions; both checks pass. If `make api:check` reports "No rule to make target", the escaping is wrong — compare against `api/makefile`'s own escaped seed targets in the auth reference.

- [ ] **Step 3: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add makefile && git commit -m "build: root makefile delegating to api and ui"
```

---

### Task 26: CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `api/`, `ui/`, `apps/api/`
- Produces: three CI jobs — `api`, `ui`, `legacy-api`

- [ ] **Step 1: Rewrite `.github/workflows/ci.yml`**

```yaml
name: CI

on:
    push:
        branches: [main]
    pull_request:

jobs:
    api:
        runs-on: ubuntu-latest
        defaults:
            run:
                working-directory: api
        steps:
            - uses: actions/checkout@v4
            - uses: astral-sh/setup-uv@v5
              with:
                  enable-cache: true
            - run: uv python install 3.14.6
            - run: uv sync --frozen
            - run: uvx ruff check
            - run: uv run ty check
            # Unit tests only. The integration suite needs Postgres and a disk,
            # and runs locally against `make up`.
            - run: uv run pytest

    ui:
        runs-on: ubuntu-latest
        defaults:
            run:
                working-directory: ui
        steps:
            - uses: actions/checkout@v4
            - uses: oven-sh/setup-bun@v2
            - run: bun install --frozen-lockfile
            - run: bun run web:fmt.chk
            - run: bun run web:chk
            - run: bun run web:build

    # The Bun API still serves torrents until that port lands. Delete this job
    # with apps/api itself.
    legacy-api:
        runs-on: ubuntu-latest
        defaults:
            run:
                working-directory: apps/api
        steps:
            - uses: actions/checkout@v4
            - uses: oven-sh/setup-bun@v2
            - run: bun install --frozen-lockfile
            - run: bun run fmt.chk
            - run: bun run chk
```

- [ ] **Step 2: Commit the lockfiles CI expects**

`--frozen-lockfile` and `--frozen` both fail without a committed lockfile. Task 24 Step 6 already un-ignored `bun.lock`; this generates `uv.lock` and tracks all three.

```bash
cd /Users/roman/projects/github/anydm/api && uv lock
cd /Users/roman/projects/github/anydm
git add api/uv.lock ui/bun.lock apps/api/bun.lock
git status --short
```
Expected: all three lockfiles staged. If any shows as ignored, `.gitignore` still carries the `bun.lock` line — fix it as Task 24 Step 6 describes rather than forcing the add.

- [ ] **Step 3: Push and confirm CI is green**

```bash
cd /Users/roman/projects/github/anydm
git add -A && git commit -m "ci: build and check api, ui and the legacy Bun API"
git push
gh run watch
```
Expected: all three jobs pass.

- [ ] **Step 4: If the `api` job fails on the Python version**

`astral-sh/setup-uv` may not have a 3.14.6 build cached. Replace `uv python install 3.14.6` with:
```yaml
            - uses: actions/setup-python@v5
              with:
                  python-version: "3.14"
```
and keep `uv sync --frozen`. Re-push and re-check.

---

# Phase 8 — UI switchover

Ends with extract, YouTube download and direct download all served by FastAPI, torrents still served by the Bun API, and both visible in one list.

### Task 27: API client module

The UI now talks to two services with two envelopes. This module is where that lives, so no component learns about it.

**Files:**
- Create: `ui/apps/web/src/lib/api/{client.ts,envelope.ts,task.ts,index.ts}`
- Modify: `ui/apps/web/.env.example`
- Test: `ui/apps/web/src/lib/api/envelope.test.ts`, `ui/apps/web/src/lib/api/task.test.ts`

**Interfaces:**
- Consumes: `import.meta.env.PUBLIC_API_URL`, `import.meta.env.PUBLIC_BASE_URL`
- Produces:
  - `unwrap<T>(payload: unknown): T` — throws on a failure envelope, handles both shapes
  - `normalizeApiTask(raw): UiTask`, `normalizeBunTask(raw): UiTask`
  - `apiUrl(path): string`, `bunUrl(path): string`
  - `getApi<T>(path)`, `postApi<T>(path, body)`, `deleteApi(path)`

- [ ] **Step 1: Write the failing test**

`ui/apps/web/src/lib/api/envelope.test.ts`:

```ts
import { describe, expect, it } from "bun:test";

import { unwrap } from "./envelope";

describe("unwrap", () => {
    it("returns data from a FastAPI success envelope", () => {
        expect(unwrap({ status: "success", code: 200, data: { id: "a" } })).toEqual({ id: "a" });
    });

    it("returns data from a Bun success envelope", () => {
        expect(unwrap({ success: true, data: { id: "a" } })).toEqual({ id: "a" });
    });

    it("throws the message from a FastAPI error envelope", () => {
        expect(() => unwrap({ status: "error", code: 404, message: "Task not found" })).toThrow(
            "Task not found",
        );
    });

    it("throws the error from a Bun error envelope", () => {
        expect(() => unwrap({ success: false, error: "boom" })).toThrow("boom");
    });

    it("throws a generic message when neither field is present", () => {
        expect(() => unwrap({ status: "error", code: 500 })).toThrow("Request failed");
    });

    it("throws on a shape it does not recognise", () => {
        expect(() => unwrap(null)).toThrow();
    });
});
```

`ui/apps/web/src/lib/api/task.test.ts`:

```ts
import { describe, expect, it } from "bun:test";

import { normalizeApiTask, normalizeBunTask } from "./task";

describe("normalizeApiTask", () => {
    const raw = {
        id: "abc",
        source_url: "https://youtu.be/x",
        title: "clip",
        filename: "clip.mp4",
        kind: "video",
        preset: "1080",
        status: "downloading",
        progress: 42,
        downloaded_bytes: 4200,
        total_bytes: 10000,
        speed_bps: 512,
        eta_seconds: 11,
        error: null,
    };

    it("maps snake_case onto the UI shape", () => {
        const task = normalizeApiTask(raw);
        expect(task.id).toBe("abc");
        expect(task.title).toBe("clip");
        expect(task.progress).toBe(42);
        expect(task.progressDetails.downloadedBytes).toBe(4200);
        expect(task.progressDetails.totalBytes).toBe(10000);
        expect(task.progressDetails.downloadSpeed).toBe(512);
        expect(task.eta).toBe(11);
    });

    it("marks the task as served by the FastAPI service", () => {
        expect(normalizeApiTask(raw).source).toBe("api");
    });

    it("tolerates nulls", () => {
        const task = normalizeApiTask({ ...raw, total_bytes: null, eta_seconds: null, speed_bps: 0 });
        expect(task.progressDetails.totalBytes).toBe(0);
        expect(task.eta).toBe(0);
    });
});

describe("normalizeBunTask", () => {
    it("keeps the Bun torrent shape and tags its source", () => {
        const task = normalizeBunTask({
            id: "t1",
            title: "ubuntu.iso",
            kind: "torrent",
            status: "downloading",
            progress: 10,
            progressDetails: { downloadedBytes: 100, totalBytes: 1000, downloadSpeed: 50, uploadSpeed: 5, eta: 18, peersConnected: 3 },
        });
        expect(task.source).toBe("bun");
        expect(task.progressDetails.peersConnected).toBe(3);
    });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd /Users/roman/projects/github/anydm/ui/apps/web && bun test src/lib/api
```
Expected: FAIL — cannot resolve `./envelope`

- [ ] **Step 3: Write `ui/apps/web/src/lib/api/envelope.ts`**

```ts
/**
 * Two services, two envelopes.
 *
 * FastAPI answers `{ status, code, data, message }`; the Bun API answers
 * `{ success, data, error }`. Both shapes are unwrapped here so no component
 * has to know which service it is talking to — and so deleting the Bun half
 * later means deleting one branch.
 */
export function unwrap<T>(payload: unknown): T {
    if (payload === null || typeof payload !== "object") {
        throw new Error("Request failed");
    }

    const body = payload as Record<string, unknown>;

    if (typeof body.status === "string") {
        if (body.status === "success") {
            return body.data as T;
        }
        throw new Error((body.message as string) || "Request failed");
    }

    if (typeof body.success === "boolean") {
        if (body.success) {
            return body.data as T;
        }
        throw new Error((body.error as string) || "Request failed");
    }

    throw new Error("Request failed");
}
```

- [ ] **Step 4: Write `ui/apps/web/src/lib/api/task.ts`**

```ts
export type TaskSource = "api" | "bun";

export type UiTask = {
    id: string;
    title: string;
    kind: string;
    status: string;
    progress: number;
    eta: number;
    error?: string;
    source: TaskSource;
    progressDetails: {
        downloadedBytes: number;
        totalBytes: number;
        downloadSpeed: number;
        uploadSpeed: number;
        eta: number;
        peersConnected: number;
    };
};

/** A FastAPI task row, in the shape the components already render. */
export function normalizeApiTask(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title || raw.filename || raw.source_url,
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta_seconds ?? 0,
        error: raw.error ?? undefined,
        source: "api",
        progressDetails: {
            downloadedBytes: raw.downloaded_bytes ?? 0,
            totalBytes: raw.total_bytes ?? 0,
            downloadSpeed: raw.speed_bps ?? 0,
            // The FastAPI service never uploads; these two exist so torrent and
            // non-torrent rows render through one component.
            uploadSpeed: 0,
            eta: raw.eta_seconds ?? 0,
            peersConnected: 0,
        },
    };
}

/** A Bun torrent task, already in the UI's shape — only tagged. */
export function normalizeBunTask(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title,
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta ?? 0,
        error: raw.error,
        source: "bun",
        progressDetails: {
            downloadedBytes: raw.progressDetails?.downloadedBytes ?? 0,
            totalBytes: raw.progressDetails?.totalBytes ?? 0,
            downloadSpeed: raw.progressDetails?.downloadSpeed ?? 0,
            uploadSpeed: raw.progressDetails?.uploadSpeed ?? 0,
            eta: raw.progressDetails?.eta ?? 0,
            peersConnected: raw.progressDetails?.peersConnected ?? 0,
        },
    };
}
```

- [ ] **Step 5: Write `ui/apps/web/src/lib/api/client.ts` and `index.ts`**

```ts
import { unwrap } from "./envelope";

/** The FastAPI service: extract, YouTube and direct downloads. */
export function apiUrl(path: string): string {
    const base = import.meta.env.PUBLIC_API_URL || (import.meta.env.DEV ? "http://localhost:8000" : "");
    return `${base}${path}`;
}

/** The Bun service: torrents only, until that port lands. */
export function bunUrl(path: string): string {
    const base = import.meta.env.PUBLIC_BASE_URL || (import.meta.env.DEV ? "http://localhost:3000" : "");
    return `${base}${path}`;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
    const response = await fetch(url, init);
    if (response.status === 204) {
        return undefined as T;
    }
    return unwrap<T>(await response.json());
}

export function getApi<T>(path: string): Promise<T> {
    return request<T>(apiUrl(path));
}

export function postApi<T>(path: string, body: unknown): Promise<T> {
    return request<T>(apiUrl(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

export function deleteApi(path: string): Promise<void> {
    return request<void>(apiUrl(path), { method: "DELETE" });
}

export function getBun<T>(path: string): Promise<T> {
    return request<T>(bunUrl(path));
}

export function postBun<T>(path: string, body?: unknown): Promise<T> {
    return request<T>(bunUrl(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
}

export function deleteBun(path: string): Promise<void> {
    return request<void>(bunUrl(path), { method: "DELETE" });
}
```

`index.ts`:
```ts
export * from "./client";
export * from "./envelope";
export * from "./task";
```

- [ ] **Step 6: Add `PUBLIC_API_URL` to the env example**

Append to `ui/apps/web/.env.example`:
```
# FastAPI service (extract, YouTube and direct downloads)
PUBLIC_API_URL=http://localhost:8000
```
and leave `PUBLIC_BASE_URL=http://localhost:3000` in place — it now means the Bun torrent service specifically. Say so in a comment above it.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd /Users/roman/projects/github/anydm/ui/apps/web && bun test src/lib/api
```
Expected: PASS, 10 passed

- [ ] **Step 8: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add ui && git commit -m "feat(ui): API client for the FastAPI and Bun services"
```

---

### Task 28: Point the UI at FastAPI

**Files:**
- Modify: `ui/apps/web/src/route/index.tsx`
- Modify: `ui/apps/web/src/component/features/add-torrent-modal/field.tsx`

**Interfaces:**
- Consumes: everything Task 27 produced
- Produces: nothing new — this is the cutover

- [ ] **Step 1: Replace the task sync with a two-source merge**

In `ui/apps/web/src/route/index.tsx`, replace the body of `syncTask` (currently one `fetch(\`${BASE_URL}/download\`)` at line 52) with a merge of both services. Downloads come from FastAPI, torrents from Bun, and a failure in one must not blank the other:

```ts
    const syncTask = $(async () => {
        const [apiTasks, bunTasks] = await Promise.all([
            getApi<any[]>("/download")
                .then((rows) => rows.map(normalizeApiTask))
                .catch(() => [] as UiTask[]),
            getBun<any[]>("/download/torrent")
                .then((rows) => rows.map(normalizeBunTask))
                .catch(() => [] as UiTask[]),
        ]);

        store.tasks = [...apiTasks, ...bunTasks].slice(0, MAX_TASKS);
    });
```

Delete `getBaseUrl` and every remaining `${BASE_URL}` template — the client module owns both base URLs now.

- [ ] **Step 2: Point the SSE stream at both services**

The existing `EventSource` at line 97 listens to `${getBaseUrl()}/download/torrent/events`. Open a second one against FastAPI and let each update its own half:

```ts
            const apiEvents = new EventSource(apiUrl("/download/events"));
            const bunEvents = new EventSource(bunUrl("/download/torrent/events"));

            apiEvents.addEventListener("tasks", (event) => {
                const rows = JSON.parse((event as MessageEvent).data) as any[];
                mergeTasks(rows.map(normalizeApiTask), "api");
            });
            apiEvents.addEventListener("task", (event) => {
                mergeTasks([normalizeApiTask(JSON.parse((event as MessageEvent).data))], "api");
            });
            apiEvents.addEventListener("progress", (event) => {
                applyProgress(JSON.parse((event as MessageEvent).data));
            });
```

where `mergeTasks(rows, source)` replaces every task with that `source` tag and leaves the others alone, and `applyProgress({ id, progress, downloaded_bytes, total_bytes, speed_bps, eta_seconds })` patches one row in place. Keep the existing Bun listeners as they are, and close both sources in the cleanup function that currently closes one.

- [ ] **Step 3: Route the add-modal to the right service**

`ui/apps/web/src/component/features/add-torrent-modal/field.tsx:38` builds three endpoints off one base URL. Replace them:

```ts
                if (input.type === "url") {
                    const url = input.value.trim().toLowerCase();
                    const isYouTube =
                        url.includes("youtube.com") ||
                        url.includes("youtu.be") ||
                        url.includes("music.youtube.com");

                    await (isYouTube
                        ? postApi("/download/youtube", { url: input.value, preset: input.preset || "best" })
                        : postApi("/download/url", { url: input.value }));
                } else {
                    await postBun("/download/torrent", { torrent: input.value });
                }
```

`unwrap` already throws with the right message on either envelope, so the manual `if (!response.ok || !payload.success) throw new Error(payload.error)` blocks are deleted rather than translated.

- [ ] **Step 4: Route pause, resume, delete and the file link**

In `index.tsx`, the handlers at lines 154–208 hard-code the Bun torrent paths. Send each to the service that owns the task, using the `source` tag the normalizers set:

```ts
    const controlTask = $(async (taskId: string, action: "pause" | "resume") => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;
        await (task.source === "api"
            ? postApi(`/download/${taskId}/${action}`, {})
            : postBun(`/download/torrent/${taskId}/${action}`));
        syncTask();
    });

    const removeTask = $(async (taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;
        await (task.source === "api" ? deleteApi(`/download/${taskId}`) : deleteBun(`/download/torrent/${taskId}`));
        syncTask();
    });
```

The download link at line 208 always pointed at `${BASE_URL}/download/${taskId}/file`. Choose the base by source: `a.href = task.source === "api" ? apiUrl(...) : bunUrl(...)`.

- [ ] **Step 5: Allow the UI origin in the API**

```bash
cd /Users/roman/projects/github/anydm/api
grep ALLOWED_ORIGINS .env
```
It must list the Vite dev origin, e.g. `ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173`. Fix it and `make restart` if not.

- [ ] **Step 6: Typecheck and format**

```bash
cd /Users/roman/projects/github/anydm/ui && bun run web:fmt && bun run web:chk
```
Expected: both clean

- [ ] **Step 7: Verify end to end in a browser**

```bash
cd /Users/roman/projects/github/anydm
make api:up
(cd apps/api && bun run dev &)
make ui:dev
```
Then in the browser at `http://localhost:5173`:
1. Paste a YouTube URL, pick 1080 — the task appears and progresses live
2. Paste a direct file URL — it downloads and completes
3. Add a magnet link — the torrent appears alongside, still served by Bun
4. Pause and resume a YouTube task — progress stops and continues, not restarts
5. Click download on a completed task — the file saves
6. Delete a task from each service — both disappear

Expected: all six work, with no CORS errors in the console.

- [ ] **Step 8: Commit**

```bash
cd /Users/roman/projects/github/anydm
git add ui && git commit -m "feat(ui): serve extract and downloads from the FastAPI service"
```

---

# After this plan

`apps/api` still exists and still serves `/download/torrent/*`. It is deleted by the torrent port, which gets its own spec — the design document's *Out of scope* section lists what that spec must cover: the torrent engine choice (rqbit sidecar, libtorrent on a pinned Python, or qBittorrent), the `Torrent` model, and global rate limits.

When that lands, three things go with it: `apps/api/`, the `legacy-api` CI job, and the `bunUrl`/`getBun`/`postBun`/`deleteBun` half of the UI client.
