import { afterEach, describe, expect, it } from "bun:test";
import { DOWNLOADS_CATEGORY_ID } from "./api/category";
import { loadCategoryChoice, saveCategoryChoice } from "./category-choice";

const store = new Map<string, string>();
const stub = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
} as unknown as Storage;
afterEach(() => store.clear());

const listed = [
    {
        id: DOWNLOADS_CATEGORY_ID,
        name: "Downloads",
        slug: "downloads",
        folder: "",
        position: 0,
        builtin: true,
        count: 0,
    },
    {
        id: "c1",
        name: "Music",
        slug: "music",
        folder: "music",
        position: 1,
        builtin: false,
        count: 0,
    },
];

describe("the remembered category", () => {
    it("is Downloads until one is chosen", () => {
        expect(loadCategoryChoice(listed, stub)).toBe(DOWNLOADS_CATEGORY_ID);
    });

    it("comes back once chosen", () => {
        saveCategoryChoice("c1", stub);
        expect(loadCategoryChoice(listed, stub)).toBe("c1");
    });

    it("falls back to Downloads once that category is gone", () => {
        saveCategoryChoice("gone", stub);
        expect(loadCategoryChoice(listed, stub)).toBe(DOWNLOADS_CATEGORY_ID);
    });

    it("survives storage that throws", () => {
        const broken = {
            getItem: () => {
                throw new Error("blocked");
            },
            setItem: () => {
                throw new Error("blocked");
            },
        } as unknown as Storage;
        saveCategoryChoice("c1", broken);
        expect(loadCategoryChoice(listed, broken)).toBe(DOWNLOADS_CATEGORY_ID);
    });
});
