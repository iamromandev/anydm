import { describe, expect, it } from "bun:test";

import {
    normalizeFetched,
    normalizeFound,
    normalizeSearch,
    normalizeVideos,
    searchParams,
    videoParams,
} from "./search";

describe("searchParams", () => {
    it("leaves q out to browse, and sends fresh only when asked", () => {
        expect(searchParams("", "tv", false)).toBe("category=tv");
        expect(searchParams("", "tv", true)).toBe("category=tv&fresh=1");
    });

    it("sends the trimmed query to search", () => {
        expect(searchParams("  big buck bunny ", "movies", false)).toBe(
            "q=big+buck+bunny&category=movies",
        );
    });
});

describe("normalizeFound", () => {
    it("maps snake_case, and a field the API left out is null", () => {
        const found = normalizeFound({
            title: "Sintel Soundtrack FLAC",
            size: 31457280,
            category: "music",
            link: "http://prowlarr:9696/1/download?link=abc",
            indexers: [
                "prowlarr-1",
            ],
        });

        expect(found).toEqual({
            title: "Sintel Soundtrack FLAC",
            sizeBytes: 31457280,
            seeders: null,
            leechers: null,
            published: null,
            category: "music",
            infoHash: null,
            magnet: null,
            link: "http://prowlarr:9696/1/download?link=abc",
            copyFrom: "",
            indexers: [
                "prowlarr-1",
            ],
        });
    });

    it("keeps zero seeders as zero, not unknown", () => {
        expect(
            normalizeFound({ title: "x", seeders: 0, indexers: [] }).seeders,
        ).toBe(0);
    });

    it("names the source the copy came from", () => {
        expect(
            normalizeFound({
                title: "x",
                link: "http://p/dl",
                copy_from: "nyaa",
            }).copyFrom,
        ).toBe("nyaa");
    });

    it("leaves the source empty when the API sends none", () => {
        expect(
            normalizeFound({ title: "x", link: "http://p/dl" }).copyFrom,
        ).toBe("");
    });
});

describe("normalizeSearch", () => {
    it("reads results, errors and the time taken", () => {
        const answer = normalizeSearch({
            results: [
                {
                    title: "a",
                    indexers: [
                        "p",
                    ],
                    info_hash: "aa",
                    magnet: "magnet:?xt=urn:btih:aa",
                },
            ],
            errors: [
                { indexer: "broken", message: "Invalid API Key" },
            ],
            took_ms: 912,
        });

        expect(answer.results[0].infoHash).toBe("aa");
        expect(answer.errors).toEqual([
            { indexer: "broken", message: "Invalid API Key" },
        ]);
        expect(answer.tookMs).toBe(912);
    });

    it("survives an empty answer", () => {
        expect(normalizeSearch({})).toEqual({
            results: [],
            errors: [],
            asked: [],
            tookMs: 0,
        });
    });

    it("reads which sources were asked", () => {
        expect(
            normalizeSearch({
                results: [],
                asked: [
                    "apibay",
                    "nyaa",
                    7,
                ],
                took_ms: 5,
            }).asked,
        ).toEqual([
            "apibay",
            "nyaa",
        ]);
    });
});

describe("normalizeFetched", () => {
    it("is a magnet when the indexer redirected to one, else the file", () => {
        expect(normalizeFetched({ magnet: "magnet:?xt=urn:btih:aa" })).toEqual({
            type: "magnet",
            value: "magnet:?xt=urn:btih:aa",
        });
        expect(normalizeFetched({ torrent: "ZDg6" })).toEqual({
            type: "file",
            value: "ZDg6",
        });
    });

    it("refuses an answer with neither", () => {
        expect(() => normalizeFetched({})).toThrow(
            "The indexer sent nothing to add",
        );
    });
});

describe("videoParams", () => {
    it("sends the trimmed query", () => {
        expect(videoParams("  big buck bunny ")).toBe("q=big+buck+bunny");
    });
});

describe("normalizeVideos", () => {
    it("maps snake_case, and a field the API left out is null", () => {
        const answer = normalizeVideos({
            results: [
                {
                    title: "Big Buck Bunny",
                    url: "https://www.youtube.com/watch?v=a",
                    channel: "Blender",
                    duration: 596,
                    views: 62000000,
                },
            ],
            took_ms: 3200,
        });

        expect(answer).toEqual({
            results: [
                {
                    title: "Big Buck Bunny",
                    url: "https://www.youtube.com/watch?v=a",
                    channel: "Blender",
                    durationS: 596,
                    thumbnail: null,
                    views: 62000000,
                    published: null,
                },
            ],
            tookMs: 3200,
        });
    });

    it("survives an answer with nothing in it", () => {
        expect(normalizeVideos({})).toEqual({ results: [], tookMs: 0 });
    });
});
