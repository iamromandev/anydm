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

**Planned:** `apps/mobile`. A native desktop client is in progress in [`cross/`](cross/README.md), a Kotlin Multiplatform project that talks to the API. Torrent streaming and on-demand transcoding have since shipped; see [docs/architecture.md](docs/architecture.md).

For how the pieces fit together — the background loops, a task's life, how
streaming works — see [docs/architecture.md](docs/architecture.md).

## Prerequisites

- [uv](https://docs.astral.sh/uv) — pins and runs Python 3.14.6 for the API
- [Bun](https://bun.sh) — runs the UI workspace
- Docker + Compose — the API stack (`api/docker-compose.yml`) runs the service, Postgres, and rqbit
- ffmpeg — only when running the API on the host; the Docker image installs it
- [Deno](https://deno.com) 2.3 or later — only when running the API on the host; the Docker image ships 2.9.7. yt-dlp uses it to solve YouTube's JavaScript challenges. Without it YouTube still works through a deprecated fallback, with fewer formats and a warning in the log

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
| `make api-test-all` | Every API test, integration included (needs `make api-up`); not the live-site ones |
| `make api-test-live` | The live-site tests: real sites, over the internet |
| `make api-migrate` | Run database migrations in the server container, then seed the built-in search sources |
| `make api-seed` | Seed the built-in search sources only: adds any that are missing, never overwrites (needs `api-migrate` first) |
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
| `DOWNLOAD_DIR` | `./download` | Where everything lands: each category's folder is under it, and so is every torrent's (one folder per torrent, named after it). Compose mounts a named volume here |
| `DOWNLOAD_WORKERS` | `2` | Concurrent download workers (min 1) |
| `DOWNLOAD_CHUNK_SIZE` | `65536` | Read chunk size in bytes (min 1024); still paces progress updates, because the write buffer's flush timer only gets a chance to fire when a chunk arrives |
| `DOWNLOAD_SEGMENTS` | `4` | Concurrent range requests per part (min 1). `1` turns segmentation off entirely |
| `DOWNLOAD_SEGMENT_MIN_BYTES` | `16777216` | Smallest file worth splitting; below this the extra round trips cost more than the concurrency wins |
| `DOWNLOAD_WRITE_BUFFER_BYTES` | `4194304` | Bytes buffered before a positional write (min 65536), paired with a 500 ms timer |
| `DOWNLOAD_PROGRESS_FLUSH_MS` | `1000` | How often progress reaches the database (min 100) |
| `DOWNLOAD_MAX_ATTEMPTS` | `3` | Total tries per task, the first included (min 1) |
| `DOWNLOAD_MIN_FREE_BYTES` | `1073741824` | Free space `DOWNLOAD_DIR`'s disk must keep. Adding a download that would dip below it (counting its size, when known) answers 507, and a queued task waits and re-checks every 30 s instead of starting. `0` turns the guard off |
| `DOWNLOAD_RATE_LIMIT_BPS` | `0` | Bytes per second shared by every download. HTTP downloads and their segments draw from one limiter. An HLS or DASH download gets `DOWNLOAD_RATE_LIMIT_BPS ÷ DOWNLOAD_WORKERS` through yt-dlp, one fragment at a time, and what it reads is charged to that limiter, so HTTP downloads alongside make room. `0` is unlimited |
| `TORRENT_ENABLED` | `true` | Torrent routes and the monitor. Off, torrent routes answer 503 and nothing polls |
| `TORRENT_API_URL` | `http://torrent-anydm-api:3030` | rqbit's control API. `http://127.0.0.1:8031` when running the API on the host |
| `TORRENT_POLL_MS` | `1000` | How often the monitor samples the engine (min 250) |
| `TORRENT_METADATA_TIMEOUT_S` | `30` | How long resolving waits for peers to supply metadata |
| `TORRENT_REQUEST_TIMEOUT_S` | `10` | Per-call timeout against the control API |
| `TORRENT_DOWNLOAD_LIMIT_BPS` | `0` | rqbit's total download cap in bytes per second. `0` is unlimited. Pushed to rqbit by the API, again after rqbit restarts |
| `TORRENT_UPLOAD_LIMIT_BPS` | `0` | rqbit's total upload cap, seeding included. Same rules |
| `SEARCH_TIMEOUT_S` | `15` | How long each indexer has to answer a search |
| `SEARCH_LIMIT` | `100` | Results a search answers with, after merging duplicates |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg executable; used to mux a site's separate video and audio, to make MP3s, and to transcode stream segments |
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

- **Stack:** FastAPI, Tortoise ORM + asyncpg (Postgres), yt-dlp (with Deno for YouTube), httpx, sse-starlette, loguru; managed with uv
- **Endpoints:**
  - Health, extract and settings
    - `GET /health/check` — health probe
    - `POST /extract` — what a link on any site yt-dlp supports is. A page answers `type: "media"`: its title, duration, thumbnail, formats, the `presets` that can be downloaded from it today, and, for a YouTube watch link that names a playlist, `playlist_url`. A playlist, or a channel's tab, answers `type: "playlist"` with its title, uploader, `count` when the site says, and every preset; a channel home answers `type: "channel"` with its `tabs`. An unsupported link answers 400 `unsupported_url`; a live stream answers 422 `live_not_supported`
    - `GET /extract/entries?url=…&limit=…` — the videos a playlist or a channel's tab lists, as server-sent events while the site pages through them: `entries` frames of up to 100 (`index`, `id`, `url`, `title`, `duration`, `thumbnail`, `timestamp`, `available`, and `have`: `complete`, `queued` or `failed` when a download already holds that video), then `done` with the `count`, or `failed` with the error. `limit` is 1–10,000, the default. Nothing is kept, and closing the stream stops the listing. Takes `api_key` in the query, since an `EventSource` sends no header
    - `GET /settings` — how this API is configured, secrets left out, and the running yt-dlp version; read-only, since changing a setting means editing `api/.env` and restarting
    - `GET /system/disk` — total and free bytes on `DOWNLOAD_DIR`'s disk, and `DOWNLOAD_MIN_FREE_BYTES`. The UI reads the same numbers from `disk` frames on `GET /download/events`
  - Categories: named save folders under `DOWNLOAD_DIR`
    - `GET /category` — every category by position: `{id, name, slug, folder, position, builtin, count}`, `folder` relative to `DOWNLOAD_DIR` (`""` is its root) and `count` the list items in it. Downloads is built in: its folder is fixed and it can't be deleted
    - `POST /category` — add one: `{name, folder}`; answers 201. A taken name answers 409; a folder outside `DOWNLOAD_DIR` answers 400. Changing a folder later moves nothing: placed downloads keep their own
    - `POST /category/order` — `{ids}`, every category exactly once, in the new order; otherwise 422
    - `PATCH /category/{id}` — rename it, point it at another folder, or both
    - `DELETE /category/{id}` — delete a category; answers 204. One still holding downloads answers 409 with how many
    - Every add route (`POST /download/media`, `/download/url`, `/download/batch`, `/download/torrent`, `/collection`) takes an optional `category_id`: the download saves in that category's folder, Downloads when left out. An unknown category answers 422. A torrent's and a collection's folder is fixed when it is added
  - Downloads
    - `POST /download/media` — enqueue a download from any supported page (YouTube, Vimeo, X, Reddit, SoundCloud, …) for a preset. A page already in the list is refused with 409, naming that download in the error's first detail (`subject` its id, `description` its title, `fields` its status). A video a playlist or channel holds counts too, and adds a second detail for the collection (`subject` its id, `description` its title, `fields` `["collection"]`); `allow_duplicate: true` adds a second copy
    - `POST /download/url` — enqueue a direct URL download. An address already in the list, however it is spelled, is refused with 409; `allow_duplicate: true` adds it anyway
    - `POST /download/batch/preview` — the links a list (`lines`) or a pattern (`pattern`) names, without adding any. A pattern expands `[01-50]`, `[1-9]` and `[a-z]` ranges, keeping the zero-padding of the left number, several ranges multiplying with the last varying fastest; any other bracket stays as written. At most 1,000 links, otherwise 400; a range that runs backwards is a 400 too. Send exactly one of `lines` and `pattern`
    - `POST /download/batch` — add many links, each as the add box would (a `magnet:` link as a torrent with every file, a page from its site at `preset`, a link no site supports as a direct download), at most four looked at at a time. Answers 200 with one `{url, result, download_id?, message?}` per link, in order: `added`, `duplicate` (naming the download that already has it; a link repeated within the batch is one too) or `error` (a playlist among them); one failing link never stops the rest. `allow_duplicate: true` adds second copies. Same `lines`/`pattern` rules and cap as the preview
    - `GET /download` — one page of the list: standalone downloads and collections together, each item tagged `"type": "download"` or `"collection"` (a collection's videos are listed through `GET /collection/{collection_id}/downloads`). `category` (a category id) narrows it to that category's items; `page` (from 1) and `page_size` (1–100, default 50); `group` is one of the sidebar's filters, `all` (default), `downloading`, `seeding` or `completed`; `sort` is `created_at`, `title`, `total_size`, `progress` or `speed_bps` (the live speed), prefixed with `-` for descending (default `-created_at`)
    - `GET /download/summary` — how many list items each sidebar filter holds, counted in the database; a collection counts once, by its computed status. `category` (a category id) counts only that category's items
    - `GET /download/events` — SSE: `downloads` (the first page, sent on every connection), then `download` (a full download on any status change), `progress` (only what moved: bytes, `live` speed/ETA/peers, `segments`, and `files` for a torrent; a collection's video carries `collection_id`), `collection` (a collection's computed totals) and `disk`
    - `GET /download/{download_id}` — one download: its `site` or `torrent` detail, `files` (with each file's `playback`), `category`, `queue`, `limits`, `checksum` and `live` numbers
    - `GET /download/{download_id}/file/{file_index}` — serve a finished file: a torrent's by index, any other download's one file at `0`
    - `PUT /download/{download_id}/file/{file_index}/playback` — where a file was left in the player, so it resumes on any device; within its last 30 s it's marked watched instead
    - `GET /download/{download_id}/media` — what the player needs to play a finished download: its MIME type for `canPlayType`, duration, and file URL (`?file_index=` for a torrent's file, else its largest media file), with its audio and subtitle tracks
    - `GET /download/{download_id}/subtitles/{track}.vtt` — one of those subtitle tracks, embedded or a subtitle file beside it, whole, as WebVTT, for a file the browser plays itself (`?file_index=` as above)
    - `PUT /download/{download_id}/category` — `{category_id}`: move it to another category. A finished download's file and its subtitles move into the category's folder (a name already taken there gets `_` and the first 8 characters of its id); an unfinished one just changes category. 409 while it downloads or muxes; 422 for a torrent (it stays where it was added) and for a collection's video (move the collection)
    - `PUT /collection/{collection_id}/category` — `{category_id}`: move the collection's folder, whole, under the category's folder, with every video. 409 while one of its videos downloads
    - `POST /download/{download_id}/pause` · `POST /download/{download_id}/resume`
    - `DELETE /download/{download_id}` — remove a download and, by default, its files. `delete_files=false` keeps the files and drops only the row; that is accepted only for a `completed` or `seeding` download, and answered 409 otherwise
    - `POST /download/bulk` — act on the whole list: `{"action": "pause_all" | "resume_all" | "clear_finished"}`. For `clear_finished`, `"delete_files": true` takes finished downloads' files too; a failed download's partial file goes either way
  - Collections (a playlist or a channel's tab, added as one)
    - `POST /collection` — add a listing's chosen videos as one collection: `url`, `extractor`, `external_id`, `title`, `channel_tab`, `preset` (a ceiling for each video), and `entries` as `GET /extract/entries` listed them. Answers 201 with the collection, whose `folder` names where its videos finish (numbered `01_…`, unless it is a channel's tab). Nothing is extracted until each video starts; more than 10,000 videos answers 422 `payload_too_large`. Adding a listing again whose `extractor` and `external_id` match a collection that isn't removed joins it: new videos go into its folder after its last, a video it holds isn't added twice, and one ticked while paused or failed goes back in the queue
    - `GET /collection/{collection_id}` — one collection, with `counts` (`total`, `complete`, `active`, `downloading`, `paused`, `failed`, `watched`); its status, progress, bytes and speed are computed from its videos
    - `GET /collection/{collection_id}/downloads?page=…&page_size=…` — one page of its videos, in listing order
    - `POST /collection/{collection_id}/pause` · `POST /collection/{collection_id}/resume` — act on its videos
    - `DELETE /collection/{collection_id}` — remove it and every video: `delete_files=true` (default) takes its folder, `false`, accepted in any state, keeps what finished
  - Torrents
    - `POST /download/torrent/resolve` — inspect a magnet or `.torrent` without downloading
    - `POST /download/torrent` — enqueue a torrent with a file selection. A torrent already in the list is refused with 409; it can only be held once, so `allow_duplicate` is a 400
    - `POST /download/{download_id}/seed/stop` — stop seeding, keep the files
  - Search and browse the latest releases (every source below, each asked at once)
    - `GET /search/sources` — whether search is on, and the sources' names (enabled built-ins and indexers); never their URLs or keys. Also `youtube`, which is always true
    - `GET /search/youtube?q=…&limit=…` — YouTube's videos for some words, found with yt-dlp (no key, no setting). `q` is 2–200 characters; `limit` is 1–30, default 20. Answers `results` (`title`, `url` of the watch page, `channel`, `duration` in seconds, `thumbnail`, `views`, and `published`, which is approximate: YouTube only says "3 weeks ago"; a field it doesn't know is left out) and `took_ms`. A success is cached for a minute; a failure is 502 `Extraction failed: …` and is not cached. Add a result by sending its `url` to the usual download routes
    - `GET /search?q=…&category=…&fresh=1` — ask every source that can answer at once. `q` is 2–200 characters, or empty to browse each source's latest releases (newest first, cached for five minutes; `fresh=1` skips the cache); `category` is `all`, `movies`, `tv`, `music`, `software`, `books` or `other`. Answers `results` (one per torrent, merged across sources by info hash: `title`, `size`, `seeders`, `leechers`, `published`, `category`, `info_hash`, `magnet` or `link` — **exactly one of the two**, the copy the server picked, and `copy_from`, the source it came from — and `indexers`, every source that had the result), `errors` (a source that failed, and why), `asked` (every source the request went to) and `took_ms`. An indexer's `.torrent` link wins over a magnet, because the link carries the passkey a private tracker needs and a magnet built from a bare info hash does not; among equals the most seeded source wins. 404 `search_disabled` with no sources; 502 `search_failed` when every source asked failed
    - `GET /source` — every source: registry rows first in registry order, then created ones oldest-first. Each answers `id`, `name`, `kind`, `enabled`, `base_url`, `api_key_masked` (a key's first and last four characters, or `********` for a short one; missing when no key is stored), `deletable` (false for registry rows) and `default_url` (missing for Torznab rows, which have no default)
    - `POST /source` — add a source (`name`, `kind`, `base_url`, optional `api_key` for Torznab, `enabled` defaulting to true); answers 201. Names are lowercase letters, digits and dashes; a key on a non-Torznab kind is refused
    - `PATCH /source/{id}` — turn a source on or off, point it at another address, or set, replace or clear its key (an empty `api_key` clears it); at least one field is required (422)
    - `DELETE /source/{id}` — delete a created source; answers 204. Registry rows are refused with 422 `source_not_deletable`: switch one off instead. Unknown ids are 404 `source_not_found` on every `/source/{id}` route
    - `POST /source/{id}/reset` — put a built-in-kind source back to its registry address and state
    - `POST /source/{id}/test` — ask a source a small question (optionally at unsaved `base_url`/`api_key`) and answer `ok`, `count`, `took_ms` and a `message`; a source that fails is a 200 with the reason. Never returns a result's contents
    - `POST /source/test` — ask an unsaved source whether it answers, before adding it
    - Sources live in the database, not in `.env`: the built-in public sources (apibay, Nyaa, EZTV) and any number of Torznab indexers (Prowlarr, Jackett), each with on or off, an address, and — for Torznab — an API key that is stored masked and never logged. They are seeded when the API starts, by `make api-migrate` and by `make api-seed` (both run `python -m scripts.seed_sources` in the server container), which only add what has no row and never overwrite an edited one. Manage them in the web app's Sources view, beside Search: switch a source, edit its address, add an indexer, delete one you added, and Test an address — saved or not — before committing to it. **Upgrading:** `SEARCH_SOURCES`, `SEARCH_SOURCE_URLS`, `SEARCH_INDEXERS` and `SEARCH_INDEXER_KEYS` no longer do anything (leaving them in `.env` is harmless); re-add any indexers in the app. The one-time `SEARCH_INDEXERS` import rides with the rebuilt migration chain.
    - `POST /search/torrent` — fetch a result's `.torrent` from its indexer: answers `torrent` (base64), or `magnet` when the indexer redirects to one. The link must share a configured indexer's scheme, host and port (400 `link_not_from_indexer`); over 10 MB is 413, and anything but a torrent file is 422 `not_a_torrent`
  - Streaming (independent of downloading — nothing is kept)
    - `POST /stream/start` — open a session for a page on any site yt-dlp supports (at up to 1080p, from its plain files, or its HLS when it has nothing else), a media URL, a magnet or a `.torrent` (with `file_index`, that one of its files rather than the largest), or a finished download read from disk (`download_id`, and `file_index` for a torrent); `audio_language` picks the audio track, or `audio_track` names one. A torrent's answer also carries `files` — its media files, so a client can offer them without resolving the torrent again; every other source's is empty
    - `POST /stream/{session_id}/quality` — a new session at another of `qualities` (`null` for the default: a site's 1080p pick, or a file as it is), built from this one, which keeps playing until the player stops it; `/stream/start` takes `quality` too
    - `POST /stream/{session_id}/audio` — a new session playing another of `audio_tracks`, built from this one, which keeps playing until the player stops it
    - `GET /stream/events` — SSE session status, including swarm numbers
    - `GET /stream/{session_id}/playlist.m3u8` — the HLS playlist
    - `GET /stream/{session_id}/segment_{index}.ts` — one segment, transcoded on request
    - `GET /stream/{session_id}/subtitles/{track}/segment_{index}.vtt` — one segment's cues for one of `subtitle_tracks`, as WebVTT at the source's own times
    - `GET /stream/{session_id}/subtitles/{track}.vtt` — one of `subtitle_tracks` that's a subtitle file beside the video, or a page's own subtitles or captions (`external`), whole, as WebVTT; a site download saves those beside its file as `<name>.<lang>.vtt`
    - `DELETE /stream/{session_id}` — end the session
- **Workers:** a pool started in the app lifespan claims queued tasks, resumes from `.part` files, and requeues orphans left in-flight by a previous process
- **Segmented transfers:** direct and YouTube downloads are fetched over `DOWNLOAD_SEGMENTS` concurrent range requests written positionally into one preallocated `.part`, with per-segment watermarks in `segment` so a pause or a crash resumes mid-segment. A server that refuses ranges, a file below `DOWNLOAD_SEGMENT_MIN_BYTES`, or `DOWNLOAD_SEGMENTS=1` all fall back to the original single-stream path — which is also the rollback switch. HLS and DASH formats are playlists of fragments rather than one file, so they go through yt-dlp's own downloader instead, which resumes from its own record of the fragments on disk.

  How much this wins depends entirely on where the bottleneck is. Against a server that caps each connection it is close to linear (measured 0.12 → 0.49 MB/s, 4.1×, on a test server throttled to 120 KB/s per connection). Against a mirror that does not, it is nearly nothing, because one connection already saturates the link (measured 9.09 → 10.37 MB/s on a Debian mirror, with 8 segments no better than 4).
- **Assembling a download:** a site's separate video and audio are muxed without re-encoding, into the container that carries both codecs:
  - MP4 for H.264, HEVC, AV1 or VP9 with AAC, MP3, Opus, AC-3 or E-AC-3
  - WebM for VP8, VP9 or AV1 with Opus or Vorbis
  - MKV for anything else, a codec yt-dlp does not name included

  The file's extension says which. Every site recorded so far comes out as MP4. An HLS download that is a single part arrives as MPEG-TS and is remuxed, also without re-encoding, into the container its codecs fit. An MP3 is transcoded from any audio codec.
- **Torrents:** a pinned rqbit runs as its own Compose service and owns every torrent transfer. The API resolves a magnet to a file list, creates one task per torrent with child `file` rows, and a monitor polls the engine and mirrors progress onto them. A finished torrent seeds until it is told to stop. rqbit's control API has no authentication, so it is published on loopback only; port 4240 is published for incoming peers. Torrents never occupy a download worker slot.
- **Streaming:** playing is a separate path from downloading and keeps nothing. A page on a site is extracted first and played from its formats, video and audio as separate inputs, and its URLs are resolved again if they expire mid-play. A session probes the source, then serves HLS whose segments are transcoded when a player asks for them, `STREAM_READAHEAD_SEGMENTS` ahead of the one being fetched. A page with only HLS formats has its playlists read instead of probed, and each segment is cut from a playlist of just the fragments it covers. Idle sessions are swept after `STREAM_IDLE_TIMEOUT_S`, and a reaper deletes rqbit torrents no task or session owns.
- **Migrations:** Tortoise's built-in migrations under `src/data/db/migration`, applied by `python -m scripts.migrate` — the compose command runs it before uvicorn

### `ui/apps/web`

- **Stack:** Qwik 2, Qwik Router, Vite 8, Tailwind 4, TypeScript
- **Routes:** `/` (home — URL/torrent input, task list, sidebar filters); `routesDir` is `src/route`
- **Entry:** `src/entry.ssr.tsx`, `src/entry.csr.tsx`, `src/root.tsx`
- **API client:** `src/lib/api` — envelope unwrapping, download normalization; the home route fetches `GET /download` on load and after each action, and takes live updates from `/download/events`

## Keeping sites working

Sites change how they serve media, and yt-dlp releases to keep up, sometimes several times a month. When a site that used to work stops extracting, the usual cause is a yt-dlp that has fallen behind.

- **Pinned on purpose:** `api/pyproject.toml` pins yt-dlp exactly, so it never changes under a running stack without someone deciding. yt-dlp-ejs, which solves YouTube's challenges with Deno, follows it: yt-dlp's `default` extra pins the version it needs.
- **Impersonating a browser:** yt-dlp's `curl-cffi` extra brings `curl_cffi`, so yt-dlp can present itself as a browser when a site refuses its own requests. Dailymotion does that from some networks, and without it about half of its extractions failed. `curl_cffi` floats within the range the pinned yt-dlp supports.
- **Noticed weekly:** the live-site check (see [CI](#ci)) goes to the sites themselves every Monday. A red run is the cue to move the pin.
- **Moved on purpose:** Dependabot ([.github/dependabot.yml](.github/dependabot.yml)) opens a pull request each week when a new yt-dlp is out, and for nothing else. CI runs the suite against it. Merge it, then rebuild with `make api-build` and `make api-up`.
- **By hand,** for a fix that cannot wait: in `api/`, `uv add "yt-dlp[default,curl-cffi]==<version>"` moves the pin, then rebuild the image. `uv lock --upgrade-package yt-dlp` does not move it, because the pin is exact.
- **Deno** is pinned by its image tag in `api/dockerfile` (`denoland/deno:bin-…`) and moves by hand.
- **What is running:** `GET /settings` reports `yt_dlp_version`, which the Settings modal shows as "yt-dlp version".

## Current limitations

- HLS, DASH and the other fragmented formats download through yt-dlp's own downloader. Their cards show no segment strip, and their size is an estimate until the end. The player plays HLS when a page has nothing else, but fetches its fragments without the site's headers, which none of the recorded sites needs. It doesn't play DASH, f4m or ISM, whose URL is a manifest of every rendition: a page offering nothing else answers 422 in the player, and still downloads. A playlist or a channel lists its videos in the add box, and each can be downloaded or played on its own. The API adds many at once as a collection (`POST /collection`); choosing them in the UI comes next. Live streams, and videos that need a login, are not supported.
- Running the API on the host with `make api-run` while rqbit runs in Docker means the two disagree about paths. Torrents download, but the host-run API cannot read the finished files. Use `make api-up` for torrent work.
- Seeders and leechers are never shown: rqbit reports connected peers and does not split a swarm.
- Authentication is one optional shared key (`API_KEY`), off by default. Without it, CORS is the only gate, which does nothing for a direct request, so do not expose an API with no key set beyond a trusted network.

## CI

GitHub Actions runs on pushes to `main` and on pull requests, in two parallel jobs:

- **api** — `uv sync`, then ruff, ty, and the unit suite.
- **ui** — a frozen Bun lockfile, then the format check, typecheck, unit tests, and production build.

The database suite (`make api-test-all`, against `make api-up`) and the cross client (`make cross-check`) are not in CI: run them locally before a change that touches them.

See [.github/workflows/ci.yml](.github/workflows/ci.yml).

A separate workflow, [.github/workflows/live.yml](.github/workflows/live.yml), runs the tests marked `network` against real sites, weekly (Mondays, 06:00 UTC) and on demand, but never on pull requests: sites break on their own schedule, and that should not block unrelated work. For each site it extracts one page and fetches the first MiB of what a download would, through the same engine, or, for a fragmented format, through yt-dlp's downloader. A red run is the cue to bump yt-dlp. A site that refuses the runner and asks it to sign in skips with that reason, since no bump clears it: YouTube does this to cloud IPs at times, and Reddit blocks them outright, so from CI Reddit is only checked when it lets the runner through. Run the same tests by hand with `make api-test-live`.
