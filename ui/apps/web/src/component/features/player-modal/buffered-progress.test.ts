import { describe, expect, it } from "bun:test";

import { getBufferedPercent } from "./buffered-progress";

describe("getBufferedPercent", () => {
    it("returns 0 when duration is unknown", () => {
        expect(
            getBufferedPercent({
                ranges: [
                    { start: 0, end: 30 },
                ],
                currentTime: 0,
                duration: 0,
            }),
        ).toBe(0);
    });

    it("returns 0 when duration is NaN (metadata not loaded yet)", () => {
        expect(
            getBufferedPercent({
                ranges: [{ start: 0, end: 5 }],
                currentTime: 0,
                duration: NaN,
            }),
        ).toBe(0);
    });

    it("returns 0 when nothing is buffered", () => {
        expect(
            getBufferedPercent({ ranges: [], currentTime: 0, duration: 120 }),
        ).toBe(0);
    });

    it("computes percent buffered ahead from the range containing the playhead", () => {
        expect(
            getBufferedPercent({
                ranges: [
                    { start: 0, end: 60 },
                ],
                currentTime: 0,
                duration: 120,
            }),
        ).toBe(50);
    });

    it("ignores ranges that don't contain the current playhead", () => {
        expect(
            getBufferedPercent({
                ranges: [
                    { start: 0, end: 10 },
                    { start: 90, end: 120 },
                ],
                currentTime: 50,
                duration: 120,
            }),
        ).toBe(0);
    });

    it("picks the range that contains the playhead among several", () => {
        expect(
            getBufferedPercent({
                ranges: [
                    { start: 0, end: 10 },
                    { start: 40, end: 100 },
                ],
                currentTime: 50,
                duration: 120,
            }),
        ).toBeCloseTo(83.33, 1);
    });

    it("clamps to 100 when buffered through the end", () => {
        expect(
            getBufferedPercent({
                ranges: [
                    { start: 0, end: 120 },
                ],
                currentTime: 119,
                duration: 120,
            }),
        ).toBe(100);
    });
});
