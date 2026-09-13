import { describe, expect, it } from "bun:test";

import { normalizeApiTask, normalizeBunTask } from "./task";

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

    it("marks the task as served by the FastAPI service", () => {
        expect(normalizeApiTask(raw).source).toBe("api");
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

describe("normalizeBunTask", () => {
    it("keeps the Bun torrent shape and tags its source", () => {
        const task = normalizeBunTask({
            id: "t1",
            title: "ubuntu.iso",
            kind: "torrent",
            status: "downloading",
            progress: 10,
            progressDetails: {
                downloadedBytes: 100,
                totalBytes: 1000,
                downloadSpeed: 50,
                uploadSpeed: 5,
                eta: 18,
                peersConnected: 3,
            },
        });
        expect(task.source).toBe("bun");
        expect(task.progressDetails.peersConnected).toBe(3);
    });

    it("tolerates a torrent with no progress details yet", () => {
        const task = normalizeBunTask({
            id: "t2",
            title: "x",
            kind: "torrent",
            status: "pending",
            progress: 0,
        });
        expect(task.progressDetails.downloadedBytes).toBe(0);
        expect(task.progressDetails.peersConnected).toBe(0);
    });
});
