import { afterEach, describe, expect, it } from "bun:test";

import { API_KEY_STORAGE, loadApiKey, saveApiKey } from "./key";

const store = new Map<string, string>();
const stub = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
} as unknown as Storage;

afterEach(() => store.clear());

describe("the stored API key", () => {
    it("is empty when none was ever saved", () => {
        expect(loadApiKey(stub)).toBe("");
    });

    it("remembers what was saved, trimmed", () => {
        saveApiKey("  s3cret \n", stub);

        expect(store.get(API_KEY_STORAGE)).toBe("s3cret");
        expect(loadApiKey(stub)).toBe("s3cret");
    });

    it("is removed rather than stored empty", () => {
        saveApiKey("s3cret", stub);
        saveApiKey("   ", stub);

        expect(store.has(API_KEY_STORAGE)).toBe(false);
    });

    it("is empty when storage refuses to be read", () => {
        const broken = {
            getItem: () => {
                throw new Error("denied");
            },
        } as unknown as Storage;

        expect(loadApiKey(broken)).toBe("");
    });
});
