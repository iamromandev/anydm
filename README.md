# AnyDM

Any Download Manager — a Bun monorepo for extracting and downloading content from URLs and torrent files.

## Repository structure

```
anydm/
├── apps/
│   ├── web/       # Qwik 2 + Vite UI
│   └── service/   # Bun + Hono API
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
# Web UI (format + typecheck + Vite SSR dev server, typically http://localhost:5173)
bun run web:dev

# API (format + typecheck + Bun hot reload, http://localhost:3000)
bun run service:dev
```

Other root scripts:

| Script | Description |
|--------|-------------|
| `bun run web:build` | Production build for the web app |
| `bun run web:fmt` / `service:fmt` | Format with Prettier |
| `bun run web:fmt.chk` / `service:fmt.chk` | Check formatting (used in CI) |
| `bun run web:chk` / `service:chk` | Typecheck with `tsc` |
| `bun run clean` | Remove root `node_modules` and web artifacts |

## Environment variables

### Web (`apps/web`)

Copy the example env file before running the web app:

```bash
cp apps/web/.env.example apps/web/.env.local
```

| Variable | Description |
|----------|-------------|
| `PUBLIC_BASE_URL` | Base URL of the AnyDM API (default in example: `http://localhost:3000`) |

`.env.local` is gitignored; see [apps/web/.env.example](apps/web/.env.example).

### Service (`apps/service`)

No env vars required for local dev. The API listens on port **3000** when started with `bun run dev` (Bun serves the default Hono export).

## Apps

### `apps/web`

- **Stack:** Qwik 2, Qwik Router, Vite 7, Tailwind 4
- **Routes:** `/` (home — URL input and Extract button), layout with header/menu/footer
- **Entry:** `src/entry.ssr.tsx`, `src/entry.csr.tsx`, `src/root.tsx`

### `apps/service`

- **Stack:** Hono, WebTorrent, ytdl-core (not wired yet)
- **Endpoints:**
  - `GET /` — health text
  - `POST /download` — torrent upload (handler stubbed; `downloadTorrent()` implemented but not returned)

## Current limitations

- Web UI validates URLs but does not call the API yet (only reads `PUBLIC_BASE_URL`).
- Torrent `POST /download` success response is commented out.
- YouTube / `ytdl-core` is a dependency only; no routes use it.
- Navigation menu links beyond Home are placeholders (no routes yet).

## CI

GitHub Actions runs on push and pull request: install with frozen lockfile, then format check and TypeScript check for both apps. See [.github/workflows/ci.yml](.github/workflows/ci.yml).
