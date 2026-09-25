import { describe, expect, it } from "bun:test";

import {
    buildStreamStartBody,
    buildTaskStreamBody,
    choosePlayback,
    isTorrentKind,
    NATIVE_LOAD_TIMEOUT_MS,
    nativeFailed,
    normalizeMediaInfo,
    normalizeStreamSession,
    normalizeStreamStatusEvent,
} from "./stream";

describe("playing a finished download (#94)", () => {
    const media = normalizeMediaInfo({
        filename: "Movie.mp4",
        duration_seconds: 30,
        has_video: true,
        media_type: 'video/mp4; codecs="avc1.640028, mp4a.40.2"',
        file_url: "/download/t1/file",
    });

    it("maps the media route onto the UI shape, a missing index and type as null", () => {
        expect(media).toEqual({
            fileIndex: null,
            filename: "Movie.mp4",
            durationSeconds: 30,
            hasVideo: true,
            mediaType: 'video/mp4; codecs="avc1.640028, mp4a.40.2"',
            fileUrl: "/download/t1/file",
        });
        expect(
            normalizeMediaInfo({ file_index: 3, file_url: "/x" }).mediaType,
        ).toBeNull();
        expect(normalizeMediaInfo({ file_index: 3 }).fileIndex).toBe(3);
    });

    it("plays the file itself when the browser says it can", () => {
        expect(choosePlayback(media, () => "probably")).toBe("native");
        expect(choosePlayback(media, () => "maybe")).toBe("native");
    });

    it("plays through a session when the browser can't, or there's no type to ask about", () => {
        expect(choosePlayback(media, () => "")).toBe("session");
        expect(
            choosePlayback({ ...media, mediaType: null }, () => "probably"),
        ).toBe("session");
    });

    it("gives up on the file when it errors, or shows no picture it should have", () => {
        expect(
            nativeFailed({ errored: true, hasVideo: false, videoWidth: 0 }),
        ).toBe(true);
        // WebKit says "probably" to VP9 and then draws nothing (#93).
        expect(
            nativeFailed({ errored: false, hasVideo: true, videoWidth: 0 }),
        ).toBe(true);
        expect(
            nativeFailed({ errored: false, hasVideo: true, videoWidth: 640 }),
        ).toBe(false);
        expect(
            nativeFailed({ errored: false, hasVideo: false, videoWidth: 0 }),
        ).toBe(false);
    });

    it("gives up on a file that never loads at all", () => {
        // WebKit answers "probably" to VP9 WebM, then fires neither loadeddata
        // nor error: without a time limit the player would wait forever.
        expect(
            nativeFailed({
                errored: false,
                hasVideo: true,
                videoWidth: 0,
                stalled: true,
            }),
        ).toBe(true);
        expect(
            nativeFailed({
                errored: false,
                hasVideo: false,
                videoWidth: 0,
                stalled: true,
            }),
        ).toBe(true);
        expect(NATIVE_LOAD_TIMEOUT_MS).toBeGreaterThan(0);
    });

    it("names a torrent's file when a link is played from the dialog (#98)", () => {
        expect(buildStreamStartBody("magnet:?x", "magnet", 2)).toEqual({
            torrent: "magnet:?x",
            file_index: 2,
        });
        expect(buildStreamStartBody("magnet:?x", "magnet")).toEqual({
            torrent: "magnet:?x",
        });
        // A link is one file: an index means nothing to it.
        expect(buildStreamStartBody("https://x/a.mp4", "media", 2)).toEqual({
            url: "https://x/a.mp4",
        });
    });

    it("starts a session from a task, with a torrent's file when there is one", () => {
        expect(buildTaskStreamBody("t1", null)).toEqual({ task_id: "t1" });
        expect(buildTaskStreamBody("t1", 4)).toEqual({
            task_id: "t1",
            file_index: 4,
        });
    });
});

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
