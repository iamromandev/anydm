import { describe, expect, it } from "bun:test";

import { HISTORY_LIMIT, appendSample, emptyHistory } from "./history";

describe("appendSample", () => {
    it("starts empty and grows one sample at a time", () => {
        const first = appendSample(emptyHistory(), 100, 20);

        expect(first.download).toEqual([
            100,
        ]);
        expect(first.upload).toEqual([
            20,
        ]);
    });

    it("keeps the scale at the tallest sample of either series", () => {
        const history = appendSample(
            appendSample(emptyHistory(), 100, 900),
            50,
            10,
        );

        expect(history.max).toBe(900);
    });

    it("never lets the scale fall to zero, so an idle series is flat not infinite", () => {
        expect(appendSample(emptyHistory(), 0, 0).max).toBe(1);
    });

    it("drops the oldest sample once the window is full", () => {
        let history = emptyHistory();
        for (let i = 0; i < HISTORY_LIMIT + 10; i++) {
            history = appendSample(history, i, 0);
        }

        expect(history.download.length).toBe(HISTORY_LIMIT);
        expect(history.download[0]).toBe(10);
        expect(history.download.at(-1)).toBe(HISTORY_LIMIT + 9);
    });
});
