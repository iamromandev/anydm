import { describe, expect, it } from "bun:test";

import {
    addBatch,
    batchBody,
    batchSummary,
    hasLinks,
    linkCount,
    looksLikeMany,
    normalizeBatchItem,
    previewBatch,
    previewHead,
    type BatchItem,
} from "./batch";

function fakePost(answer: unknown) {
    const calls: Array<{ path: string; body: unknown }> = [];
    const post = async <T>(path: string, body: unknown): Promise<T> => {
        calls.push({ path, body });
        return answer as T;
    };
    return { post, calls };
}

describe("batchBody", () => {
    it("sends a list as its lines, for the API to trim", () => {
        expect(batchBody({ kind: "list", text: "a\r\nb\n" })).toEqual({
            lines: [
                "a",
                "b",
                "",
            ],
        });
    });

    it("sends a pattern as typed", () => {
        expect(
            batchBody({ kind: "pattern", text: "img[001-120].png" }),
        ).toEqual({ pattern: "img[001-120].png" });
    });
});

describe("hasLinks", () => {
    it("is false for nothing but space", () => {
        expect(hasLinks({ kind: "list", text: " \n \n" })).toBe(false);
        expect(hasLinks({ kind: "pattern", text: "x" })).toBe(true);
    });
});

describe("previewBatch", () => {
    it("asks the preview route and reads its count and links", async () => {
        const { post, calls } = fakePost({
            count: 2,
            urls: [
                "u1",
                "u2",
            ],
        });

        const preview = await previewBatch(
            { kind: "pattern", text: "u[1-2]" },
            post,
        );

        expect(calls).toEqual([
            {
                path: "/download/batch/preview",
                body: { pattern: "u[1-2]" },
            },
        ]);
        expect(preview).toEqual({
            count: 2,
            urls: [
                "u1",
                "u2",
            ],
        });
    });
});

describe("addBatch", () => {
    it("adds with the preset and reads every link's result", async () => {
        const { post, calls } = fakePost([
            { url: "a", result: "added", download_id: "d1" },
            {
                url: "b",
                result: "duplicate",
                download_id: "d0",
                message: "Already in your list: b (completed)",
            },
            { url: "c", result: "error", message: "Video unavailable" },
        ]);

        const items = await addBatch(
            { kind: "list", text: "a\nb\nc" },
            "720",
            post,
        );

        expect(calls[0]).toEqual({
            path: "/download/batch",
            body: {
                lines: [
                    "a",
                    "b",
                    "c",
                ],
                preset: "720",
            },
        });
        expect(items.map((item) => item.result)).toEqual([
            "added",
            "duplicate",
            "error",
        ]);
        expect(items[1].downloadId).toBe("d0");
        expect(items[2]).toEqual({
            url: "c",
            result: "error",
            downloadId: null,
            message: "Video unavailable",
        });
    });
});

describe("normalizeBatchItem", () => {
    it("reads an outcome it does not know as an error", () => {
        expect(normalizeBatchItem({ url: "x", result: "weird" }).result).toBe(
            "error",
        );
    });
});

describe("previewHead", () => {
    it("shows the first few and counts the rest", () => {
        const urls = Array.from({ length: 120 }, (_, i) => `u${i}`);
        expect(previewHead({ count: 120, urls })).toEqual({
            head: [
                "u0",
                "u1",
                "u2",
                "u3",
                "u4",
            ],
            more: 115,
        });
    });

    it("has nothing more for a short list", () => {
        expect(
            previewHead({
                count: 2,
                urls: [
                    "a",
                    "b",
                ],
            }).more,
        ).toBe(0);
    });
});

describe("wording", () => {
    it("counts links", () => {
        expect(linkCount(1)).toBe("1 link");
        expect(linkCount(1000)).toBe("1,000 links");
    });

    it("sums up every outcome, zeros included", () => {
        const item = (result: BatchItem["result"]): BatchItem => ({
            url: "u",
            result,
            downloadId: null,
            message: "",
        });
        expect(
            batchSummary([
                item("added"),
                item("added"),
                item("duplicate"),
            ]),
        ).toBe("2 added · 1 already in your list · 0 failed");
    });
});

describe("looksLikeMany", () => {
    it("is true for two or more lines with links on them", () => {
        expect(looksLikeMany("https://a.test/1\nhttps://a.test/2")).toBe(true);
        expect(
            looksLikeMany("https://a.test/1\r\n\r\nhttps://a.test/2\n"),
        ).toBe(true);
    });

    it("is false for one link, even with a newline after it", () => {
        expect(looksLikeMany("https://a.test/1")).toBe(false);
        expect(looksLikeMany("https://a.test/1\n")).toBe(false);
        expect(looksLikeMany("\n  \nhttps://a.test/1\n ")).toBe(false);
    });
});
