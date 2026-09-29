import { describe, expect, it } from "bun:test";

import { normalizeFetched, normalizeFound, normalizeSearch } from "./search";

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
            tookMs: 0,
        });
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
