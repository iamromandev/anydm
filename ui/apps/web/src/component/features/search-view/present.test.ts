import { describe, expect, it } from "bun:test";

import type { FoundTorrent } from "@/lib/api/search";

import {
    canRun,
    defaultSort,
    emptyText,
    errorChip,
    formatAge,
    formatSize,
    indexerLabel,
    modeFor,
    nextSort,
    seederTone,
    sortFound,
    statusLine,
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
            "Nothing recent from these indexers",
        );
        expect(emptyText("search", "bunny")).toBe("No results for “bunny”");
    });
});
