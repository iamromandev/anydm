import { describe, expect, it } from "bun:test";

import { normalizeStreamSession } from "./stream";

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
