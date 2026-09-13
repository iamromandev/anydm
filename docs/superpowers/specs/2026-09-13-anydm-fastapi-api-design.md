# AnyDM API: Bun → FastAPI

**Date:** 2026-09-13
**Status:** Approved, ready for implementation planning

Replace anydm's Bun/Hono API with a Python FastAPI service at the repository
root, structured after the `exateks/auth` project. Scope of v1 is extract and
YouTube download; torrents follow in their own spec.

## Context

`apps/api` is 1,824 lines of TypeScript on Bun-only libraries:

| Area | Files | Endpoints |
|---|---|---|
| extract | `route/extract.ts`, `service/youtube.ts` (376 L) | `POST /extract` |
| youtube | `route/download.ts`, `service/download.ts` (235 L) | `POST /download/youtube`, `GET /download`, `GET /download/:id`, `GET /download/:id/file` |
| torrent | `route/torrent.ts`, `service/torrent.ts` (515 L), `torrent/{session,events}.ts` | `POST /download/torrent`, `GET /events` (SSE), `GET /global/stats`, `PATCH /global/limits`, `GET\|DELETE /:id`, `POST /:id/{pause,resume,verify}`, `GET /:id/progress` |
| state | `service/task.ts` | in-memory `Map` flushed to `data/tasks.json` |

This is a rewrite, not a move. Three constraints shaped it:

1. **`rqbit-napi` has no Python equivalent.** It is Rust rqbit behind a Node
   NAPI binding. Torrents are therefore deferred; the Bun API keeps serving
   them until a follow-up spec ports them.
2. **`libtorrent` ships no cp314 wheels.** PyPI 2.1.1 covers cp39–cp313 only,
   while auth pins `requires-python == 3.14.6`. This closed off the obvious
   in-process torrent alternative and confirmed the deferral.
3. **`youtubei.js` has no Python equivalent either.** `pytubefix` 11.1.0
   (released 2026-09-07, pure Python, declares 3.14 support) is the closest
   concept-for-concept match and is what v1 uses.

One live bug surfaced during analysis: the UI calls `${BASE_URL}/download/url`
(`apps/ui/src/route/index.tsx:242`) against a route that has never existed.
v1 implements it.

## Decisions

| Decision | Choice |
|---|---|
| Persistence | Postgres + Tortoise, full mirror of auth's data layer |
| Torrents | Deferred out of v1 |
| Existing Bun API | Left whole and untouched until FastAPI reaches parity |
| Repo layout | Full auth mirror — `api/` and `ui/apps/web` at root |
| YouTube library | `pytubefix` |
| Download model | Server-side background downloads to disk |
| Worker architecture | In-process asyncio workers (one container) |

### On the worker choice

A separate worker container was recommended and declined. The cost of the
in-process pool is that `uvicorn --reload` — the command in auth's makefile,
and the one used all day — kills in-flight downloads on every code edit. The
design absorbs this rather than ignoring it: every download is resumable and
every orphan is requeued at startup (see *Resume and recovery*).

The in-process pool also buys something the separate container could not:
workers and SSE subscribers share a process, so progress reaches the browser
without a database round trip, and `service/torrent/events.ts` ports almost
verbatim.

## Target layout

```
anydm/
├── api/                 # NEW — Python 3.14 FastAPI, mirrors auth/api
├── ui/                  # bun workspace root (package.json, bunfig.toml, bun.lock)
│   └── apps/web/        # apps/ui, moved and renamed
├── apps/api/            # Bun API — untouched, deleted after the torrent port
├── makefile             # root, delegating to both halves
└── .github/workflows/ci.yml
```

auth has no root `package.json`: `api/` and `ui/` are independent halves tied
together by a root makefile, and `ui/` is itself a bun workspace root holding
`apps/web`. anydm adopts that shape exactly.

## Module structure

