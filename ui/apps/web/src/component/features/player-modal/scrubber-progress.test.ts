import { describe, expect, it } from "bun:test";

import {
    formatClockTime,
    scrubberSegments,
    seekRatioFromPointerX,
} from "./scrubber-progress";

describe("scrubberSegments", () => {
    it("computes all three segments for an in-progress torrent session", () => {
        const result = scrubberSegments({
            isTorrent: true,
            progressBytes: 450_000_000,
            totalBytes: 900_000_000,
            bufferedRanges: [
                { start: 0, end: 60 },
            ],
            currentTime: 30,
            duration: 120,
        });
        expect(result.swarmPercent).toBe(50);
        expect(result.bufferedPercent).toBe(50);
        expect(result.playedPercent).toBe(25);
    });

    it("zeroes the swarm segment for a non-torrent (direct URL) session", () => {
        const result = scrubberSegments({
            isTorrent: false,
            progressBytes: 450_000_000,
            totalBytes: 900_000_000,
            bufferedRanges: [
                { start: 0, end: 60 },
            ],
            currentTime: 0,
            duration: 120,
        });
        expect(result.swarmPercent).toBe(0);
    });

    it("zeroes every segment when duration is unknown", () => {
        const result = scrubberSegments({
            isTorrent: true,
            progressBytes: 0,
            totalBytes: 0,
            bufferedRanges: [],
            currentTime: 0,
            duration: 0,
        });
        expect(result).toEqual({
            swarmPercent: 0,
            bufferedPercent: 0,
            playedPercent: 0,
        });
    });

    it("only counts the buffered range that contains the playhead", () => {
        const result = scrubberSegments({
            isTorrent: true,
            progressBytes: 900_000_000,
            totalBytes: 900_000_000,
            bufferedRanges: [
                { start: 0, end: 10 },
                { start: 90, end: 120 },
            ],
            currentTime: 50,
            duration: 120,
        });
        expect(result.bufferedPercent).toBe(0);
    });
});

describe("seekRatioFromPointerX", () => {
    it("returns 0 at the left edge", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 100,
                rectLeft: 100,
                rectWidth: 300,
            }),
        ).toBe(0);
    });

    it("returns 1 at the right edge", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 400,
                rectLeft: 100,
                rectWidth: 300,
            }),
        ).toBe(1);
    });

    it("returns a fraction for a point in the middle", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 250,
                rectLeft: 100,
                rectWidth: 300,
            }),
        ).toBeCloseTo(0.5, 5);
    });

    it("clamps to 0 when the pointer is left of the track", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 50,
                rectLeft: 100,
                rectWidth: 300,
            }),
        ).toBe(0);
    });

    it("clamps to 1 when the pointer is right of the track", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 500,
                rectLeft: 100,
                rectWidth: 300,
            }),
        ).toBe(1);
    });

    it("returns 0 for a zero-width track instead of dividing by zero", () => {
        expect(
            seekRatioFromPointerX({
                clientX: 150,
                rectLeft: 100,
                rectWidth: 0,
            }),
        ).toBe(0);
    });
});

describe("formatClockTime", () => {
    it("formats seconds under a minute", () => {
        expect(formatClockTime(5)).toBe("0:05");
    });

    it("formats minutes and seconds", () => {
        expect(formatClockTime(125)).toBe("2:05");
    });

    it("formats hours, minutes, and seconds past an hour", () => {
        expect(formatClockTime(3725)).toBe("1:02:05");
    });

    it("floors fractional seconds", () => {
        expect(formatClockTime(59.9)).toBe("0:59");
    });

    it("treats NaN and negative values as zero", () => {
        expect(formatClockTime(NaN)).toBe("0:00");
        expect(formatClockTime(-5)).toBe("0:00");
    });
});
