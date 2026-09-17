import { describe, expect, it } from "bun:test";

import {
    buildStreamStartBody,
    isTorrentKind,
    normalizeStreamSession,
    normalizeStreamStatusEvent,
} from "./stream";

describe("normalizeStreamSession", () => {
    it("maps snake_case onto the UI shape", () => {
        const session = normalizeStreamSession({
            session_id: "abc123",
            playlist_url: "/stream/abc123/playlist.m3u8",
            status: "ready",
            duration_seconds: 125.5,
            has_video: true,
        });
        expect(session.sessionId).toBe("abc123");
        expect(session.playlistUrl).toBe("/stream/abc123/playlist.m3u8");
        expect(session.status).toBe("ready");
        expect(session.durationSeconds).toBe(125.5);
        expect(session.hasVideo).toBe(true);
    });

    it("defaults safely on a missing payload", () => {
        const session = normalizeStreamSession({});
        expect(session.sessionId).toBe("");
        expect(session.playlistUrl).toBe("");
        expect(session.status).toBe("ready");
        expect(session.durationSeconds).toBe(0);
        expect(session.hasVideo).toBe(false);
    });

    it("carries a connecting status with null duration and video", () => {
        const session = normalizeStreamSession({
            session_id: "abc123",
            playlist_url: "/stream/abc123/playlist.m3u8",
            status: "connecting",
        });
        expect(session.status).toBe("connecting");
        expect(session.durationSeconds).toBeNull();
        expect(session.hasVideo).toBeNull();
    });
});

describe("normalizeStreamStatusEvent", () => {
    it("maps snake_case swarm fields onto the UI shape", () => {
        const event = normalizeStreamStatusEvent({
            id: "abc123",
            status: "ready",
            peers_connected: 35,
            download_bps: 7_864_320,
            progress_bytes: 450_000_000,
            total_bytes: 900_000_000,
        });
        expect(event.id).toBe("abc123");
        expect(event.status).toBe("ready");
        expect(event.peersConnected).toBe(35);
        expect(event.downloadBps).toBe(7_864_320);
        expect(event.progressBytes).toBe(450_000_000);
        expect(event.totalBytes).toBe(900_000_000);
    });

    it("leaves swarm fields undefined when the payload has none", () => {
        const event = normalizeStreamStatusEvent({
            id: "abc123",
            status: "error",
            message: "boom",
        });
        expect(event.message).toBe("boom");
        expect(event.peersConnected).toBeUndefined();
        expect(event.downloadBps).toBeUndefined();
        expect(event.progressBytes).toBeUndefined();
        expect(event.totalBytes).toBeUndefined();
    });
});

describe("isTorrentKind", () => {
    it("is true for magnet", () => {
        expect(isTorrentKind("magnet")).toBe(true);
    });

    it("is true for torrent", () => {
        expect(isTorrentKind("torrent")).toBe(true);
    });

    it("is false for media", () => {
        expect(isTorrentKind("media")).toBe(false);
    });

    it("is false for youtube", () => {
        expect(isTorrentKind("youtube")).toBe(false);
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
