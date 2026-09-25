import { describe, expect, it } from "bun:test";

import {
    aggregateStats,
    appendPage,
    normalizeSummary,
    canPause,
    retryLabel,
    canResume,
    canStopSeeding,
    canDownloadTorrentFile,
    canPlayTask,
    downloadAction,
    isActive,
    isSeeding,
    keepSegments,
    normalizeApiTask,
    normalizeSegments,
    normalizeFiles,
    segmentLayout,
    settlePage,
    statusView,
    type SegmentView,
    type TaskStatus,
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

    it("keeps the site a task came from", () => {
        expect(
            normalizeApiTask({ ...raw, platform: "site", extractor: "Vimeo" })
                .extractor,
        ).toBe("Vimeo");
        expect(normalizeApiTask(raw).extractor).toBeUndefined();
    });

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

    it("calls a wait for disk space a wait, not a retry", () => {
        // The API hands the attempt back, so no count belongs in this line.
        const view = retryLabel(
            task({
                attempts: 0,
                maxAttempts: 3,
                nextAttemptAt: NOW + 30_000,
                errorCode: "insufficient_storage",
                error: "Not enough disk space: 0.5 GB free, 1.0 GB must stay free",
            }),
            NOW,
        );

        expect(view).toEqual({
            tone: "warning",
            headline: "Waiting for disk space · checking again in 30s",
            detail: "Not enough disk space: 0.5 GB free, 1.0 GB must stay free",
        });
    });

    it("says it is checking once the disk wait is over", () => {
        const view = retryLabel(
            task({
                nextAttemptAt: NOW - 1,
                errorCode: "insufficient_storage",
            }),
            NOW,
        );

        expect(view?.headline).toBe("Waiting for disk space · checking…");
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

describe("normalizeSummary", () => {
    it("reads the four counts the sidebar shows", () => {
        expect(
            normalizeSummary({
                all: 12,
                downloading: 3,
                seeding: 2,
                completed: 7,
            }),
        ).toEqual({ all: 12, downloading: 3, seeding: 2, completed: 7 });
    });

    it("treats a missing count as none, not as unknown", () => {
        expect(normalizeSummary({ all: 4 })).toEqual({
            all: 4,
            downloading: 0,
            seeding: 0,
            completed: 0,
        });
    });
});

describe("appendPage", () => {
    const row = (id: string): UiTask => ({
        id,
        title: id,
        url: "",
        kind: "file",
        status: "complete",
        progress: 100,
        eta: 0,
        attempts: 0,
        downloadedBytes: 0,
        totalBytes: 0,
        downloadSpeed: 0,
        uploadSpeed: 0,
        peersConnected: 0,
    });

    it("keeps what is already on screen and adds the new page after it", () => {
        const merged = appendPage(
            [
                row("a"),
                row("b"),
            ],
            [
                row("c"),
                row("d"),
            ],
        );

        expect(merged.map((t) => t.id)).toEqual([
            "a",
            "b",
            "c",
            "d",
        ]);
    });

    it("lets the newer copy of a row win, without moving it twice", () => {
        // A task can change between one page being fetched and the next, and
        // pages overlap whenever a row is added while reading.
        const stale = { ...row("b"), progress: 10 };
        const fresh = { ...row("b"), progress: 90 };

        const merged = appendPage(
            [
                row("a"),
                stale,
            ],
            [
                fresh,
                row("c"),
            ],
        );

        expect(merged.map((t) => t.id)).toEqual([
            "a",
            "b",
            "c",
        ]);
        expect(merged.find((t) => t.id === "b")?.progress).toBe(90);
    });

    it("is just the new page when nothing was loaded yet", () => {
        expect(
            appendPage(
                [],
                [
                    row("a"),
                ],
            ).map((t) => t.id),
        ).toEqual([
            "a",
        ]);
    });
});

describe("keepSegments", () => {
    const row = (id: string, segments?: SegmentView[]): UiTask => ({
        id,
        title: id,
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
        segments,
    });
    const strip: SegmentView[] = [
        { index: 0, start: 0, end: 9, downloaded: 5, downloadSpeed: 1 },
    ];

    it("carries the strip a held row has onto its new copy", () => {
        // Task rows never carry segments; only progress frames do.
        const kept = keepSegments(
            [
                row("a"),
                row("b"),
            ],
            [
                row("a", strip),
            ],
        );

        expect(kept.map((t) => t.segments)).toEqual([
            strip,
            undefined,
        ]);
    });
});

describe("settlePage", () => {
    const row = (id: string, status: TaskStatus = "downloading"): UiTask => ({
        id,
        title: id,
        url: "",
        kind: "file",
        status,
        progress: 0,
        eta: 0,
        attempts: 0,
        downloadedBytes: 0,
        totalBytes: 0,
        downloadSpeed: 0,
        uploadSpeed: 0,
        peersConnected: 0,
    });

    it("keeps the stream's copy of a row it changed while the page was in flight", () => {
        // The bug: a quick download finished between the server reading the
        // page and the page arriving, and the older row put it back.
        const settled = settlePage(
            [
                row("a", "downloading"),
            ],
            [
                row("a", "complete"),
            ],
            new Set([
                "a",
            ]),
        );

        expect(settled.map((t) => t.status)).toEqual([
            "complete",
        ]);
    });

    it("takes the page's copy of every row the stream left alone", () => {
        const settled = settlePage(
            [
                { ...row("a"), progress: 60 },
                row("b", "complete"),
            ],
            [
                { ...row("a"), progress: 20 },
                row("b", "downloading"),
            ],
            new Set(),
        );

        expect(
            settled.map((t) => [
                t.id,
                t.status,
                t.progress,
            ]),
        ).toEqual([
            [
                "a",
                "downloading",
                60,
            ],
            [
                "b",
                "complete",
                0,
            ],
        ]);
    });

    it("leaves out a row the stream removed while the page was in flight", () => {
        // Cancelling in another tab publishes the row, and `mergeTasks` drops
        // it; a page read before the cancel still has it.
        const settled = settlePage(
            [
                row("a"),
                row("b"),
            ],
            [
                row("b"),
            ],
            new Set([
                "a",
            ]),
        );

        expect(settled.map((t) => t.id)).toEqual([
            "b",
        ]);
    });

    it("keeps a row the stream added that the page was read too early to hold", () => {
        const settled = settlePage(
            [
                row("a"),
            ],
            [
                row("new"),
                row("a"),
            ],
            new Set([
                "new",
            ]),
        );

        expect(settled.map((t) => t.id)).toEqual([
            "new",
            "a",
        ]);
    });

    it("does not bring back an untouched row the page no longer has", () => {
        // Rows missing from page 1 because they moved down, or out of the
        // filter, are the page's call.
        const settled = settlePage(
            [
                row("a"),
            ],
            [
                row("gone"),
                row("a"),
            ],
            new Set(),
        );

        expect(settled.map((t) => t.id)).toEqual([
            "a",
        ]);
    });
});

describe("playing a finished task (#94)", () => {
    const file = (path: string, selected = true) => ({
        index: 0,
        path,
        sizeBytes: 10,
        selected,
        downloadedBytes: 10,
    });

    it("plays a finished video or audio download", () => {
        expect(canPlayTask({ status: "complete", kind: "video" })).toBe(true);
        expect(canPlayTask({ status: "complete", kind: "audio" })).toBe(true);
    });

    it("waits for it to finish", () => {
        expect(canPlayTask({ status: "downloading", kind: "video" })).toBe(
            false,
        );
    });

    it("plays a direct download only when it's a media file", () => {
        expect(
            canPlayTask({
                status: "complete",
                kind: "file",
                filename: "a.MKV",
            }),
        ).toBe(true);
        expect(
            canPlayTask({
                status: "complete",
                kind: "file",
                filename: "a.zip",
            }),
        ).toBe(false);
    });

    it("plays a torrent with a selected media file, seeding included", () => {
        expect(
            canPlayTask({
                status: "seeding",
                kind: "torrent",
                files: [
                    file("Movie.mkv"),
                    file("readme.txt"),
                ],
            }),
        ).toBe(true);
        expect(
            canPlayTask({
                status: "complete",
                kind: "torrent",
                files: [
                    file("Movie.mkv", false),
                    file("readme.txt"),
                ],
            }),
        ).toBe(false);
        expect(canPlayTask({ status: "complete", kind: "torrent" })).toBe(
            false,
        );
    });
});

describe("downloading a finished task's file (#107)", () => {
    const file = (index: number, selected = true) => ({
        index,
        path: `file${index}`,
        sizeBytes: 10,
        selected,
        downloadedBytes: 10,
    });

    it("offers nothing until the task is complete or seeding", () => {
        expect(
            downloadAction({
                status: "downloading",
                files: [
                    file(0),
                ],
            }),
        ).toBe("none");
        expect(downloadAction({ status: "paused" })).toBe("none");
    });

    it("downloads the one file of a direct task or a single-file torrent", () => {
        expect(downloadAction({ status: "complete" })).toBe("file");
        expect(
            downloadAction({
                status: "seeding",
                files: [
                    file(0),
                    file(1, false),
                ],
            }),
        ).toBe("file");
    });

    it("asks to choose when a torrent has several selected files", () => {
        expect(
            downloadAction({
                status: "seeding",
                files: [
                    file(0),
                    file(1),
                ],
            }),
        ).toBe("choose");
    });

    it("links a torrent's own files once it is finished, but only selected ones", () => {
        expect(canDownloadTorrentFile("seeding", file(0))).toBe(true);
        expect(canDownloadTorrentFile("complete", file(0))).toBe(true);
        expect(canDownloadTorrentFile("downloading", file(0))).toBe(false);
        expect(canDownloadTorrentFile("complete", file(1, false))).toBe(false);
    });
});
