import { describe, expect, it } from "bun:test";

import { detectKind, isPlayableKind } from "./kind";

describe("detectKind", () => {
    it("detects magnet links", () => {
        expect(detectKind("magnet:?xt=urn:btih:ABC123&dn=Some.Movie")).toBe(
            "magnet",
        );
    });

    it("detects youtube links", () => {
        expect(detectKind("https://www.youtube.com/watch?v=abc123")).toBe(
            "youtube",
        );
        expect(detectKind("https://youtu.be/abc123")).toBe("youtube");
    });

    it("detects .torrent file links", () => {
        expect(detectKind("https://example.com/movie.torrent")).toBe("torrent");
    });

    it("detects direct media file links", () => {
        expect(detectKind("https://example.com/movie.mkv")).toBe("media");
        expect(detectKind("https://example.com/clip.mp4")).toBe("media");
        expect(detectKind("https://example.com/song.mp3")).toBe("media");
        expect(detectKind("https://example.com/song.MP3")).toBe("media");
    });

    it("treats plain webpages as generic url", () => {
        expect(detectKind("https://example.com/some/article")).toBe("url");
    });

    it("falls back to url for unparsable input", () => {
        expect(detectKind("not a url")).toBe("url");
    });
});

describe("isPlayableKind", () => {
    it("is true for magnet, torrent, youtube, and media", () => {
        expect(isPlayableKind("magnet")).toBe(true);
        expect(isPlayableKind("torrent")).toBe(true);
        expect(isPlayableKind("youtube")).toBe(true);
        expect(isPlayableKind("media")).toBe(true);
    });

    it("is false for generic url and auto", () => {
        expect(isPlayableKind("url")).toBe(false);
        expect(isPlayableKind("auto")).toBe(false);
    });
});
