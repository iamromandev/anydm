import { afterEach, describe, expect, it } from "bun:test";

import { DEFAULT_PREFS, loadPrefs, savePrefs } from "./prefs";

const store = new Map<string, string>();
const stub = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
} as unknown as Storage;

afterEach(() => store.clear());

describe("loadPrefs", () => {
    it("is the defaults when nothing was ever saved", () => {
        expect(loadPrefs(stub)).toEqual(DEFAULT_PREFS);
    });

    it("remembers what was saved", () => {
        savePrefs({ ...DEFAULT_PREFS, defaultPreset: "720" }, stub);

        expect(loadPrefs(stub).defaultPreset).toBe("720");
    });

    it("keeps the defaults for anything the saved copy is missing", () => {
        // Written by an older build that had fewer preferences.
        stub.setItem("anydm.prefs", JSON.stringify({ defaultPreset: "480" }));

        const prefs = loadPrefs(stub);

        expect(prefs.defaultPreset).toBe("480");
        expect(prefs.confirmBeforeRemove).toBe(
            DEFAULT_PREFS.confirmBeforeRemove,
        );
    });

    it("ignores a preset this build does not offer", () => {
        stub.setItem("anydm.prefs", JSON.stringify({ defaultPreset: "8k" }));

        expect(loadPrefs(stub).defaultPreset).toBe(DEFAULT_PREFS.defaultPreset);
    });

    it("survives a stored value that is not json at all", () => {
        stub.setItem("anydm.prefs", "{ this is not json");

        expect(loadPrefs(stub)).toEqual(DEFAULT_PREFS);
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

        expect(loadPrefs(hostile)).toEqual(DEFAULT_PREFS);
        expect(() => savePrefs(DEFAULT_PREFS, hostile)).not.toThrow();
    });
});
