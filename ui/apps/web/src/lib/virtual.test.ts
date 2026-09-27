import { describe, expect, it } from "bun:test";

import { virtualWindow } from "./virtual";

const ROW = 56;

function total(w: ReturnType<typeof virtualWindow>): number {
    return w.padTop + (w.end - w.start) * ROW + w.padBottom;
}

describe("virtualWindow", () => {
    it("draws the rows in view and a few beyond, at the top", () => {
        const w = virtualWindow(5000, ROW, 0, 420, 8);
        expect(w.start).toBe(0);
        expect(w.end).toBe(Math.ceil(420 / ROW) + 8);
        expect(w.padTop).toBe(0);
    });

    it("keeps the full height, wherever it is scrolled", () => {
        for (const scrollTop of [
            0,
            1000,
            123_456,
            5000 * ROW - 420,
        ]) {
            expect(total(virtualWindow(5000, ROW, scrollTop, 420))).toBe(
                5000 * ROW,
            );
        }
    });

    it("starts a few rows above the first in view", () => {
        const w = virtualWindow(5000, ROW, 100 * ROW, 420, 8);
        expect(w.start).toBe(92);
        expect(w.padTop).toBe(92 * ROW);
    });

    it("stops at the last row", () => {
        const w = virtualWindow(20, ROW, 20 * ROW, 420, 8);
        expect(w.end).toBe(20);
        expect(w.padBottom).toBe(0);
    });

    it("copes with a list shorter than where it was scrolled", () => {
        // A new listing replaced a long one while scrolled down.
        const w = virtualWindow(3, ROW, 10_000, 420, 8);
        expect(w.start).toBeLessThanOrEqual(w.end);
        expect(w.end).toBe(3);
        expect(total(w)).toBe(3 * ROW);
    });

    it("draws nothing for an empty list", () => {
        expect(virtualWindow(0, ROW, 0, 420)).toEqual({
            start: 0,
            end: 0,
            padTop: 0,
            padBottom: 0,
        });
    });
});
