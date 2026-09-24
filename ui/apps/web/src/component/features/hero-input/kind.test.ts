import { describe, expect, it } from "bun:test";

import { detectKind, isPlayableKind } from "./kind";

describe("detectKind", () => {
    it("detects magnet links", () => {
        expect(detectKind("magnet:?xt=urn:btih:ABC123&dn=Some.Movie")).toBe(
            "magnet",
        );
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

    it("sends any other file link straight to a direct download", () => {
        expect(detectKind("https://example.com/setup.zip")).toBe("url");
        expect(detectKind("https://example.com/paper.pdf")).toBe("url");
        expect(detectKind("https://example.com/disk.iso?mirror=2")).toBe("url");
    });

    it("treats every page link as a site to ask the API about", () => {
        expect(detectKind("https://www.youtube.com/watch?v=abc123")).toBe(
            "site",
        );
        expect(detectKind("https://youtu.be/abc123")).toBe("site");
        expect(detectKind("https://vimeo.com/75629013")).toBe("site");
        expect(detectKind("https://soundcloud.com/artist/track")).toBe("site");
        expect(detectKind("https://example.com/some/article")).toBe("site");
    });

    it("counts web page extensions as pages, not files", () => {
        expect(detectKind("https://example.com/watch.html")).toBe("site");
        expect(detectKind("https://example.com/video.php?id=1")).toBe("site");
    });

    it("falls back to url for unparsable input", () => {
        expect(detectKind("not a url")).toBe("url");
    });
});

describe("isPlayableKind", () => {
    it("is true for magnet, torrent and media", () => {
        expect(isPlayableKind("magnet")).toBe(true);
        expect(isPlayableKind("torrent")).toBe(true);
        expect(isPlayableKind("media")).toBe(true);
    });

    it("is false for sites until playing them is supported", () => {
        expect(isPlayableKind("site")).toBe(false);
    });

    it("is false for generic url and auto", () => {
        expect(isPlayableKind("url")).toBe(false);
        expect(isPlayableKind("auto")).toBe(false);
    });
});