```
api/
├── pyproject.toml ruff.toml makefile dockerfile docker-compose.yml
├── .python-version (3.14.6)  .env.example  .dockerignore  .gitignore
├── scripts/migrate.py
├── src/
│   ├── main.py                  # create_app, lifespan, CORS, init_global_errors, init_db
│   ├── config/{settings,logging}.py
│   ├── core/{base,common,constant,error,format,runtime,success,type}.py
│   ├── data/
│   │   ├── db/{__init__,migration/,model/download/task.py}
│   │   ├── repo/download/{interface/,task_repo.py}
│   │   ├── schema/{health,extract,download}/
│   │   └── type/{core,download}/
│   ├── deps/                    # request-scoped dependencies only
│   ├── lib/
│   │   ├── youtube/             # pytubefix boundary
│   │   ├── media/               # ffmpeg invocation
│   │   └── event/               # in-process pub/sub
│   ├── route/{health,extract,download}/
│   ├── service/
│   │   ├── health/health_service.py
│   │   ├── extract/extract_service.py
│   │   └── download/{download_service.py,download_worker.py,downloader.py}
│   └── util/
└── tests/{lib,service,data}/
```

**Ported from auth:** `core/base.py` (`Base` model, `BaseRepo`, `BaseSchema`,
`BaseService`), `core/success.py` and `core/error.py` envelopes,
`config/settings.py`'s pydantic-settings pattern, `data/db/__init__.py` with
`DB_CONFIG`/`init_db`/`get_db_health`/`get_db_version`, the
`route/*/__init__.py` router-aggregation pattern, makefile, dockerfile,
compose, and the ruff/ty/pytest configuration.

**Not ported:** `deps/{auth,client_auth,rbac,tenant,tenant_filter,introspection}`,
`data/db/model/{iam,rbac,audit,flow}`, all of `data/seed/`,
`middleware/TenantContextMiddleware`, `lib/iam/*`, `client/email`,
`template/email`. anydm has no principals or tenants. CORS uses a static
`ALLOWED_ORIGINS` setting rather than auth's `DatabaseCorsMiddleware`.

### Boundaries

- **`lib/youtube/`** is the only module importing pytubefix. It exposes a
  narrow protocol — `resolve_info(url)`, `resolve_streams(video_id, preset)`,
  `stream_url(video_id, itag)` — and raises this project's own errors. When
  YouTube breaks pytubefix, one module changes; tests fake the protocol so
  nothing else needs network.
- **`service/download/downloader.py`** pulls bytes to disk with resume. It
  knows nothing about YouTube or tasks: a URL, a destination, a progress
  callback.
- **`lib/event/`** is the SSE hub. Workers publish, the SSE route subscribes.
  Same process, so it is a bounded `asyncio.Queue` per subscriber.

## Data model

One table: `src/data/db/model/download/task.py`, a `Task` extending auth's
`Base` (UUID primary key, `created_at`/`updated_at`/`deleted_at`, soft delete,
`get_active()`).

| Group | Columns |
|---|---|
| source | `source_url`, `platform`, `video_id` |
| request | `preset`, `kind` |
| resolved plan | `title`, `filename`, `mime_type`, `video_itag`, `audio_itag` |
| progress | `status`, `progress`, `downloaded_bytes`, `total_bytes`, `speed_bps`, `eta_seconds` |
| result | `file_path`, `file_size` |
| lifecycle | `error`, `error_code`, `attempts`, `next_attempt_at`, `started_at`, `completed_at`, `heartbeat_at` |

Enums live in `data/type/download/` as `CharEnumField`s:

- `Platform` — `youtube`, `direct`
- `Preset` — `best`, `2160`, `1440`, `1080`, `720`, `480`, `mp3`
- `Kind` — `video`, `audio`, `file` (`file` is what `platform=direct` produces:
  an arbitrary fetched file with no notion of stream quality)
- `TaskStatus` — `pending`, `downloading`, `muxing`, `paused`, `complete`,
  `failed`, `canceled`

`TaskStatus` is deliberately not named `Status`: `core.type.Status` is already
the success/error enum the envelope uses.

`filename` and `file_path` are distinct: `filename` is the target name decided
at enqueue time and shown in the UI, `file_path` is the final on-disk path
relative to `DOWNLOADS_DIR`, written only on completion.

`progress` always means **byte-download** progress. It reaches 100 when the
last byte lands and stays there through muxing; `status` is what communicates
the phase. A muxing job does not report a second 0–100 sweep.

Four things carry the resume story:

