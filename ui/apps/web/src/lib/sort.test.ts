import { afterEach, describe, expect, it } from "bun:test";

import {
    DEFAULT_SORT,
    SORT_OPTIONS,
    loadSort,
    saveSort,
    sortLabel,
} from "./sort";

const store = new Map<string, string>();
const stub = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
} as unknown as Storage;

afterEach(() => store.clear());

describe("the options offered", () => {
    it("every option has a label and a distinct value", () => {
        const values = SORT_OPTIONS.map((o) => o.value);

        expect(new Set(values).size).toBe(values.length);
        expect(SORT_OPTIONS.every((o) => o.label.length > 0)).toBe(true);
    });

    it("includes the default, or nothing would be selected on first load", () => {
        expect(SORT_OPTIONS.some((o) => o.value === DEFAULT_SORT)).toBe(true);
    });

    it("names every option it offers", () => {
        for (const option of SORT_OPTIONS) {
            expect(sortLabel(option.value)).toBe(option.label);
        }
    });
});

describe("loadSort", () => {
    it("is the default when nothing was ever chosen", () => {
        expect(loadSort(stub)).toBe(DEFAULT_SORT);
    });

    it("remembers what was chosen", () => {
        saveSort("title", stub);

        expect(loadSort(stub)).toBe("title");
    });

    it("ignores a stored value this build no longer offers", () => {
        // A sort removed between releases, or a hand-edited key.
        stub.setItem("anydm.sort", "-nonsense");

        expect(loadSort(stub)).toBe(DEFAULT_SORT);
    });

    it("survives storage being unavailable", () => {
        const hostile = {
            getItem: () => {
                throw new Error("denied");
            },
            setItem: () => {
                throw new Error("denied");
            },
        } as unknown as Storage;

        expect(loadSort(hostile)).toBe(DEFAULT_SORT);
        expect(() => saveSort("title", hostile)).not.toThrow();
    });
});
