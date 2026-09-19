import { afterEach, describe, expect, it } from "bun:test";

import { getApi, getPageApi } from "./client";
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
