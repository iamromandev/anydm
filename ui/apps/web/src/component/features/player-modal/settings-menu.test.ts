import { describe, expect, test } from "bun:test";
import { availablePages, formatRate } from "./settings-menu";

const none = { files: 0, audio: 0, subtitles: 0, quality: 0 };

describe("availablePages", () => {
    test("a bare source has only speed", () => {
        expect(availablePages(none)).toEqual([
            "speed",
        ]);
    });

    test("one audio track or one file is no choice", () => {
        expect(availablePages({ ...none, audio: 1, files: 1 })).toEqual([
            "speed",
        ]);
    });

    test("one subtitle track is a choice, since it can be turned off", () => {
        expect(availablePages({ ...none, subtitles: 1 })).toEqual([
            "speed",
            "subtitles",
        ]);
    });

    test("everything, in menu order", () => {
        expect(
            availablePages({ files: 3, audio: 2, subtitles: 2, quality: 4 }),
        ).toEqual([
            "speed",
            "quality",
            "audio",
            "subtitles",
            "files",
        ]);
    });
});

describe("formatRate", () => {
    test("names a rate", () => {
        expect(formatRate(1.25)).toBe("1.25x");
    });
});