- **`video_itag` / `audio_itag`, not a stored URL.** pytubefix stream URLs
  expire within hours and bind to the requesting IP, so a resumed download
  re-resolves from `video_id` + itag rather than replaying a dead link.
- **`downloaded_bytes`** is the offset a `Range` request resumes from.
- **`next_attempt_at`** makes retry backoff visible to the claim query, so a
  failing task neither spins nor needs a sleeping coroutine holding it.
- **`heartbeat_at`** is written by the progress flush. With a single process it
  is *not* consulted by startup recovery — every `downloading` row at boot is
  by definition an orphan, so recovery requeues unconditionally. It exists for
  observability ("has this task moved in the last minute?") and to keep the
  multi-process door open without a later migration.

Indexes: `(status, created_at)` for the claim query, plus `platform` and
`video_id`.

Torrent columns are absent — no `info_hash`, `seeders`, `ratio`,
`uploaded_bytes`. When torrents land they get their own model rather than
nullable columns here.

## API contract

| Method | Path | Notes |
|---|---|---|
| GET | `/health/check` | auth's health verbatim — db status and version, uptime, environment |
| POST | `/extract` | url → title, author, duration, thumbnails, available formats |
| POST | `/download/youtube` | `{url, preset}` → creates a pending task, returns it |
| POST | `/download/url` | new — direct HTTP download |
| GET | `/download` | list, paginated via auth's `Meta` |
| GET | `/download/{id}` | single task |
| GET | `/download/{id}/file` | `FileResponse` from disk, Range-capable |
| POST | `/download/{id}/pause` | valid from `pending` or `downloading`; 409 otherwise |
| POST | `/download/{id}/resume` | valid from `paused` or `failed`; clears `error`, `error_code` and `next_attempt_at`, resets `attempts`, re-queues. The `.part` file is the resume point |
| DELETE | `/download/{id}` | cancel, soft-delete the row, remove files |
| GET | `/download/events` | SSE via `sse-starlette` |

Absent from v1: everything under `/download/torrent/*`, plus `/global/stats`
and `/global/limits`. The Bun API keeps serving those.

`POST /download/url` is the downloader with pytubefix skipped: `platform=direct`,
filename from `Content-Disposition` or the URL path. It closes the live bug
described in *Context* rather than porting it.

### Response envelope change

Every ported endpoint changes shape, because `core/success.py` and
`core/error.py` port as-is:

```
Bun      { "success": true, "data": {...} }
FastAPI  { "status": "success", "code": 200, "data": {...}, "meta": null, "timestamp": "..." }
```

Errors likewise become auth's `Error` envelope. Field naming flips with it —
`downloadedBytes` → `downloaded_bytes`, `taskId` → `id` — since auth's schemas
are snake_case throughout. The UI switchover in phase 8 is therefore a
response-reader change, not only a base-URL swap. The upside is one envelope
across both services.

## Data flow

### Enqueue

`POST /download/youtube` resolves *the plan* synchronously, then returns.
`lib/youtube` fetches info via pytubefix, selects streams for the preset, and
the route writes one `pending` row carrying `title`, `filename`, `kind`,
`mime_type`, `video_itag`, `audio_itag`. No bytes move. A bad URL or an
unavailable format fails here as a 400/422 the caller sees immediately, rather
than as a task that fails in the background ten seconds later.

Enqueue then sets an `asyncio.Event` the workers wait on, so a queued task
starts in milliseconds rather than on the next poll tick.

### Worker pool

`lifespan` recovers orphans, then opens an `asyncio.TaskGroup` with
`DOWNLOAD_WORKERS` coroutines (default 2). Each loops
`claim → resolve → download → post-process → finish`.

**Claim** is the only contended step, and Postgres arbitrates it:

```python
async with in_transaction() as conn:
    task = await (Task.filter(status=PENDING)
                      .filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
                      .order_by("created_at").limit(1)
                      .select_for_update(skip_locked=True)
                      .using_db(conn).first())
```

Idle workers block on the enqueue event with a ~5s timeout as a fallback poll.

**Resolve** re-derives a fresh stream URL from `video_id` + itag on every
attempt. `platform=direct` skips this step.

