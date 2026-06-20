# Download Feature Implementation Plan

## Overview

Two features to bring download functionality to AnyDM's web UI:

1. **Download Initiation from Format List** — users can click a format in the extracted info panel and download the file
2. **Download Management Page** — a `/download` route showing active/completed download tasks with progress

---

## Feature 1: Download Initiation from Format List

### Service: `POST /download/youtube`

**File: `apps/service/src/route/download.ts`**

- Add a new route handler alongside the existing torrent logic
- Accept `{ url: string, itag: number }` JSON body
- Use `youtubei.js` client to resolve the stream URL for the given format
- Return `{ success: true, downloadUrl: string, filename: string }`

**New helper in `apps/service/src/route/extract.ts`:**

```typescript
export async function getYouTubeDownloadUrl(
    url: string,
    itag: number,
): Promise<string>;
```

- Extracts video ID from URL
- Gets Innertube instance
- Fetches video info and finds format by itag
- Returns the stream URL (handling signatureCipher if needed)

### Web: Download button per format

**File: `apps/web/src/route/info.tsx`**

- Add `videoUrl: string` prop for the original URL
- Add a "Download" button per format row in the formats list
- On click:
    1. Set format-specific loading state
    2. `POST /download/youtube` with `{ url: videoUrl, itag: format.itag }`
    3. Receive `downloadUrl` and trigger browser download via `<a download>` or `window.open`
    4. Clear loading state

**File: `apps/web/src/route/index.tsx`**

- Pass `store.url` as `videoUrl` prop to `<Info>`

---

## Feature 2: Download Management Page at `/download`

### Service: Download tracking

**New file or inline in `apps/service/src/index.ts`:**

```typescript
type DownloadTask = {
    id: string;
    url: string;
    title: string;
    format: string;
    status: "downloading" | "complete" | "failed";
    progress: number; // 0–100
    error?: string;
};
```

In-memory `Map<string, DownloadTask>` store.

**Endpoints:**

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/download/youtube` | Initiate download (creates a task) |
| `GET` | `/download` | List all tasks |
| `GET` | `/download/:id` | Single task status |

### Web: New route

**New directory: `apps/web/src/route/download/`**

```
route/download/
├── index.tsx       # Route page — list of download tasks
└── field.css       # Page-level styles
```

**`index.tsx`:**

- Fetch download list from `GET /download`
- Poll periodically via `useVisibleTask$` + `setInterval`
- Display per task: title, format tag, progress bar (Tailwind), status badge
- Empty state: "No downloads yet. Extract a video and download a format."
- Use `useSignal` for the list, `useStore` for card state

### Navigation update

**File: `apps/web/src/component/menu/field.tsx`**

- Add `<li><a href="/download" class="menu-link">Downloads</a></li>` to the menu list

---

## Architecture & Conventions

### Component pattern (existing)

```
component/xyz/
├── index.tsx     # Re-export
├── field.tsx     # component$() implementation
└── field.css     # Tailwind @apply styles
```

### Qwik patterns to follow

| Practice | Usage |
|---|---|
| `component$()` | All components |
| `$()` | Event handlers passed as props |
| `useSignal` | Simple reactive state (loading, toggle) |
| `useStore` | Complex objects (form data, results) |
| `useVisibleTask$` | Client-only effects (polling) |
| `Slot` | Children composition |
| `PropsOf<T>` | Extending native element props |

### Styling

- Tailwind 4 via `@tailwindcss/vite`
- Each CSS file: `@import "tailwindcss"` then `@apply` rules
- Component-scoped CSS files

### Formatting

- 4-space tabs, 80 char width
- Double quotes, trailing commas
- `prettier-plugin-tailwindcss`, `prettier-plugin-multiline-arrays`

---

## Files to Create / Modify

| File | Action |
|---|---|
| `apps/service/src/route/extract.ts` | Add `getYouTubeDownloadUrl()` helper |
| `apps/service/src/route/download.ts` | Add `POST /download/youtube` handler + `GET /download` and `GET /download/:id` |
| `apps/web/src/route/info.tsx` | Add download button per format + `videoUrl` prop |
| `apps/web/src/route/index.tsx` | Pass `store.url` to Info component |
| `apps/web/src/route/download/index.tsx` | **Create** — download management page |
| `apps/web/src/route/download/field.css` | **Create** — page styles |
| `apps/web/src/component/menu/field.tsx` | Add "Downloads" nav link |

---

## Future Considerations

- Torrent download integration (already stubbed in service)
- Non-YouTube URL support (general file download, other platforms)
- Download cancellation via `POST /download/:id/cancel`
- Persistent download history (localStorage or DB)
