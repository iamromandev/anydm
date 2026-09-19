import { describe, expect, it } from "bun:test";

import {
    aggregateStats,
    canPause,
    retryLabel,
    canResume,
    canStopSeeding,
    isActive,
    isSeeding,
    normalizeApiTask,
    normalizeSegments,
    normalizeFiles,
    segmentLayout,
    statusView,
    type UiTask,
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
            {
                index: 0,
                path: "video.mkv",
                size_bytes: 900,
                selected: true,
                downloaded_bytes: 900,
            },
            {
                index: 1,
                path: "readme.txt",
                size_bytes: 100,
                selected: false,
                downloaded_bytes: 0,
            },
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

describe("normalizeFiles", () => {
    it("returns undefined when the key is absent", () => {
        expect(normalizeFiles({ progress: 10 })).toBeUndefined();
    });

    it("returns undefined for a malformed array", () => {
        expect(normalizeFiles({ files: "nope" })).toBeUndefined();
    });
});

describe("seeding status", () => {
    it("has a label rather than rendering as unknown", () => {
        expect(statusView("seeding")).toEqual({
            key: "seeding",
            label: "Seeding",
        });
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

describe("aggregateStats", () => {
    const task = (overrides: Partial<UiTask> = {}): UiTask => ({
        id: "t",
        title: "t",
        url: "",
        kind: "file",
        status: "downloading",
        progress: 0,
        eta: 0,
        attempts: 0,
        downloadedBytes: 0,
        totalBytes: 0,
        downloadSpeed: 0,
        uploadSpeed: 0,
        peersConnected: 0,
        ...overrides,
    });

    it("is all zeros when nothing is listed", () => {
        expect(aggregateStats([])).toEqual({
            downloadSpeed: 0,
            uploadSpeed: 0,
            totalDownloaded: 0,
            totalPeers: 0,
        });
    });

    it("adds up the rates of everything still transferring", () => {
        const stats = aggregateStats([
            task({
                id: "a",
                status: "downloading",
                downloadSpeed: 1000,
                downloadedBytes: 500,
            }),
            task({
                id: "b",
                status: "seeding",
                uploadSpeed: 250,
                peersConnected: 4,
                downloadedBytes: 900,
            }),
        ]);

        expect(stats.downloadSpeed).toBe(1000);
        expect(stats.uploadSpeed).toBe(250);
        expect(stats.totalPeers).toBe(4);
    });

    it("drops the rates a finished or failed row still carries", () => {
        const stats = aggregateStats([
            task({
                id: "a",
                status: "complete",
                downloadSpeed: 8000,
                peersConnected: 3,
            }),
            task({ id: "b", status: "failed", downloadSpeed: 4000 }),
            task({ id: "c", status: "canceled", uploadSpeed: 2000 }),
        ]);

        expect(stats.downloadSpeed).toBe(0);
        expect(stats.uploadSpeed).toBe(0);
        expect(stats.totalPeers).toBe(0);
    });

    it("counts the bytes of every row, finished ones included", () => {
        const stats = aggregateStats([
            task({ id: "a", status: "downloading", downloadedBytes: 120 }),
            task({ id: "b", status: "complete", downloadedBytes: 300 }),
            task({ id: "c", status: "failed", downloadedBytes: 80 }),
        ]);

        expect(stats.totalDownloaded).toBe(500);
    });
});

describe("retryLabel", () => {
    const NOW = 1_700_000_000_000;
    const task = (overrides: Partial<UiTask> = {}): UiTask => ({
        id: "t",
        title: "clip",
        url: "",
        kind: "file",
        status: "pending",
        progress: 0,
        eta: 0,
        attempts: 0,
        downloadedBytes: 0,
        totalBytes: 0,
        downloadSpeed: 0,
        uploadSpeed: 0,
        peersConnected: 0,
        ...overrides,
    });

    it("says nothing about a task that is simply queued", () => {
        expect(retryLabel(task(), NOW)).toBeNull();
    });

    it("says nothing about a task that is running", () => {
        expect(retryLabel(task({ status: "downloading" }), NOW)).toBeNull();
    });

    it("counts down to the next attempt and names the budget", () => {
        const view = retryLabel(
            task({
                attempts: 2,
                maxAttempts: 3,
                nextAttemptAt: NOW + 12_000,
            }),
            NOW,
        );

        expect(view).toEqual({
            tone: "warning",
            headline: "Retrying in 12s · attempt 2 of 3",
        });
    });

    it("rounds the wait up, so it never reads zero while still waiting", () => {
        const view = retryLabel(
            task({ attempts: 1, maxAttempts: 3, nextAttemptAt: NOW + 200 }),
            NOW,
        );

        expect(view?.headline).toBe("Retrying in 1s · attempt 1 of 3");
    });

    it("stops counting once the deadline has passed", () => {
        const view = retryLabel(
            task({ attempts: 1, maxAttempts: 3, nextAttemptAt: NOW - 5_000 }),
            NOW,
        );

        expect(view?.headline).toBe("Retrying… · attempt 1 of 3");
    });

    it("carries the reason the last attempt failed", () => {
        const view = retryLabel(
            task({
                attempts: 1,
                maxAttempts: 3,
                nextAttemptAt: NOW + 5_000,
                error: "connection reset",
            }),
            NOW,
        );

        expect(view?.detail).toBe("connection reset");
    });

    it("reports a final failure with the code the API gave", () => {
        const view = retryLabel(
            task({
                status: "failed",
                attempts: 3,
                maxAttempts: 3,
                error: "connection reset",
                errorCode: "network",
            }),
            NOW,
        );

        expect(view).toEqual({
            tone: "error",
            headline: "Failed after 3 attempts · network",
            detail: "connection reset",
        });
    });

    it("counts a single attempt in the singular", () => {
        const view = retryLabel(
            task({ status: "failed", attempts: 1, errorCode: "not_found" }),
            NOW,
        );

        expect(view?.headline).toBe("Failed after 1 attempt · not_found");
    });

    it("leaves the code out of a failure that came without one", () => {
        const view = retryLabel(task({ status: "failed", attempts: 2 }), NOW);

        expect(view?.headline).toBe("Failed after 2 attempts");
    });
});

describe("normalizeApiTask, for a retry in progress", () => {
    const raw = {
        id: "a",
        source_url: "https://example.com/a.mkv",
        kind: "file",
        status: "pending",
        attempts: 2,
        max_attempts: 3,
        error: "connection reset",
        error_code: "network",
        next_attempt_at: "2026-09-19T08:30:00+00:00",
    };

    it("reads the deadline as an instant", () => {
        expect(normalizeApiTask(raw).nextAttemptAt).toBe(
            Date.parse("2026-09-19T08:30:00Z"),
        );
    });

    it("leaves the deadline out when the API omitted it", () => {
        expect(
            normalizeApiTask({ ...raw, next_attempt_at: undefined })
                .nextAttemptAt,
        ).toBeUndefined();
    });

    it("carries the attempt count, the budget and the code", () => {
        const row = normalizeApiTask(raw);

        expect(row.attempts).toBe(2);
        expect(row.maxAttempts).toBe(3);
        expect(row.errorCode).toBe("network");
    });
});
