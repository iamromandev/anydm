# AnyDM

Any Download Manager — a FastAPI service that extracts and downloads media from URLs, with a Qwik web UI.

## Repository structure

```
anydm/
├── api/          # FastAPI + Tortoise ORM service (Python 3.14, uv, Docker)
│   ├── src/
│   ├── tests/
│   ├── docker-compose.yml   # API + Postgres
│   └── makefile
├── ui/           # Bun workspace
│   └── apps/web/ # Qwik 2 + Vite SPA
├── docs/
└── makefile      # delegates to api/makefile and ui/package.json
```

**Planned (not in repo yet):** torrent support in the API, `apps/desktop` (Tauri), `apps/mobile`.

## Prerequisites

- [uv](https://docs.astral.sh/uv) — pins and runs Python 3.14.6 for the API
- [Bun](https://bun.sh) — runs the UI workspace
- Docker + Compose — the API stack (`api/docker-compose.yml`) runs the service and Postgres
- ffmpeg — only when running the API on the host; the Docker image installs it

## Install

```bash
make api-install   # uv sync
make ui-install    # bun install --cwd ui
```

## Development

```bash
# API + Postgres in containers (API on http://127.0.0.1:8030, Postgres on 5430)
make api-up

# or the API on the host with reload (needs a reachable Postgres)
make api-run

# UI (format + typecheck + Vite SSR dev server, http://localhost:3030)
make ui-dev
```

Interactive API docs are at `http://127.0.0.1:8030/docs`.

Common root targets — run `make help` for the full list, `make -C api help` for the API's own:

| Target | Description |
|--------|-------------|
| `make check` | Lint + typecheck both stacks |
| `make api-test` | API unit tests |
| `make api-test-all` | Every API test, integration included (needs `make api-up`) |
| `make api-migrate` | Run database migrations in the server container |
| `make api-logs` / `api-ps` | Follow logs / list containers |
| `make api-clean-db` | Drop the database volume |
| `make ui-build` | Production build for the UI |
| `make ui-check` / `ui-format` | Typecheck / format the UI |
| `make down` | Stop both stacks |

## Environment variables

### API (`api/`)

Settings are loaded by pydantic-settings from `api/.env` — that exact name, not `.env.local`:

```bash
cp api/.env.example api/.env
```

| Variable | Default | Description |
|----------|---------|-------------|
| `ENV` | *(required)* | `local`, `dev`, or `prod` |
| `DEBUG` | *(required)* | Enables FastAPI debug mode |
| `LOG_FORMAT` | `text` | `text` or `json` |
| `DB_ENGINE` | `tortoise.backends.asyncpg` | Tortoise database backend |
| `DB_HOST` | *(required)* | `db-anydm-api` inside the compose stack; `localhost` when running uvicorn on the host |
| `DB_PORT` | *(required)* | `5432` inside the stack; `5430` from the host |
| `DB_USER` | *(required)* | Database user |
| `DB_PASSWORD` | *(required)* | Database password |
| `DB_NAME` | *(required)* | Database name |
| `CORS_ORIGINS` | *(empty)* | Comma-separated origins; empty leaves the CORS middleware off (same-origin only) |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8030` | Public URL of this service (declared in settings; no route reads it yet) |
| `DOWNLOAD_DIR` | `./download` | Where finished files land; compose mounts a named volume here |
| `DOWNLOAD_WORKERS` | `2` | Concurrent download workers (min 1) |
| `DOWNLOAD_CHUNK_SIZE` | `65536` | Read chunk size in bytes (min 1024); progress is sampled once per chunk |
| `DOWNLOAD_PROGRESS_FLUSH_MS` | `1000` | How often progress reaches the database (min 100) |
| `DOWNLOAD_MAX_ATTEMPTS` | `3` | Total tries per task, the first included (min 1) |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg executable; YouTube downloads mux separate video and audio streams with it |

The compose stack reads `DB_USER`, `DB_PASSWORD`, and `DB_NAME` from the same file to provision Postgres. `api/.env` is gitignored; see [api/.env.example](api/.env.example).

### UI (`ui/apps/web`)

```bash
cp ui/apps/web/.env.example ui/apps/web/.env.local
```

| Variable | Default | Description |
|----------|---------|-------------|
| `PUBLIC_API_URL` | `http://localhost:8030` | Base URL of the AnyDM API. Unset, the client falls back to `http://localhost:8030` in dev and to same-origin in a production build |

`.env.local` is gitignored; see [ui/apps/web/.env.example](ui/apps/web/.env.example).

## Apps

### `api/`

- **Stack:** FastAPI, Tortoise ORM + asyncpg (Postgres), pytubefix, httpx, sse-starlette, loguru; managed with uv
- **Endpoints:**
  - `GET /health/check` — health probe
  - `POST /extract` — resolve a URL into stream metadata (YouTube only)
  - `POST /download/youtube` — enqueue a YouTube download for a preset
  - `POST /download/url` — enqueue a direct URL download
  - `GET /download` — list tasks
  - `GET /download/events` — SSE progress stream
  - `GET /download/{task_id}` — one task
  - `GET /download/{task_id}/file` — serve the finished file
  - `POST /download/{task_id}/pause` · `POST /download/{task_id}/resume` · `DELETE /download/{task_id}`
- **Workers:** a pool started in the app lifespan claims queued tasks, resumes from `.part` files, and requeues orphans left in-flight by a previous process
- **Migrations:** Tortoise's built-in migrations under `src/data/db/migration`, applied by `python -m scripts.migrate` — the compose command runs it before uvicorn

### `ui/apps/web`

- **Stack:** Qwik 2, Qwik Router, Vite 8, Tailwind 4, TypeScript
- **Routes:** `/` (home — URL/torrent input, task list, sidebar filters); `routesDir` is `src/route`
- **Entry:** `src/entry.ssr.tsx`, `src/entry.csr.tsx`, `src/root.tsx`
- **API client:** `src/lib/api` — envelope unwrapping, task normalization; the home route fetches `GET /download` on load and after each action, and takes live updates from `/download/events`

## Current limitations

- Torrents are not ported to the FastAPI service. The add-torrent modal posts `/download/torrent`, which does not exist yet, so the API answers 404 and the modal surfaces it.
- `POST /extract` handles YouTube only; other URLs are rejected.
- The sidebar's "Seeding" filter is torrent-era and matches nothing today.
- The API integration suite needs a live Postgres and disk, so CI runs unit tests only (`make api-test-all` locally, after `make api-up`).

## CI

GitHub Actions runs on pushes to `main` and on pull requests: the API job installs with `uv sync --frozen` and runs ruff, ty, and the unit tests; the UI job installs with a frozen Bun lockfile and runs the format check, typecheck, and production build. See [.github/workflows/ci.yml](.github/workflows/ci.yml).
