import { afterEach, describe, expect, it } from "bun:test";

import {
    createSource,
    deleteSource,
    listSources,
    normalizeSource,
    normalizeTest,
    probeSource,
    resetSource,
    testSource,
    updateSource,
} from "./source";

const realFetch = globalThis.fetch;

type Seen = { url: string; method: string | undefined; body: unknown };
let seen: Seen = { url: "", method: undefined, body: undefined };

function stubData(data: unknown): void {
    globalThis.fetch = (async (url: string, init?: RequestInit) => {
        seen = {
            url,
            method: init?.method,
            body:
                init?.body === undefined
                    ? undefined
                    : JSON.parse(String(init.body)),
        };
        return new Response(
            JSON.stringify({ status: "success", code: 200, data }),
        );
    }) as unknown as typeof globalThis.fetch;
}

afterEach(() => {
    globalThis.fetch = realFetch;
});

const RAW = {
    id: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    name: "prowlarr",
    kind: "torznab",
    enabled: true,
    base_url: "http://prowlarr:9696/1/api",
    api_key_masked: "secr…ey-1",
    deletable: true,
    default_url: null,
    label: "Prowlarr",
};

describe("normalizeSource", () => {
    it("maps snake_case and never reads a label", () => {
        expect(normalizeSource(RAW)).toEqual({
            id: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            name: "prowlarr",
            kind: "torznab",
            enabled: true,
            baseUrl: "http://prowlarr:9696/1/api",
            apiKeyMasked: "secr…ey-1",
            deletable: true,
            defaultUrl: null,
        });
        expect("label" in normalizeSource(RAW)).toBe(false);
    });

    it("reads a missing masked key or default as null, not undefined", () => {
        const normalized = normalizeSource({
            ...RAW,
            api_key_masked: undefined,
            default_url: undefined,
        });

        expect(normalized.apiKeyMasked).toBeNull();
        expect(normalized.defaultUrl).toBeNull();
    });
});

describe("normalizeTest", () => {
    it("maps snake_case, with a missing count as null", () => {
        expect(
            normalizeTest({
                ok: true,
                count: 12,
                took_ms: 400,
                message: "Answered",
            }),
        ).toEqual({
            ok: true,
            count: 12,
            tookMs: 400,
            message: "Answered",
        });
        expect(
            normalizeTest({
                ok: false,
                took_ms: 30,
                message: "refused the request",
            }).count,
        ).toBeNull();
    });
});

describe("listSources", () => {
    it("gets /source and normalizes each row", async () => {
        stubData({
            sources: [
                RAW,
            ],
        });

        const sources = await listSources();

        expect(seen.url).toEndWith("/source");
        expect(seen.method).toBeUndefined();
        expect(sources).toEqual([
            normalizeSource(RAW),
        ]);
    });
});

describe("createSource", () => {
    it("posts snake_case and answers 201's row", async () => {
        stubData(RAW);

        const created = await createSource({
            name: "prowlarr",
            kind: "torznab",
            baseUrl: "http://prowlarr:9696/1/api",
            apiKey: "secret-key-1",
        });

        expect(seen.url).toEndWith("/source");
        expect(seen.method).toBe("POST");
        expect(seen.body).toEqual({
            name: "prowlarr",
            kind: "torznab",
            base_url: "http://prowlarr:9696/1/api",
            api_key: "secret-key-1",
        });
        expect(created).toEqual(normalizeSource(RAW));
    });
});

describe("updateSource", () => {
    it("patches the row by id with snake_case", async () => {
        stubData(RAW);

        await updateSource("3fa85f64-5717-4562-b3fc-2c963f66afa6", {
            enabled: false,
            apiKey: "",
        });

        expect(seen.url).toEndWith(
            "/source/3fa85f64-5717-4562-b3fc-2c963f66afa6",
        );
        expect(seen.method).toBe("PATCH");
        expect(seen.body).toEqual({ enabled: false, api_key: "" });
    });
});

describe("deleteSource", () => {
    it("deletes the row by id", async () => {
        globalThis.fetch = (async (url: string, init?: RequestInit) => {
            seen = { url, method: init?.method, body: undefined };
            return new Response(null, { status: 204 });
        }) as unknown as typeof globalThis.fetch;

        await deleteSource("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        expect(seen.url).toEndWith(
            "/source/3fa85f64-5717-4562-b3fc-2c963f66afa6",
        );
        expect(seen.method).toBe("DELETE");
    });
});

describe("resetSource", () => {
    it("posts to the row's reset", async () => {
        stubData(RAW);

        await resetSource("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        expect(seen.url).toEndWith(
            "/source/3fa85f64-5717-4562-b3fc-2c963f66afa6/reset",
        );
        expect(seen.method).toBe("POST");
    });
});

describe("testSource", () => {
    it("posts to the row's test, with overrides when given", async () => {
        stubData({ ok: true, count: 1, took_ms: 5, message: "Answered" });

        const plain = await testSource("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        expect(seen.url).toEndWith(
            "/source/3fa85f64-5717-4562-b3fc-2c963f66afa6/test",
        );
        expect(plain).toEqual({
            ok: true,
            count: 1,
            tookMs: 5,
            message: "Answered",
        });

        await testSource("3fa85f64-5717-4562-b3fc-2c963f66afa6", {
            baseUrl: "https://mirror.test",
        });
        expect(seen.body).toEqual({ base_url: "https://mirror.test" });
    });
});

describe("probeSource", () => {
    it("posts an unsaved source to /source/test", async () => {
        stubData({ ok: true, count: 2, took_ms: 5, message: "Answered" });

        const probed = await probeSource({
            kind: "torznab",
            baseUrl: "http://p.test/1/api",
            apiKey: "k",
        });

        expect(seen.url).toEndWith("/source/test");
        expect(seen.method).toBe("POST");
        expect(seen.body).toEqual({
            kind: "torznab",
            base_url: "http://p.test/1/api",
            api_key: "k",
        });
        expect(probed.count).toBe(2);
    });
});
