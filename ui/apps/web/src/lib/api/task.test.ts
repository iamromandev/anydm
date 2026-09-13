import { describe, expect, it } from "bun:test";

import { normalizeApiTask } from "./task";

describe("normalizeApiTask", () => {
    const raw = {
        id: "abc",
        source_url: "https://youtu.be/x",
        title: "clip",
        filename: "clip.mp4",
        kind: "video",
        preset: "1080",
        status: "downloading",
        progress: 42,
        downloaded_bytes: 4200,
        total_bytes: 10000,
        speed_bps: 512,
        eta_seconds: 11,
        error: null,
    };

    it("maps snake_case onto the UI shape", () => {
        const task = normalizeApiTask(raw);
        expect(task.id).toBe("abc");
        expect(task.title).toBe("clip");
        expect(task.progress).toBe(42);
        expect(task.progressDetails.downloadedBytes).toBe(4200);
        expect(task.progressDetails.totalBytes).toBe(10000);
        expect(task.progressDetails.downloadSpeed).toBe(512);
        expect(task.eta).toBe(11);
    });

    it("tolerates nulls", () => {
        const task = normalizeApiTask({
            ...raw,
            total_bytes: null,
            eta_seconds: null,
            speed_bps: 0,
        });
        expect(task.progressDetails.totalBytes).toBe(0);
        expect(task.eta).toBe(0);
    });

    it("falls back to the filename when there is no title", () => {
        expect(normalizeApiTask({ ...raw, title: "" }).title).toBe("clip.mp4");
    });
});