**Download** is `downloader.fetch(url, dest, resume_from, on_progress)`: httpx
streaming with `Range: bytes=N-` into `<downloads>/<task_id>/<name>.part`.
Progress is tracked per chunk in memory but flushed to Postgres and published
to SSE on a `PROGRESS_FLUSH_MS` throttle — a 4 GB file at 20 MB/s would
otherwise be thousands of writes per second. Speed is an EWMA over the flush
interval; ETA is remaining ÷ speed.

**Post-process** depends on `kind`:

| Case | Action |
|---|---|
| combined stream | rename `.part` to the final name |
| video + audio | `status=muxing`, ffmpeg `-c copy -movflags +faststart` |
| mp3 | `status=muxing`, ffmpeg `-vn -c:a libmp3lame -q:a 2` |

ffmpeg reads two local files, unlike the Bun implementation
(`apps/api/src/service/download.ts:60`), which had to keep two remote URLs
alive for the duration of the transcode.

**Finish** writes `file_path`, `file_size`, `completed_at`, `status=complete`,
and publishes a final event.

Disk layout is `downloads/<task_id>/<filename>`, matching the Bun API's
convention so the torrent port can reuse it.

### Pause, cancel, resume and recovery

An in-process registry maps `task_id` to an `asyncio.Event`. Pause and delete
set it; the downloader checks between chunks and unwinds. Pause keeps the
`.part` file; cancel removes the task directory.

On every boot:

- rows in `downloading` or `muxing` return to `pending`, with
  `downloaded_bytes` preserved
- `.part` files are left in place, so the next claim resumes from byte *N*
- `paused` rows stay paused

A `uvicorn --reload` firing mid-download therefore costs a few seconds and a
re-resolve, not the file.

### SSE

`lib/event` holds a set of subscribers, each a **bounded** `asyncio.Queue` —
bounded so a slow browser drops frames instead of stalling a worker. On
connect: a `tasks` event carrying the full list, then `task` events per
update, plus a comment heartbeat every ~15s so proxies do not reap the
connection. These are the event names the UI's `EventSource` already handles
(`apps/ui/src/route/index.tsx:97`).

Because workers and subscribers share a process, a progress update reaches the
browser without touching Postgres.

### Serving files

`GET /download/{id}/file` returns Starlette's `FileResponse`, which handles
Range and resumable browser downloads natively. An incomplete task returns 409,
mirroring what the Bun API did for verifying torrents.

## Error handling

`core/error.py` ports wholesale — it is domain-agnostic. Domain failures use
auth's factory style (`Error.not_found(...)`, `Error.bad_request(...)`) rather
than a parallel exception hierarchy.

auth's `Error` already carries `retry_able`, so the worker reads its retry
decision off the error instead of re-deriving it:

```python
if err.retry_able and task.attempts < MAX_ATTEMPTS:
    task.status, task.next_attempt_at = PENDING, now() + backoff(task.attempts)
else:
    task.status, task.error_code, task.error = FAILED, err.type, err.message
```

`attempts` counts tries, not retries, and is incremented before the check. With
`MAX_ATTEMPTS=3` a task runs at most three times: the first try, then retries
delayed 5s and 30s. The third entry in the schedule (2m) only applies if
`MAX_ATTEMPTS` is raised.

Classification, all raised inside `lib/youtube` and `downloader`:

| Failure | Code | ErrorType | Retryable |
|---|---|---|---|
| Unparseable or non-YouTube URL | 400 | `unsupported_operation` | no |
| Video private or removed | 404 | `does_not_exist` | no |
| Age or region gated | 403 | `forbidden` | no |
| No stream for preset | 422 | `unprocessable_entity` | no |
| pytubefix broke on a YouTube change | 502 | `external_api_error` | yes |
| Expired stream URL (403 mid-transfer) | 502 | `external_api_error` | yes — re-resolve fixes it |
| Network, timeout, upstream 5xx | 502 | `dependency_failure` | yes |
| ffmpeg non-zero exit | 500 | `dependency_failure` | once |

`Error.process_exception`'s `_SAFE_EXCEPTION_TYPES` and generic-message table
already keep internals out of responses; nothing extra is needed.

## Testing

auth's tests are pure logic — no database, no network — and that discipline
carries over because the risky logic here is pure.

