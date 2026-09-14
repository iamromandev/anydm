import { describe, expect, it } from "bun:test";

import {
    canPause,
    canResume,
    canStopSeeding,
    isActive,
    isSeeding,
    normalizeApiTask,
    normalizeSegments,
    normalizeTorrentFiles,
    segmentLayout,
    statusView,
} from "./task";

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
        expect(task.eta).toBe(11);
    });

    // The card reads these off the task itself. A nested bag of the same
    // numbers renders as nothing at all, which is what it used to do.
    it("exposes byte and speed counters at the top level", () => {
        const task = normalizeApiTask(raw);
        expect(task.downloadedBytes).toBe(4200);
        expect(task.totalBytes).toBe(10000);
        expect(task.downloadSpeed).toBe(512);
    });

    it("carries the source url the list searches on", () => {
        expect(normalizeApiTask(raw).url).toBe("https://youtu.be/x");
    });

    it("tolerates nulls", () => {
        const task = normalizeApiTask({
            ...raw,
            total_bytes: null,
            eta_seconds: null,
            speed_bps: 0,
        });
        expect(task.totalBytes).toBe(0);
        expect(task.eta).toBe(0);
    });

    it("falls back to the filename when there is no title", () => {
        expect(normalizeApiTask({ ...raw, title: "" }).title).toBe("clip.mp4");
    });

    it("keeps a direct download's own kind", () => {
        expect(normalizeApiTask({ ...raw, kind: "file" }).kind).toBe("file");
    });
});

describe("statusView", () => {
    it("labels every status the API can emit", () => {
        expect(statusView("pending").label).toBe("Queued");
        expect(statusView("downloading").label).toBe("Downloading");
        expect(statusView("muxing").label).toBe("Processing");
        expect(statusView("paused").label).toBe("Paused");
        expect(statusView("complete").label).toBe("Complete");
        expect(statusView("failed").label).toBe("Failed");
        expect(statusView("canceled").label).toBe("Canceled");
    });

    // A cancelled task arrives over SSE. Returning undefined here is what
    // threw on `statusConfig.icon` and took the whole list down with it.
    it("falls back to a rendered view for a status it does not know", () => {
        const view = statusView("teleporting");
        expect(view.key).toBe("unknown");
        expect(view.label).toBe("Unknown");
    });
});

describe("canPause", () => {
    it("allows pausing an in-flight download whatever its kind", () => {
        expect(canPause("downloading")).toBe(true);
        expect(canPause("pending")).toBe(true);
    });

    it("refuses to pause a task that is no longer running", () => {
        expect(canPause("paused")).toBe(false);
        expect(canPause("complete")).toBe(false);
        expect(canPause("failed")).toBe(false);
        expect(canPause("canceled")).toBe(false);
    });

    it("refuses to pause a muxing task, which cannot be interrupted", () => {
        expect(canPause("muxing")).toBe(false);
    });
});

describe("canResume", () => {
    it("allows resuming a paused task", () => {
        expect(canResume("paused")).toBe(true);
    });

    it("allows retrying a failed task", () => {
        expect(canResume("failed")).toBe(true);
    });

    it("refuses to resume a task that is already running", () => {
        expect(canResume("downloading")).toBe(false);
        expect(canResume("complete")).toBe(false);
    });
});

describe("isActive", () => {
    it("counts everything still on its way to a file", () => {
        expect(isActive("pending")).toBe(true);
        expect(isActive("downloading")).toBe(true);
        expect(isActive("muxing")).toBe(true);
    });

    it("excludes settled and paused tasks", () => {
        expect(isActive("paused")).toBe(false);
        expect(isActive("complete")).toBe(false);
        expect(isActive("canceled")).toBe(false);
    });
});

describe("normalizeSegments", () => {
    it("maps snake_case segments onto the UI shape", () => {
        const segments = normalizeSegments({
            segments: [
                {
                    index: 0,
                    start: 0,
                    end: 499,
                    downloaded: 200,
                    speed_bps: 60,
                },
                {
                    index: 1,
                    start: 500,
                    end: 999,
                    downloaded: 100,
                    speed_bps: 40,
                },
            ],
        });
        expect(segments).toHaveLength(2);
        expect(segments![1].start).toBe(500);
        expect(segments![1].downloadSpeed).toBe(40);
    });

    // Absent means "this transfer is not segmented", which is not the same as
    // "it has no segments right now".
    it("returns undefined when the key is absent", () => {
        expect(normalizeSegments({ progress: 10 })).toBeUndefined();
    });

    it("returns undefined for a malformed array", () => {
        expect(normalizeSegments({ segments: "nope" })).toBeUndefined();
    });
});

