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

    it("remembers the audio language, and opens with the file's own by default (#99)", () => {
        expect(DEFAULT_PREFS.audioLanguage).toBe("");
        savePrefs({ ...DEFAULT_PREFS, audioLanguage: "ja" }, stub);

        expect(loadPrefs(stub).audioLanguage).toBe("ja");
    });

    it("ignores an audio language that is not a language code", () => {
        stub.setItem(
            "anydm.prefs",
            JSON.stringify({ audioLanguage: "<script>" }),
        );
        expect(loadPrefs(stub).audioLanguage).toBe("");

        stub.setItem("anydm.prefs", JSON.stringify({ audioLanguage: 7 }));
        expect(loadPrefs(stub).audioLanguage).toBe("");
    });

    it("remembers the subtitle language, off by default (#100)", () => {
        expect(DEFAULT_PREFS.subtitleLanguage).toBe("");
        savePrefs({ ...DEFAULT_PREFS, subtitleLanguage: "fr" }, stub);
        expect(loadPrefs(stub).subtitleLanguage).toBe("fr");

        stub.setItem(
            "anydm.prefs",
            JSON.stringify({ subtitleLanguage: "not a code!" }),
        );
        expect(loadPrefs(stub).subtitleLanguage).toBe("");
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
