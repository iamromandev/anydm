import { afterEach, describe, expect, it } from "bun:test";
import { addBatch } from "./batch";
import { detailRows } from "./detail";
import { normalizeApiTask } from "./download";
import { directRequest } from "./duplicate";
import { playlistRequest } from "./playlist";
import { addTorrent } from "./torrent";

const realFetch = globalThis.fetch;
afterEach(() => {
    globalThis.fetch = realFetch;
});

const downloadRaw = {
    id: "d1",
    type: "download",
    url: "https://files.test/a.bin",
    platform: "direct",
    media_kind: "file",
    title: "a.bin",
    status: "completed",
    progress: 100,
    downloaded_size: 10,
    total_size: 10,
    category: { id: "c1", name: "Music" },
};

describe("a row's category", () => {
    it("is read from the row the API sends", () => {
        expect(normalizeApiTask(downloadRaw).category).toEqual({
            id: "c1",
            name: "Music",
        });
    });

    it("is absent when the API sends none", () => {
        const { category: _, ...bare } = downloadRaw;
        expect(normalizeApiTask(bare).category).toBeUndefined();
    });

    it("shows as a Category row in the detail panel", () => {
        const rows = detailRows(normalizeApiTask(downloadRaw), 0);
        expect(rows).toContainEqual({ label: "Category", value: "Music" });
    });
});

describe("the add requests", () => {
    it("send the category last, and only when one is chosen", () => {
        expect(
            directRequest({
                type: "url",
                value: "https://x/a.iso",
                categoryId: "c1",
            }).body,
        ).toEqual({ url: "https://x/a.iso", category_id: "c1" });
        expect(
            directRequest({ type: "url", value: "https://x/a.iso" }).body,
        ).toEqual({ url: "https://x/a.iso" });
    });

    it("send the category with a playlist, and leave it out otherwise", () => {
        const target = {
            url: "https://www.youtube.com/playlist?list=PL1",
            extractor: "YoutubeTab",
            playlistId: "PL1",
            title: "Talks",
            channelTab: false,
        } as any;
        expect(
            playlistRequest(target, "best", [], new Set(), "c1").category_id,
        ).toBe("c1");
        expect(
            "category_id" in playlistRequest(target, "best", [], new Set()),
        ).toBe(false);
    });

    it("send the category with a batch", async () => {
        const bodies: unknown[] = [];
        const post = async <T>(_path: string, body: unknown): Promise<T> => {
            bodies.push(body);
            return [] as T;
        };
        await addBatch(
            { kind: "lines", text: "https://x/a.bin" } as any,
            "best",
            post,
            "c1",
        );
        expect(bodies[0]).toMatchObject({ category_id: "c1", preset: "best" });
    });

    it("send the category with a torrent", async () => {
        let body: unknown;
        globalThis.fetch = (async (_url: string, init?: RequestInit) => {
            body = JSON.parse(String(init?.body));
            return new Response(
                JSON.stringify({
                    status: "success",
                    code: 201,
                    data: { id: "t1" },
                }),
            );
        }) as unknown as typeof globalThis.fetch;

        const id = await addTorrent(
            "magnet:?xt=urn:btih:abc",
            [
                0,
            ],
            "c1",
        );

        expect(id).toBe("t1");
        expect(body).toEqual({
            torrent: "magnet:?xt=urn:btih:abc",
            files: [
                0,
            ],
            category_id: "c1",
        });
    });
});