describe("segmentLayout", () => {
    it("gives each segment a width proportional to its byte range", () => {
        const layout = segmentLayout([
            { index: 0, start: 0, end: 749, downloaded: 750, downloadSpeed: 0 },
            { index: 1, start: 750, end: 999, downloaded: 0, downloadSpeed: 0 },
        ]);
        expect(layout[0].widthPercent).toBeCloseTo(75);
        expect(layout[1].widthPercent).toBeCloseTo(25);
    });

    it("fills each segment by its own progress, not the file's", () => {
        const layout = segmentLayout([
            { index: 0, start: 0, end: 999, downloaded: 250, downloadSpeed: 0 },
        ]);
        expect(layout[0].fillPercent).toBeCloseTo(25);
    });

    it("survives an empty list", () => {
        expect(segmentLayout([])).toEqual([]);
    });
});

describe("torrent fields", () => {
    const rawTorrent = {
        id: "t1",
        source_url: "magnet:?xt=urn:btih:abc",
        title: "Some Release",
        filename: "Some Release",
        kind: "torrent",
        preset: "best",
        status: "seeding",
        progress: 100,
        downloaded_bytes: 1000,
        total_bytes: 1000,
        speed_bps: 0,
        eta_seconds: null,
        info_hash: "abc",
        uploaded_bytes: 500,
        peers_connected: 7,
        files: [
            { index: 0, path: "video.mkv", size_bytes: 900, selected: true, downloaded_bytes: 900 },
            { index: 1, path: "readme.txt", size_bytes: 100, selected: false, downloaded_bytes: 0 },
        ],
    };

    it("maps upload, peers and info hash onto the task", () => {
        const task = normalizeApiTask(rawTorrent);
        expect(task.peersConnected).toBe(7);
        expect(task.infoHash).toBe("abc");
        expect(task.uploadSpeed).toBe(0);
    });

    it("computes the share ratio from uploaded over downloaded", () => {
        expect(normalizeApiTask(rawTorrent).ratio).toBe(0.5);
    });

    it("leaves the ratio undefined when nothing has downloaded", () => {
        const task = normalizeApiTask({ ...rawTorrent, downloaded_bytes: 0 });
        expect(task.ratio).toBeUndefined();
    });

    it("maps the file list, keeping the selection", () => {
        const task = normalizeApiTask(rawTorrent);
        expect(task.files).toHaveLength(2);
        expect(task.files?.[0].path).toBe("video.mkv");
        expect(task.files?.[0].selected).toBe(true);
        expect(task.files?.[1].downloadedBytes).toBe(0);
    });

    it("leaves files undefined for a task that is not a torrent", () => {
        const task = normalizeApiTask({ ...rawTorrent, files: undefined });
        expect(task.files).toBeUndefined();
    });

    it("never invents seeders or leechers", () => {
        // rqbit reports connected peers and never splits the swarm.
        const task = normalizeApiTask(rawTorrent);
        expect(task.seeders).toBeUndefined();
        expect(task.leechers).toBeUndefined();
    });
});

describe("normalizeTorrentFiles", () => {
    it("returns undefined when the key is absent", () => {
        expect(normalizeTorrentFiles({ progress: 10 })).toBeUndefined();
    });

    it("returns undefined for a malformed array", () => {
        expect(normalizeTorrentFiles({ files: "nope" })).toBeUndefined();
    });
});

describe("seeding status", () => {
    it("has a label rather than rendering as unknown", () => {
        expect(statusView("seeding")).toEqual({ key: "seeding", label: "Seeding" });
    });

    it("is not resumable through the ordinary controls", () => {
        expect(canResume("seeding")).toBe(false);
    });

    it("can be paused, since the engine accepts it", () => {
        expect(canPause("seeding")).toBe(true);
    });

    it("can be stopped", () => {
        expect(canStopSeeding("seeding")).toBe(true);
        expect(canStopSeeding("downloading")).toBe(false);
        expect(canStopSeeding("complete")).toBe(false);
    });

    it("is not counted as active, because nothing is still arriving", () => {
        expect(isActive("seeding")).toBe(false);
        expect(isSeeding("seeding")).toBe(true);
        expect(isSeeding("complete")).toBe(false);
        expect(isSeeding("downloading")).toBe(false);
    });
});
