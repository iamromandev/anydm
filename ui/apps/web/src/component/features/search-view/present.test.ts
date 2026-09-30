import { describe, expect, it } from "bun:test";

import type { FoundTorrent, VideoResult } from "@/lib/api/search";

import {
    canRun,
    canSearchVideos,
    defaultSort,
    emptyText,
    errorChip,
    formatAge,
    formatDuration,
    formatSize,
    formatViews,
    indexerLabel,
    modeFor,
    nextSort,
    seederTone,
    SORT_OPTIONS,
    sortFound,
    sortOptionId,
    statusLine,
    swarmTicks,
    VIDEO_EMPTY_IDLE,
    videoEmptyText,
    videoFacts,
} from "./present";

const found = (
    title: string,
    fields: Partial<FoundTorrent> = {},
): FoundTorrent => ({
    title,
    sizeBytes: null,
    seeders: null,
    leechers: null,
    published: null,
    category: "other",
    infoHash: null,
    magnet: null,
    link: "http://p/dl",
    indexers: [
        "p",
    ],
    ...fields,
});

describe("sortFound", () => {
    const rows = [
        found("b", { seeders: 5, sizeBytes: 10 }),
        found("a", { seeders: null, sizeBytes: 30 }),
        found("c", { seeders: 40, sizeBytes: 20 }),
    ];

    it("puts the most seeded first, and unknown last whichever way", () => {
        expect(
            sortFound(rows, { key: "seeders", descending: true }).map(
                (r) => r.title,
            ),
        ).toEqual([
            "c",
            "b",
            "a",
        ]);
        expect(
            sortFound(rows, { key: "seeders", descending: false }).map(
                (r) => r.title,
            ),
        ).toEqual([
            "b",
            "c",
            "a",
        ]);
    });

    it("sorts names without caring about case, and sizes by bytes", () => {
        expect(
            sortFound(
                [
                    found("b"),
                    found("A"),
                    found("c"),
                ],
                { key: "title", descending: false },
            ).map((r) => r.title),
        ).toEqual([
            "A",
            "b",
            "c",
        ]);
        expect(
            sortFound(rows, { key: "size", descending: true }).map(
                (r) => r.title,
            ),
        ).toEqual([
            "a",
            "c",
            "b",
        ]);
    });

    it("doesn't change the list it was given", () => {
        const copy = [
            ...rows,
        ];
        sortFound(rows, { key: "title", descending: false });
        expect(rows).toEqual(copy);
    });
});

describe("nextSort", () => {
    it("flips direction on the same column, and a new column starts where it reads best", () => {
        expect(
            nextSort({ key: "seeders", descending: true }, "seeders"),
        ).toEqual({ key: "seeders", descending: false });
        expect(nextSort({ key: "seeders", descending: true }, "title")).toEqual(
            { key: "title", descending: false },
        );
        expect(nextSort({ key: "title", descending: false }, "size")).toEqual({
            key: "size",
            descending: true,
        });
    });
});

describe("formatting", () => {
    it("writes sizes and ages as the list does", () => {
        expect(formatSize(725614592)).toBe("692.0 MB");
        expect(formatSize(null)).toBe("—");
        const now = Date.parse("2026-09-29T10:00:00Z");
        expect(formatAge("2026-09-26T10:00:00Z", now)).toBe("3 days");
        expect(formatAge("2026-09-29T05:00:00Z", now)).toBe("5 hours");
        expect(formatAge("2026-09-29T09:59:00Z", now)).toBe("1 minute");
        expect(formatAge("2025-09-29T10:00:00Z", now)).toBe("1 year");
        expect(formatAge(null, now)).toBe("—");
    });

    it("colours seeders by how likely the download is to finish", () => {
        expect(seederTone(10)).toBe("good");
        expect(seederTone(9)).toBe("some");
        expect(seederTone(1)).toBe("some");
        expect(seederTone(0)).toBe("none");
        expect(seederTone(null)).toBe("none");
    });

    it("names the first indexer and counts the rest", () => {
        expect(
            indexerLabel([
                "prowlarr-1",
            ]),
        ).toBe("prowlarr-1");
        expect(
            indexerLabel([
                "prowlarr-1",
                "jackett-all",
                "x",
            ]),
        ).toBe("prowlarr-1 +2");
    });

    it("says what came back, and why an indexer didn't", () => {
        expect(statusLine(42, 3, 912, "search")).toBe(
            "42 results from 3 indexers · 0.9 s",
        );
        expect(statusLine(1, 1, 50, "search")).toBe(
            "1 result from 1 indexer · 0.1 s",
        );
        expect(statusLine(42, 3, 912, "browse")).toBe(
            "Latest · 42 releases from 3 indexers · 0.9 s",
        );
        expect(statusLine(1, 1, 50, "browse")).toBe(
            "Latest · 1 release from 1 indexer · 0.1 s",
        );
        expect(
            errorChip({
                indexer: "jackett-all",
                message: "timed out after 15 s",
            }),
        ).toBe("jackett-all: timed out after 15 s");
    });
});

