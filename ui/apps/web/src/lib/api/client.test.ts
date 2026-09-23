import { afterEach, describe, expect, it } from "bun:test";

import { apiUrl, getApi, getPageApi, onUnauthorized } from "./client";
import { ApiError } from "./envelope";

const realFetch = globalThis.fetch;

function stubFetch(handler: () => Promise<Response>): void {
    globalThis.fetch = handler as unknown as typeof globalThis.fetch;
}

function respondWith(body: string, init: ResponseInit): void {
    stubFetch(async () => new Response(body, init));
}

afterEach(() => {
    globalThis.fetch = realFetch;
});

describe("getApi", () => {
    it("returns the envelope's data", async () => {
        respondWith(
            JSON.stringify({
                status: "success",
                code: 200,
                data: [
                    1,
                ],
            }),
            {
                status: 200,
            },
        );

        expect(await getApi<number[]>("/download")).toEqual([
            1,
        ]);
    });

    it("reports the service's message for a rejected request", async () => {
        respondWith(
            JSON.stringify({
                status: "error",
                code: 409,
                message: "Task is paused",
            }),
            { status: 409 },
        );

        expect(getApi("/download/x/pause")).rejects.toThrow("Task is paused");
    });

    it("says something readable when the body is not the envelope at all", async () => {
        respondWith("<html>502 Bad Gateway</html>", { status: 502 });

        const error = (await getApi("/download").catch((e) => e)) as ApiError;
        expect(error).toBeInstanceOf(ApiError);
        expect(error.message).toContain("502");
        expect(error.code).toBe(502);
    });

    it("says the API could not be reached when the request never lands", async () => {
        stubFetch(async () => {
            throw new TypeError("Failed to fetch");
        });

        const error = (await getApi("/download").catch((e) => e)) as ApiError;
        expect(error).toBeInstanceOf(ApiError);
        expect(error.message).toContain("Could not reach");
    });
});

describe("getPageApi", () => {
    it("returns the rows and the pagination beside them", async () => {
        respondWith(
            JSON.stringify({
                status: "success",
                code: 200,
                data: [
                    { id: "a" },
                ],
                meta: { page: 2, page_size: 25, total: 60, total_pages: 3 },
            }),
            { status: 200 },
        );

        const page = await getPageApi<{ id: string }[]>("/download?page=2");

        expect(page.data).toEqual([
            { id: "a" },
        ]);
        expect(page.meta).toEqual({
            page: 2,
            pageSize: 25,
            total: 60,
            totalPages: 3,
        });
    });

    it("falls back to a single page when the envelope carries no meta", async () => {
        respondWith(
            JSON.stringify({ status: "success", code: 200, data: [] }),
            { status: 200 },
        );

        const page = await getPageApi<unknown[]>("/download");

        expect(page.meta.page).toBe(1);
        expect(page.meta.totalPages).toBe(1);
    });

    it("still throws the service's message when the request is refused", () => {
        respondWith(
            JSON.stringify({ status: "error", code: 500, message: "boom" }),
            { status: 500 },
        );

        expect(getPageApi("/download")).rejects.toThrow("boom");
    });
});

describe("the API key", () => {
    const store = new Map<string, string>();
    const realStorage = globalThis.localStorage;

    function storeKey(key: string | null): void {
        store.clear();
        if (key !== null) store.set("anydm.apiKey", key);
        globalThis.localStorage = {
            getItem: (k: string) => store.get(k) ?? null,
        } as unknown as Storage;
    }

    afterEach(() => {
        globalThis.localStorage = realStorage;
    });

    it("leaves a URL alone when no key is stored", () => {
        storeKey(null);

        expect(apiUrl("/download/events", { withKey: true })).toEndWith(
            "/download/events",
        );
    });

    it("appends the key to a URL the browser will open by itself", () => {
        storeKey("s3 cret&");

        expect(apiUrl("/download/events", { withKey: true })).toEndWith(
            "/download/events?api_key=s3%20cret%26",
        );
    });

    it("joins an existing query rather than starting a second one", () => {
        storeKey("k");

        expect(
            apiUrl("/download/x/file?inline=1", { withKey: true }),
        ).toEndWith("/download/x/file?inline=1&api_key=k");
    });

    it("keeps the key out of ordinary URLs, which get a header instead", () => {
        storeKey("k");

        expect(apiUrl("/settings")).toEndWith("/settings");
    });

    it("sends the stored key as a header", async () => {
        storeKey("s3cret");
        const seen: { key: string | null } = { key: null };
        stubFetch((async (_url: string, init?: RequestInit) => {
            seen.key = new Headers(init?.headers).get("X-API-Key");
            return new Response(
                JSON.stringify({ status: "success", code: 200, data: 1 }),
            );
        }) as unknown as () => Promise<Response>);

        await getApi("/settings");

        expect(seen.key).toBe("s3cret");
    });

    it("sends no header when no key is stored", async () => {
        storeKey(null);
        let had = true;
        stubFetch((async (_url: string, init?: RequestInit) => {
            had = new Headers(init?.headers).has("X-API-Key");
            return new Response(
                JSON.stringify({ status: "success", code: 200, data: 1 }),
            );
        }) as unknown as () => Promise<Response>);

        await getApi("/settings");

        expect(had).toBe(false);
    });

    it("tells whoever is listening about a 401", async () => {
        storeKey(null);
        respondWith(
            JSON.stringify({
                status: "error",
                code: 401,
                message: "A valid API key is required",
            }),
            { status: 401 },
        );
        let heard = 0;
        const stop = onUnauthorized(() => heard++);

        await expect(getApi("/settings")).rejects.toThrow(ApiError);
        stop();
        await expect(getApi("/settings")).rejects.toThrow(ApiError);

        expect(heard).toBe(1);
    });
});
