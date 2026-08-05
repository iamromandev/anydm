# AnyDM

Any Download Manager — a Bun monorepo for extracting and downloading content from URLs and torrent files.

## Repository structure

```
anydm/
├── apps/
│   ├── ui/        # Qwik 2 + Vite UI
│   └── api/       # Bun + Hono API
├── bun.lock
├── bunfig.toml
└── package.json
```

**Planned (not in repo yet):** `packages/` (shared UI, utils, types), `apps/desktop` (Tauri), `apps/mobile`.

## Prerequisites

- [Bun](https://bun.sh) (latest stable recommended)

## Install

```bash
bun install
```

## Development

Run each app in a separate terminal from the repo root:

```bash
# UI (format + typecheck + Vite SSR dev server, typically http://localhost:5173)
bun run ui:dev

# API (format + typecheck + Bun hot reload, http://localhost:3000)
bun run api:dev
```

Other root scripts:

| Script | Description |
|--------|-------------|
| `bun run ui:build` | Production build for the UI app |
| `bun run ui:fmt` / `api:fmt` | Format with Prettier |
| `bun run ui:fmt.chk` / `api:fmt.chk` | Check formatting (used in CI) |
| `bun run ui:chk` / `api:chk` | Typecheck with `tsc` |
| `bun run clean` | Remove root `node_modules` and web artifacts |

## Environment variables

### UI (`apps/ui`)

Copy the example env file before running the UI app:

```bash
cp apps/ui/.env.example apps/ui/.env.local
```

| Variable | Description |
|----------|-------------|
| `PUBLIC_BASE_URL` | Base URL of the AnyDM API (default in example: `http://localhost:3000`) |

`.env.local` is gitignored; see [apps/ui/.env.example](apps/ui/.env.example).

### API (`apps/api`)

Copy the example env file before running the API app:

```bash
cp apps/api/.env.example apps/api/.env.local
```

| Variable | Description |
|----------|-------------|
| `PORT` | Port the API listens on (Bun serves the default Hono export; default `3000`) |
| `ALLOWED_ORIGINS` | Comma-separated CORS origins allowed to call the API (default: `http://localhost:5173,http://127.0.0.1:5173`) |

`.env.local` is gitignored; see [apps/api/.env.example](apps/api/.env.example).

## Apps

### `apps/ui`

- **Stack:** Qwik 2, Qwik Router, Vite 7, Tailwind 4
- **Routes:** `/` (home — URL input and Extract button), layout with header/menu/footer
- **Entry:** `src/entry.ssr.tsx`, `src/entry.csr.tsx`, `src/root.tsx`

### `apps/api`

- **Stack:** Hono, WebTorrent, ytdl-core (not wired yet)
- **Endpoints:**
  - `GET /` — health text
  - `POST /download` — torrent upload (handler stubbed; `downloadTorrent()` implemented but not returned)

## Current limitations

- UI validates URLs but does not call the API yet (only reads `PUBLIC_BASE_URL`).
- Torrent `POST /download` success response is commented out.
- YouTube / `ytdl-core` is a dependency only; no routes use it.
- Navigation menu links beyond Home are placeholders (no routes yet).

## CI

GitHub Actions runs on push and pull request: install with frozen lockfile, then format check and TypeScript check for both apps. See [.github/workflows/ci.yml](.github/workflows/ci.yml).
