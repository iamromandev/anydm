import { describe, expect, it } from "bun:test";

import { normalizeResolvedTorrent } from "./torrent";

describe("normalizeResolvedTorrent", () => {
    const raw = {
        info_hash: "abc",
        title: "Some Release",
        total_bytes: 1000,
        files: [
            { index: 0, path: "video.mkv", size_bytes: 900, selected: true },
            { index: 1, path: "readme.txt", size_bytes: 100, selected: true },
        ],
    };

    it("maps snake_case onto the UI shape", () => {
        const resolved = normalizeResolvedTorrent(raw);
        expect(resolved.infoHash).toBe("abc");
        expect(resolved.title).toBe("Some Release");
        expect(resolved.totalBytes).toBe(1000);
    });

    it("keeps the torrent's own file order and indexes", () => {
        const resolved = normalizeResolvedTorrent(raw);
        expect(resolved.files.map((file) => file.index)).toEqual([
            0,
            1,
        ]);
        expect(resolved.files[0].path).toBe("video.mkv");
        expect(resolved.files[0].sizeBytes).toBe(900);
    });

    it("survives a torrent with no files", () => {
        const resolved = normalizeResolvedTorrent({ info_hash: "abc" });
        expect(resolved.files).toEqual([]);
        expect(resolved.title).toBe("");
        expect(resolved.totalBytes).toBe(0);
    });
});
