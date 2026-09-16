import { describe, expect, it } from "bun:test";

import { buildStreamStartBody, normalizeStreamSession } from "./stream";

describe("normalizeStreamSession", () => {
    it("maps snake_case onto the UI shape", () => {
        const session = normalizeStreamSession({
            session_id: "abc123",
            playlist_url: "/stream/abc123/playlist.m3u8",
            duration_seconds: 125.5,
            has_video: true,
        });
        expect(session.sessionId).toBe("abc123");
        expect(session.playlistUrl).toBe("/stream/abc123/playlist.m3u8");
        expect(session.durationSeconds).toBe(125.5);
        expect(session.hasVideo).toBe(true);
    });

    it("defaults safely on a missing payload", () => {
        const session = normalizeStreamSession({});
        expect(session.sessionId).toBe("");
        expect(session.playlistUrl).toBe("");
        expect(session.durationSeconds).toBe(0);
        expect(session.hasVideo).toBe(false);
    });
});

describe("buildStreamStartBody", () => {
    it("builds {torrent} for the magnet kind", () => {
        expect(
            buildStreamStartBody("magnet:?xt=urn:btih:abc", "magnet"),
        ).toEqual({
            torrent: "magnet:?xt=urn:btih:abc",
        });
    });

    it("builds {torrent} for the torrent kind", () => {
        expect(buildStreamStartBody("aGVsbG8=", "torrent")).toEqual({
            torrent: "aGVsbG8=",
        });
    });

    it("builds {url} for the media kind", () => {
        expect(
            buildStreamStartBody("https://example.com/clip.mp4", "media"),
        ).toEqual({ url: "https://example.com/clip.mp4" });
    });

    it("builds {url} for the youtube kind", () => {
        expect(buildStreamStartBody("https://youtu.be/abc", "youtube")).toEqual(
            { url: "https://youtu.be/abc" },
        );
    });
});
