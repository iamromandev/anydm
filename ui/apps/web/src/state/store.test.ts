import { afterEach, describe, expect, test } from "bun:test";
import { DEFAULT_PREFS } from "@/lib/prefs";
import { DEFAULT_SORT } from "@/lib/sort";
import { createInitialState } from "./store";

afterEach(() => {
    delete (globalThis as { localStorage?: unknown }).localStorage;
});

describe("createInitialState", () => {
    test("never touches storage; remembered values are loaded by start()", () => {
        Object.defineProperty(globalThis, "localStorage", {
            configurable: true,
            get() {
                throw new Error("storage was read");
            },
        });

        const state = createInitialState();

        expect(state.sort).toBe(DEFAULT_SORT);
        expect(state.prefs).toEqual(DEFAULT_PREFS);
        expect(state.apiKey).toBe("");
    });

    test("starts empty and connecting", () => {
        const state = createInitialState();
        expect(state.tasks).toEqual([]);
        expect(state.connection).toBe("connecting");
        expect(state.queueIndex).toBe(-1);
    });
});
