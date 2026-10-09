import { afterEach, describe, expect, it } from "bun:test";
import {
    DOWNLOADS_CATEGORY_ID,
    canMoveCategory,
    createCategory,
    deleteCategory,
    inCategory,
    listCategories,
    moveToCategory,
    normalizeCategory,
    orderCategories,
    updateCategory,
} from "./category";

const realFetch = globalThis.fetch;
type Seen = { url: string; method: string | undefined; body: unknown };
let seen: Seen = { url: "", method: undefined, body: undefined };

function stubData(data: unknown, status = 200): void {
    globalThis.fetch = (async (url: string, init?: RequestInit) => {
        seen = {
            url,
            method: init?.method,
            body:
                init?.body === undefined
                    ? undefined
                    : JSON.parse(String(init.body)),
        };
        if (status === 204) return new Response(null, { status });
        return new Response(
            JSON.stringify({ status: "success", code: status, data }),
            { status },
        );
    }) as unknown as typeof globalThis.fetch;
}

afterEach(() => {
    globalThis.fetch = realFetch;
});

const lectures = {
    id: "c1",
    name: "Lectures",
    slug: "lectures",
    folder: "edu/lectures",
    position: 15,
    builtin: false,
    count: 2,
};

describe("normalizeCategory", () => {
    it("maps the API's row and defaults what is missing", () => {
        expect(normalizeCategory(lectures)).toEqual(lectures);
        expect(normalizeCategory({ id: "x" })).toEqual({
            id: "x",
            name: "",
            slug: "",
            folder: "",
            position: 0,
            builtin: false,
            count: 0,
        });
    });
});

describe("the calls", () => {
    it("lists from inside the payload", async () => {
        stubData({
            categories: [
                lectures,
            ],
        });
        expect(await listCategories()).toEqual([
            lectures,
        ]);
        expect(seen.url.endsWith("/category")).toBe(true);
    });

    it("creates, patches only what is given, orders and deletes", async () => {
        stubData(lectures);
        await createCategory("Lectures", "edu/lectures");
        expect(seen).toMatchObject({
            method: "POST",
            body: { name: "Lectures", folder: "edu/lectures" },
        });

        await updateCategory("c1", { folder: "edu/talks" });
        expect(seen.method).toBe("PATCH");
        expect(seen.url.endsWith("/category/c1")).toBe(true);
        expect(seen.body).toEqual({ folder: "edu/talks" });

        stubData({
            categories: [
                lectures,
            ],
        });
        await orderCategories([
            "c1",
            DOWNLOADS_CATEGORY_ID,
        ]);
        expect(seen.body).toEqual({
            ids: [
                "c1",
                DOWNLOADS_CATEGORY_ID,
            ],
        });

        stubData(null, 204);
        await deleteCategory("c1");
        expect(seen.method).toBe("DELETE");
    });

    it("moves a download or a collection by its own route", async () => {
        stubData({ id: "d1" });
        await moveToCategory({ id: "d1", collection: false }, "c1");
        expect(seen).toMatchObject({
            method: "PUT",
            body: { category_id: "c1" },
        });
        expect(seen.url.endsWith("/download/d1/category")).toBe(true);
        await moveToCategory({ id: "g1", collection: true }, "c1");
        expect(seen.url.endsWith("/collection/g1/category")).toBe(true);
    });
});

describe("inCategory", () => {
    it("lets everything through without a filter, and only matches with one", () => {
        expect(inCategory({}, null)).toBe(true);
        expect(inCategory({ category: { id: "c1", name: "L" } }, "c1")).toBe(
            true,
        );
        expect(inCategory({ category: { id: "c2", name: "M" } }, "c1")).toBe(
            false,
        );
        expect(inCategory({}, "c1")).toBe(false);
    });
});

describe("canMoveCategory", () => {
    it("is off for torrents, collection videos and running downloads", () => {
        expect(canMoveCategory({ kind: "file", status: "completed" })).toBe(
            true,
        );
        expect(canMoveCategory({ kind: "playlist", status: "paused" })).toBe(
            true,
        );
        expect(canMoveCategory({ kind: "torrent", status: "seeding" })).toBe(
            false,
        );
        expect(
            canMoveCategory({
                kind: "video",
                status: "completed",
                parentId: "g1",
            }),
        ).toBe(false);
        expect(canMoveCategory({ kind: "file", status: "downloading" })).toBe(
            false,
        );
        expect(canMoveCategory({ kind: "file", status: "muxing" })).toBe(false);
    });
});
