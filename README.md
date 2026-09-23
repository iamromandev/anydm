# AnyDM

Any Download Manager — a FastAPI service that extracts and downloads media from URLs, with a Qwik web UI.

## Repository structure

```
anydm/
├── api/          # FastAPI + Tortoise ORM service (Python 3.14, uv, Docker)
│   ├── src/
│   ├── tests/
│   ├── docker-compose.yml   # API + Postgres + rqbit
│   └── makefile
├── ui/           # Bun workspace
│   └── apps/web/ # Qwik 2 + Vite SPA
├── docs/
└── makefile      # delegates to api/makefile and ui/package.json
```

**Planned (not in repo yet):** `apps/desktop` (Tauri) and `apps/mobile`. Torrent streaming and on-demand transcoding have since shipped; see [docs/architecture.md](docs/architecture.md).

For how the pieces fit together — the background loops, a task's life, how
streaming works — see [docs/architecture.md](docs/architecture.md).

## Prerequisites

- [uv](https://docs.astral.sh/uv) — pins and runs Python 3.14.6 for the API
- [Bun](https://bun.sh) — runs the UI workspace
- Docker + Compose — the API stack (`api/docker-compose.yml`) runs the service, Postgres, and rqbit
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
| `make api-clean-volumes` | Drop the project's volumes (db, download, torrent) |
| `make ui-build` | Production build for the UI |
| `make ui-check` / `ui-format` | Typecheck / format the UI |
| `make ui-test` | UI unit tests (`bun test`) |
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
| `API_KEY` | *(empty)* | When set, every route but `GET /health/check` requires it in the `X-API-Key` header. Browser-opened URLs (event streams, file downloads, HLS) may pass `?api_key=` instead. Enter the same key in the UI's Settings. Empty leaves the API open |
| `CORS_ORIGINS` | *(empty)* | Comma-separated origins; empty leaves the CORS middleware off (same-origin only) |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8030` | Public URL of this service (declared in settings; no route reads it yet) |
| `DOWNLOAD_DIR` | `./download` | Where finished files land; compose mounts a named volume here |
| `DOWNLOAD_WORKERS` | `2` | Concurrent download workers (min 1) |
| `DOWNLOAD_CHUNK_SIZE` | `65536` | Read chunk size in bytes (min 1024); still paces progress updates, because the write buffer's flush timer only gets a chance to fire when a chunk arrives |
| `DOWNLOAD_SEGMENTS` | `4` | Concurrent range requests per part (min 1). `1` turns segmentation off entirely |
| `DOWNLOAD_SEGMENT_MIN_BYTES` | `16777216` | Smallest file worth splitting; below this the extra round trips cost more than the concurrency wins |
| `DOWNLOAD_WRITE_BUFFER_BYTES` | `4194304` | Bytes buffered before a positional write (min 65536), paired with a 500 ms timer |
| `DOWNLOAD_PROGRESS_FLUSH_MS` | `1000` | How often progress reaches the database (min 100) |
| `DOWNLOAD_MAX_ATTEMPTS` | `3` | Total tries per task, the first included (min 1) |
| `DOWNLOAD_RATE_LIMIT_BPS` | `0` | Bytes per second shared by every HTTP download and segment. `0` is unlimited |
| `TORRENT_ENABLED` | `true` | Torrent routes and the monitor. Off, torrent routes answer 503 and nothing polls |
| `TORRENT_API_URL` | `http://torrent-anydm-api:3030` | rqbit's control API. `http://127.0.0.1:8031` when running the API on the host |
| `TORRENT_DIR` | `./download/torrent` | Where rqbit writes, under `DOWNLOAD_DIR` |
| `TORRENT_POLL_MS` | `1000` | How often the monitor samples the engine (min 250) |
| `TORRENT_METADATA_TIMEOUT_S` | `30` | How long resolving waits for peers to supply metadata |
| `TORRENT_REQUEST_TIMEOUT_S` | `10` | Per-call timeout against the control API |
| `TORRENT_DOWNLOAD_LIMIT_BPS` | `0` | rqbit's total download cap in bytes per second. `0` is unlimited. Pushed to rqbit by the API, again after rqbit restarts |
| `TORRENT_UPLOAD_LIMIT_BPS` | `0` | rqbit's total upload cap, seeding included. Same rules |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg executable; used to mux YouTube's separate video and audio, and to transcode stream segments |
| `FFPROBE_PATH` | `ffprobe` | ffprobe executable; reads a source's duration and streams before a session starts |
| `STREAM_DIR` | `./stream` | Scratch directory for on-demand HLS segments |
| `STREAM_SEGMENT_SECONDS` | `6` | Fixed HLS segment duration |
| `STREAM_READAHEAD_SEGMENTS` | `2` | Segments generated ahead of the one being requested (min 0) |
| `STREAM_MAX_CONCURRENT_ENCODES` | `2` | Per-session cap on simultaneous ffmpeg processes (min 1) |
| `STREAM_IDLE_TIMEOUT_S` | `300` | Inactivity before the sweeper ends a session (min 1) |
| `STREAM_PROBE_TIMEOUT_S` | `600` | Ceiling on waiting for a torrent-backed stream to yield data. A safety net, not a normal wait (min 1) |
| `TORRENT_REAP_POLL_S` | `60` | How often to delete rqbit torrents no task or live session owns (min 1) |

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
  - Health, extract and settings
    - `GET /health/check` — health probe
    - `POST /extract` — resolve a URL into stream metadata (YouTube only)
    - `GET /settings` — how this API is configured, secrets left out; read-only, since changing a setting means editing `api/.env` and restarting
  - Downloads
    - `POST /download/youtube` — enqueue a YouTube download for a preset
    - `POST /download/url` — enqueue a direct URL download
    - `GET /download` — one page of tasks. `page` (from 1) and `page_size` (1–100, default 50); `group` is one of the sidebar's filters, `all` (default), `downloading`, `seeding` or `completed`; `sort` is `created_at`, `title`, `total_bytes`, `progress` or `speed_bps`, prefixed with `-` for descending (default `-created_at`)
    - `GET /download/summary` — how many tasks each sidebar filter holds, counted in the database
    - `GET /download/events` — SSE task and progress stream
    - `GET /download/{task_id}` — one task
    - `GET /download/{task_id}/file` — serve the finished file
    - `POST /download/{task_id}/pause` · `POST /download/{task_id}/resume`
    - `DELETE /download/{task_id}` — remove a task and, by default, its files. `delete_files=false` keeps the files and drops only the row; that is accepted only for a `complete` or `seeding` task, and answered 409 otherwise
    - `POST /download/bulk` — act on the whole list: `{"action": "pause_all" | "resume_all" | "clear_finished"}`. For `clear_finished`, `"delete_files": true` takes finished downloads' files too; a failed download's partial file goes either way
  - Torrents
    - `POST /download/torrent/resolve` — inspect a magnet or `.torrent` without downloading
    - `POST /download/torrent` — enqueue a torrent with a file selection
    - `POST /download/{task_id}/seed/stop` — stop seeding, keep the files
    - `GET /download/{task_id}/file/{file_index}` — serve one file out of a torrent
  - Streaming (independent of downloading — nothing is kept)
    - `POST /stream/start` — probe a URL, magnet or `.torrent` and open a session
    - `GET /stream/events` — SSE session status, including swarm numbers
    - `GET /stream/{session_id}/playlist.m3u8` — the HLS playlist
    - `GET /stream/{session_id}/segment_{index}.ts` — one segment, transcoded on request
    - `DELETE /stream/{session_id}` — end the session
- **Workers:** a pool started in the app lifespan claims queued tasks, resumes from `.part` files, and requeues orphans left in-flight by a previous process
- **Segmented transfers:** direct and YouTube downloads are fetched over `DOWNLOAD_SEGMENTS` concurrent range requests written positionally into one preallocated `.part`, with per-segment watermarks in `segment` so a pause or a crash resumes mid-segment. A server that refuses ranges, a file below `DOWNLOAD_SEGMENT_MIN_BYTES`, or `DOWNLOAD_SEGMENTS=1` all fall back to the original single-stream path — which is also the rollback switch.

  How much this wins depends entirely on where the bottleneck is. Against a server that caps each connection it is close to linear (measured 0.12 → 0.49 MB/s, 4.1×, on a test server throttled to 120 KB/s per connection). Against a mirror that does not, it is nearly nothing, because one connection already saturates the link (measured 9.09 → 10.37 MB/s on a Debian mirror, with 8 segments no better than 4).
- **Torrents:** a pinned rqbit runs as its own Compose service and owns every torrent transfer. The API resolves a magnet to a file list, creates one task per torrent with child `file` rows, and a monitor polls the engine and mirrors progress onto them. A finished torrent seeds until it is told to stop. rqbit's control API has no authentication, so it is published on loopback only; port 4240 is published for incoming peers. Torrents never occupy a download worker slot.
- **Streaming:** playing is a separate path from downloading and keeps nothing. A session probes the source, then serves HLS whose segments are transcoded when a player asks for them, `STREAM_READAHEAD_SEGMENTS` ahead of the one being fetched. Idle sessions are swept after `STREAM_IDLE_TIMEOUT_S`, and a reaper deletes rqbit torrents no task or session owns.
- **Migrations:** Tortoise's built-in migrations under `src/data/db/migration`, applied by `python -m scripts.migrate` — the compose command runs it before uvicorn

### `ui/apps/web`

- **Stack:** Qwik 2, Qwik Router, Vite 8, Tailwind 4, TypeScript
- **Routes:** `/` (home — URL/torrent input, task list, sidebar filters); `routesDir` is `src/route`
- **Entry:** `src/entry.ssr.tsx`, `src/entry.csr.tsx`, `src/root.tsx`
- **API client:** `src/lib/api` — envelope unwrapping, task normalization; the home route fetches `GET /download` on load and after each action, and takes live updates from `/download/events`

## Current limitations

- `POST /extract` handles YouTube only; other URLs are rejected.
- Running the API on the host with `make api-run` while rqbit runs in Docker means the two disagree about paths. Torrents download, but the host-run API cannot read the finished files. Use `make api-up` for torrent work.
- Seeders and leechers are never shown: rqbit reports connected peers and does not split a swarm.
- The API has no authentication. CORS is the only gate, which does nothing for a direct request, so do not expose it beyond a trusted network.

## CI

GitHub Actions runs on pushes to `main` and on pull requests, in three parallel jobs:

- **api** — `uv sync`, then ruff, ty, and the unit suite.
- **api-integration** — brings up Postgres as a service, applies the migrations with `python -m scripts.migrate`, and runs the tests marked `integration`. The schema comes from the migrations rather than from `generate_schemas`, so a migration that does not do what it claims fails here.
- **ui** — a frozen Bun lockfile, then the format check, typecheck, and production build.

See [.github/workflows/ci.yml](.github/workflows/ci.yml).
