import { describe, expect, it } from "bun:test";

import {
    adjacentFileIndex,
    defaultFileIndex,
    hasMediaExtension,
    mediaFiles,
    naturalCompare,
} from "./media";

describe("a torrent's files, as the player lists them (#98)", () => {
    it("orders numbers as numbers, so E2 comes before E10", () => {
        const paths = [
            "Show.S01E10.mkv",
            "Show.S01E2.mkv",
            "Show.S01E1.mkv",
        ];
        expect(
            [
                ...paths,
            ].sort(naturalCompare),
        ).toEqual([
            "Show.S01E1.mkv",
            "Show.S01E2.mkv",
            "Show.S01E10.mkv",
        ]);
    });

    it("ignores case, and keeps folders together", () => {
        expect(
            [
                "b/2.mkv",
                "A/10.mkv",
                "a/9.mkv",
                "B/1.mkv",
            ].sort(naturalCompare),
        ).toEqual([
            "a/9.mkv",
            "A/10.mkv",
            "B/1.mkv",
            "b/2.mkv",
        ]);
    });

    it("keeps selected media files only, in natural order", () => {
        const files = [
            { index: 0, path: "Show.S01E10.mkv", sizeBytes: 9 },
            { index: 1, path: "Show.S01E2.mkv", sizeBytes: 8 },
            { index: 2, path: "Show.S01E2.en.srt", sizeBytes: 1 },
            { index: 3, path: "Extras.mkv", sizeBytes: 5, selected: false },
        ];
        expect(mediaFiles(files)).toEqual([
            { index: 1, path: "Show.S01E2.mkv", sizeBytes: 8 },
            { index: 0, path: "Show.S01E10.mkv", sizeBytes: 9 },
        ]);
    });

    it("opens with the largest, as the API does when nothing is named", () => {
        expect(
            defaultFileIndex([
                { index: 1, path: "a.mkv", sizeBytes: 8 },
                { index: 0, path: "b.mkv", sizeBytes: 9 },
            ]),
        ).toBe(0);
        expect(defaultFileIndex([])).toBeNull();
    });

    it("recognises media by extension, whatever the case", () => {
        expect(hasMediaExtension("Movie.MKV")).toBe(true);
        expect(hasMediaExtension("notes.txt")).toBe(false);
    });

    it("steps to the file before or after the current one (#97)", () => {
        const files = [
            { index: 1, path: "Show.S01E1.mkv", sizeBytes: 1 },
            { index: 2, path: "Show.S01E2.mkv", sizeBytes: 1 },
            { index: 10, path: "Show.S01E10.mkv", sizeBytes: 1 },
        ];
        expect(adjacentFileIndex(files, 2, 1)).toBe(10);
        expect(adjacentFileIndex(files, 2, -1)).toBe(1);
    });

    it("has nothing past either end", () => {
        const files = [
            { index: 1, path: "a.mkv", sizeBytes: 1 },
            { index: 2, path: "b.mkv", sizeBytes: 1 },
        ];
        expect(adjacentFileIndex(files, 2, 1)).toBeNull();
        expect(adjacentFileIndex(files, 1, -1)).toBeNull();
    });

    it("has nothing to step from when the current file isn't in the list", () => {
        const files = [
            { index: 1, path: "a.mkv", sizeBytes: 1 },
        ];
        expect(adjacentFileIndex(files, null, 1)).toBeNull();
        expect(adjacentFileIndex(files, 9, 1)).toBeNull();
        expect(adjacentFileIndex([], 1, 1)).toBeNull();
    });
});
