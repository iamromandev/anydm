/**
 * What the Search view shows, decided without the DOM so it is tested with plain values.
 */

import type {
    FoundTorrent,
    IndexerError,
    SearchCategory,
    VideoResult,
} from "@/lib/api/search";

export const CATEGORY_OPTIONS: Array<{ id: SearchCategory; label: string }> = [
    { id: "all", label: "All" },
    { id: "movies", label: "Movies" },
    { id: "tv", label: "TV" },
    { id: "music", label: "Music" },
    { id: "software", label: "Software" },
    { id: "books", label: "Books" },
    { id: "other", label: "Other" },
];

export type SearchMode = "browse" | "search";

export type SortKey =
    "title" | "size" | "seeders" | "leechers" | "published" | "indexer";
export type SortState = { key: SortKey; descending: boolean };

/** Numbers and dates read best biggest-or-newest first; words, A to Z. */
const STARTS_DESCENDING: Record<SortKey, boolean> = {
    title: false,
    size: true,
    seeders: true,
    leechers: true,
    published: true,
    indexer: false,
};

export function nextSort(current: SortState, key: SortKey): SortState {
    if (current.key === key) return { key, descending: !current.descending };
    return { key, descending: STARTS_DESCENDING[key] };
}

function valueOf(result: FoundTorrent, key: SortKey): string | number | null {
    switch (key) {
        case "title":
            return result.title.toLowerCase();
        case "size":
            return result.sizeBytes;
        case "seeders":
            return result.seeders;
        case "leechers":
            return result.leechers;
        case "published":
            return result.published ? Date.parse(result.published) : null;
        case "indexer":
            return (result.indexers[0] ?? "").toLowerCase();
    }
}

/** A sorted copy; an unknown value sorts last whichever way the column runs. */
export function sortFound(
    results: FoundTorrent[],
    sort: SortState,
): FoundTorrent[] {
    const direction = sort.descending ? -1 : 1;
    return [
        ...results,
    ].sort((a, b) => {
        const x = valueOf(a, sort.key);
        const y = valueOf(b, sort.key);
        if (x === null && y === null) return 0;
        if (x === null) return 1;
        if (y === null) return -1;
        if (x < y) return -1 * direction;
        if (x > y) return 1 * direction;
        return 0;
    });
}

export function formatSize(bytes: number | null): string {
    if (bytes === null) return "—";
    if (bytes === 0) return "0 B";
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let size = bytes;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
        size /= 1024;
        unit++;
    }
    return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

const AGE_STEPS: Array<
    [
        number,
        string,
    ]
> = [
    [
        365 * 24 * 3600,
        "year",
    ],
    [
        30 * 24 * 3600,
        "month",
    ],
    [
        24 * 3600,
        "day",
    ],
    [
        3600,
        "hour",
    ],
    [
        60,
        "minute",
    ],
];

export function formatAge(published: string | null, now: number): string {
    if (!published) return "—";
    const at = Date.parse(published);
    if (Number.isNaN(at)) return "—";
    const seconds = Math.max(0, (now - at) / 1000);
    for (const [
        size,
        name,
    ] of AGE_STEPS) {
        const n = Math.floor(seconds / size);
        if (n >= 1) return `${n} ${name}${n === 1 ? "" : "s"}`;
    }
    return "just now";
}

export function seederTone(seeders: number | null): "good" | "some" | "none" {
    if (seeders !== null && seeders >= 10) return "good";
    if (seeders !== null && seeders >= 1) return "some";
    return "none";
}

export const SWARM_TICKS = 5;

/** How many of the swarm's 5 ticks light up: log-scaled seeders, and a dead or unknown swarm still shows 1. */
export function swarmTicks(seeders: number | null): number {
    if (seeders === null || seeders < 1) return 1;
    return Math.min(SWARM_TICKS, 1 + Math.floor(Math.log10(seeders + 1) * 1.4));
}

export const SORT_OPTIONS: Array<{
    id: string;
    label: string;
    sort: SortState;
}> = [
    {
        id: "seeders",
        label: "Most seeded",
        sort: { key: "seeders", descending: true },
    },
    {
        id: "published",
        label: "Newest",
        sort: { key: "published", descending: true },
    },
    { id: "size", label: "Biggest", sort: { key: "size", descending: true } },
    { id: "title", label: "Name", sort: { key: "title", descending: false } },
];

/** The menu entry for a sort state; a state outside the menu shows as its nearest key. */
export function sortOptionId(sort: SortState): string {
    return SORT_OPTIONS.find((o) => o.sort.key === sort.key)?.id ?? "seeders";
}

export function indexerLabel(indexers: string[]): string {
    if (indexers.length <= 1) return indexers[0] ?? "";
    return `${indexers[0]} +${indexers.length - 1}`;
}

export function statusLine(
    count: number,
    indexerCount: number,
    tookMs: number,
    mode: SearchMode,
): string {
    const noun = mode === "browse" ? "release" : "result";
    const results = `${count} ${noun}${count === 1 ? "" : "s"}`;
    const indexers = `${indexerCount} indexer${indexerCount === 1 ? "" : "s"}`;
    const line = `${results} from ${indexers} · ${(tookMs / 1000).toFixed(1)} s`;
    return mode === "browse" ? `Latest · ${line}` : line;
}

export function errorChip(error: IndexerError): string {
    return `${error.indexer}: ${error.message}`;
}

/** Nothing typed browses the latest; 2 or more characters search; 1 does neither. */
export function modeFor(q: string): SearchMode | "blocked" {
    const length = q.trim().length;
    if (length === 0) return "browse";
    return length >= 2 ? "search" : "blocked";
}

export function canRun(q: string, busy: boolean): boolean {
    return !busy && modeFor(q) !== "blocked";
}

export function defaultSort(mode: SearchMode): SortState {
    return mode === "browse"
        ? { key: "published", descending: true }
        : { key: "seeders", descending: true };
}

export function emptyText(mode: SearchMode, searched: string): string {
    return mode === "browse"
        ? "Nothing recent from these indexers. Check that a source is turned on in Settings."
        : `Nothing for “${searched}”. Try fewer words, or turn on more sources in Settings.`;
}

/** `m:ss`, or `h:mm:ss` from an hour; "" when the site gave none (a live stream). */
export function formatDuration(seconds: number | null): string {
    if (seconds === null) return "";
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    const pad = (n: number) => String(n).padStart(2, "0");
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function formatViews(views: number | null): string {
    if (views === null) return "";
    if (views === 1) return "1 view";
    if (views < 1000) return `${views} views`;
    const [
        value,
        unit,
    ] =
        views >= 1_000_000
            ? [
                  views / 1_000_000,
                  "M",
              ]
            : [
                  views / 1000,
                  "K",
              ];
    const text = value >= 10 ? String(Math.round(value)) : value.toFixed(1);
    return `${text.replace(/\.0$/, "")}${unit} views`;
}

/** What follows the channel in a video row; unknown values are left out. */
export function videoFacts(video: VideoResult, now: number): string[] {
    const facts: string[] = [];
    const views = formatViews(video.views);
    if (views) facts.push(views);
    if (video.published) facts.push(formatAge(video.published, now));
    return facts;
}

/** Two or more characters, and nothing already running. */
export function canSearchVideos(q: string, busy: boolean): boolean {
    return !busy && q.trim().length >= 2;
}

export const VIDEO_EMPTY_IDLE = "Search YouTube for a video.";

export function videoEmptyText(searched: string): string {
    return `Nothing for “${searched}”. Try fewer words.`;
}
