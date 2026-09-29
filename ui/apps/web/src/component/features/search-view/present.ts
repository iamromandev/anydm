/**
 * What the Search view shows, decided without the DOM so it is tested with plain values.
 */

import type {
    FoundTorrent,
    IndexerError,
    SearchCategory,
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
        ? "Nothing recent from these indexers"
        : `No results for “${searched}”`;
}
