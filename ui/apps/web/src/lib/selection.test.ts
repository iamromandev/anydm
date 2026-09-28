import { describe, expect, it } from "bun:test";

import type { PlaylistEntry } from "./api/playlist";
import {
    filterEntries,
    isSelectable,
    presetHint,
    selectionSummary,
    setAll,
    tickArrivals,
    toggle,
    toggleRange,
} from "./selection";

const entry = (
    index: number,
    extra: Partial<PlaylistEntry> = {},
): PlaylistEntry => ({
    index,
    id: `v${index}`,
    url: `https://y.test/v${index}`,
    title: `Video ${index}`,
    duration: 3600,
    thumbnail: null,
    timestamp: null,
    available: true,
    have: null,
    ...extra,
});

const ENTRIES = [
    entry(1),
    entry(2, { have: "complete" }),
    entry(3, { available: false }),
    entry(4, { title: "Talk about disks" }),
    entry(5),
];

describe("ticks", () => {
    it("ticks what arrives, except what's held or unavailable", () => {
        expect(isSelectable(ENTRIES[1])).toBe(false);
        expect([
            ...tickArrivals(new Set(), ENTRIES),
        ]).toEqual([
            1,
            4,
            5,
        ]);
    });

    it("toggles one row, never an unselectable one", () => {
        expect([
            ...toggle(
                new Set([
                    1,
                ]),
                ENTRIES[0],
            ),
        ]).toEqual([]);
        expect([
            ...toggle(new Set(), ENTRIES[2]),
        ]).toEqual([]);
    });

    it("gives a shift-click range the clicked row's new state", () => {
        const on = toggleRange(new Set(), ENTRIES, 1, ENTRIES[4]);
        expect(
            [
                ...on,
            ].sort(),
        ).toEqual([
            1,
            4,
            5,
        ]);
        const off = toggleRange(
            new Set([
                1,
                4,
                5,
            ]),
            ENTRIES,
            5,
            ENTRIES[3],
        );
        expect([
            ...off,
        ]).toEqual([
            1,
        ]);
    });

    it("sets All or None on the rows shown, leaving hidden ticks", () => {
        const shown = filterEntries(ENTRIES, "disks");
        expect(shown.map((e) => e.index)).toEqual([
            4,
        ]);
        expect(
            [
                ...setAll(
                    new Set([
                        1,
                    ]),
                    shown,
                    true,
                ),
            ].sort(),
        ).toEqual([
            1,
            4,
        ]);
        expect([
            ...setAll(
                new Set([
                    1,
                    4,
                ]),
                shown,
                false,
            ),
        ]).toEqual([
            1,
        ]);
        expect(
            [
                ...setAll(new Set(), ENTRIES, true),
            ].sort(),
        ).toEqual([
            1,
            4,
            5,
        ]);
    });

    it("filters by title in any case, and an empty query shows all", () => {
        expect(filterEntries(ENTRIES, "  DISKS ").length).toBe(1);
        expect(filterEntries(ENTRIES, "")).toEqual(ENTRIES);
    });
});

describe("selectionSummary", () => {
    it("counts the ticks and their running time", () => {
        expect(
            selectionSummary(
                ENTRIES,
                new Set([
                    1,
                    4,
                ]),
            ),
        ).toBe("2 of 5 selected · about 2 h");
    });

    it("says minutes under an hour, and nothing without durations", () => {
        const short = [
            entry(1, { duration: 600 }),
        ];
        expect(
            selectionSummary(
                short,
                new Set([
                    1,
                ]),
            ),
        ).toBe("1 of 1 selected · about 10 min");
        const unknown = [
            entry(1, { duration: null }),
        ];
        expect(
            selectionSummary(
                unknown,
                new Set([
                    1,
                ]),
            ),
        ).toBe("1 of 1 selected");
    });
});

describe("presetHint", () => {
    it("explains a height, and nothing else", () => {
        expect(presetHint("1080")).toBe(
            "Videos without 1080p get the closest below",
        );
        expect(presetHint("best")).toBe("");
        expect(presetHint("mp3")).toBe("");
    });
});