| Test | Covers |
|---|---|
| `tests/lib/youtube/test_url.py` | The `extractYouTubeVideoId` port: `youtu.be`, `/watch?v=`, `/embed/`, `/shorts/`, `music.`, `m.`/`www.` prefixes, junk. Table-driven. |
| `tests/lib/youtube/test_format.py` | Preset → stream selection against checked-in fixture stream lists: combined vs muxed, height matching, mp3 audio pick, "no format available" |
| `tests/service/download/test_progress.py` | Progress math, EWMA speed, ETA, flush-throttle boundaries |
| `tests/service/download/test_retry.py` | Classification and backoff schedule driven by `retry_able` |
| `tests/lib/event/test_hub.py` | Subscribe, publish, bounded-queue drop, unsubscribe |
| `tests/lib/media/test_ffmpeg.py` | Argument construction only, no subprocess |

`lib/youtube` sits behind a Protocol so tests inject a fake and never hit the
network.

**Known gap:** claim-under-`SKIP LOCKED`, orphan recovery and resume are
database- and disk-stateful, and unit tests will not cover them. They get one
`@pytest.mark.integration` module run against the compose Postgres, excluded
from the default `make test`. Phase 4's manual checkpoint — kill the process
mid-download and watch it resume — is the real verification.

## Rollout

Phases 1–6 touch only the new `api/` directory; nothing in the running app
changes until phase 7.

| # | Phase | Checkpoint |
|---|---|---|
| 1 | Scaffold: pyproject, ruff, makefile, docker, config, core, db, health | `make up && curl :8000/health/check` reports the db version |
| 2 | Extract: `lib/youtube`, service, route | `POST /extract` on a real video; URL tests green |
| 3 | Model, migration, enqueue and read endpoints | Rows appear; no bytes move yet |
| 4 | Downloader, worker pool, resume, orphan recovery | 1080p completes; kill mid-download, confirm resume |
| 5 | ffmpeg mux and mp3, file serving with Range, pause/resume/delete | An mp3 and a muxed 4K file both play |
| 6 | SSE | `curl -N /download/events` shows live progress |
| 7 | Repo restructure: `apps/ui` → `ui/apps/web`, root makefile, CI | Both halves build; CI green |
| 8 | UI switchover for extract and YouTube download | End-to-end in the browser |

Torrents become their own spec after phase 8. `apps/api` is deleted then, not
before.

## Supporting pieces

**`api/.env.example`** extends auth's core and db blocks with
`ALLOWED_ORIGINS`, `DOWNLOADS_DIR`, `DOWNLOAD_WORKERS=2`,
`DOWNLOAD_CHUNK_SIZE`, `PROGRESS_FLUSH_MS=1000`, `MAX_ATTEMPTS=3`,
`FFMPEG_PATH=ffmpeg`.

**Root `makefile`** delegates to both halves: `api:run` → `make -C api run`,
`ui:dev` → `bun run --cwd ui web:dev`. Target names need auth's `api\:run`
escaping for GNU Make 3.81 — auth's makefile carries a comment explaining
exactly how that bites.

**CI** gains a Python job (uv sync → ruff check → ty check → pytest) beside the
bun jobs, with ui paths updated. The `apps/api` bun check stays until phase 8.

**Compose** gets `db` (Postgres) and `server`, in auth's shape, plus a
`downloads/` volume mount. The dockerfile adds `apt-get install -y ffmpeg` to
the base stage.

## Dependencies

```
fastapi[all]        # matches auth; pulls pydantic-settings and uvicorn
tortoise-orm[asyncpg, accel]
loguru
toml
httpx               # downloader
pytubefix           # 11.1.0
sse-starlette       # 3.4.11

dev: ruff, ty, pytest, pytest-asyncio, pre-commit
```

System dependency: `ffmpeg` (apt in the image; `brew install ffmpeg` locally).

## Out of scope

- Torrents, and everything under `/download/torrent/*`
- Global rate limits (`PATCH /global/limits`) — belongs with the torrent port
- Deleting `apps/api`
- Authentication and multi-tenancy
- Non-YouTube extractors beyond `POST /download/url`'s direct HTTP fetch