describe("modes", () => {
    it("browses with nothing typed, searches from 2 characters, and blocks 1", () => {
        expect(modeFor("")).toBe("browse");
        expect(modeFor("   ")).toBe("browse");
        expect(modeFor(" a ")).toBe("blocked");
        expect(modeFor("ab")).toBe("search");
        expect(modeFor("  big buck  ")).toBe("search");
    });

    it("runs one request at a time, and never for one character", () => {
        expect(canRun("", false)).toBe(true);
        expect(canRun("ab", false)).toBe(true);
        expect(canRun("a", false)).toBe(false);
        expect(canRun("ab", true)).toBe(false);
        expect(canRun("", true)).toBe(false);
    });

    it("sorts a browse newest first and a search most seeded first", () => {
        expect(defaultSort("browse")).toEqual({
            key: "published",
            descending: true,
        });
        expect(defaultSort("search")).toEqual({
            key: "seeders",
            descending: true,
        });
    });

    it("says why the list is empty", () => {
        expect(emptyText("browse", "")).toBe(
            "Nothing recent from these indexers. Check that a source is turned on in Settings.",
        );
        expect(emptyText("search", "bunny")).toBe(
            "Nothing for “bunny”. Try fewer words, or turn on more sources in Settings.",
        );
    });
});

describe("swarmTicks", () => {
    it("shows one tick for a dead or unknown swarm", () => {
        expect(swarmTicks(0)).toBe(1);
        expect(swarmTicks(null)).toBe(1);
    });

    it("lights more ticks as seeders grow, up to five", () => {
        expect(swarmTicks(1)).toBe(1);
        expect(swarmTicks(9)).toBe(2);
        expect(swarmTicks(100)).toBe(3);
        expect(swarmTicks(1000)).toBe(5);
        expect(swarmTicks(1_000_000)).toBe(5);
    });

    it("never goes down as seeders go up", () => {
        let last = 0;
        for (const n of [
            0,
            1,
            3,
            10,
            40,
            200,
            900,
            5000,
            90000,
        ]) {
            const ticks = swarmTicks(n);
            expect(ticks).toBeGreaterThanOrEqual(last);
            last = ticks;
        }
    });
});

describe("sort menu", () => {
    it("offers each sort once, most seeded first", () => {
        expect(SORT_OPTIONS.map((o) => o.id)).toEqual([
            "seeders",
            "published",
            "size",
            "title",
        ]);
    });

    it("names the entry for a sort state", () => {
        expect(sortOptionId({ key: "published", descending: true })).toBe(
            "published",
        );
        expect(sortOptionId({ key: "leechers", descending: true })).toBe(
            "seeders",
        );
    });
});

describe("formatDuration", () => {
    it("reads minutes and seconds, and hours when there are some", () => {
        expect(formatDuration(596)).toBe("9:56");
        expect(formatDuration(59)).toBe("0:59");
        expect(formatDuration(3725)).toBe("1:02:05");
    });

    it("is empty when the duration is unknown (a live stream)", () => {
        expect(formatDuration(null)).toBe("");
    });
});

describe("formatViews", () => {
    it("shortens big counts", () => {
        expect(formatViews(1)).toBe("1 view");
        expect(formatViews(950)).toBe("950 views");
        expect(formatViews(1234)).toBe("1.2K views");
        expect(formatViews(62_000_000)).toBe("62M views");
    });

    it("is empty when unknown", () => {
        expect(formatViews(null)).toBe("");
    });
});

describe("videoFacts", () => {
    const video = (over: Partial<VideoResult>): VideoResult => ({
        title: "t",
        url: "u",
        channel: null,
        durationS: null,
        thumbnail: null,
        views: null,
        published: null,
        ...over,
    });

    it("lists views then age, leaving out what is unknown", () => {
        const now = Date.parse("2026-09-30T00:00:00Z");
        expect(
            videoFacts(
                video({ views: 1234, published: "2026-09-28T00:00:00Z" }),
                now,
            ),
        ).toEqual([
            "1.2K views",
            "2 days",
        ]);
        expect(videoFacts(video({}), now)).toEqual([]);
    });
});

describe("the YouTube tab's rules", () => {
    it("searches from two characters, and not while busy", () => {
        expect(canSearchVideos("a", false)).toBe(false);
        expect(canSearchVideos(" ab ", false)).toBe(true);
        expect(canSearchVideos("ab", true)).toBe(false);
    });

    it("says what to do when there is nothing to show", () => {
        expect(VIDEO_EMPTY_IDLE).toBe("Search YouTube for a video.");
        expect(videoEmptyText("zzxk")).toBe(
            "Nothing for “zzxk”. Try fewer words.",
        );
    });
});
