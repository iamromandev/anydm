import { describe, expect, it } from "bun:test";

import {
    EMPTY_ENTRIES,
    addEntriesPage,
    applyVideoProgress,
    applyVideoRows,
    countsLine,
    dropVideo,
    groupMeta,
    groupProgressLine,
    groupSpeed,
    groupToast,
    keepWatched,
    placeRows,
    trackVideoSpeed,
    videoStatus,
    withGroupSpeeds,
} from "./group";
import type { EntryCounts, TaskStatus, UiTask } from "./task";

const row = (
    id: string,
    status: TaskStatus = "downloading",
    extra: Partial<UiTask> = {},
): UiTask => ({
    id,
    title: id,
    url: "",
    kind: "video",
    status,
    progress: 0,
    eta: 0,
    attempts: 0,
    downloadedBytes: 0,
    totalBytes: 0,
    downloadSpeed: 0,
    uploadSpeed: 0,
    peersConnected: 0,
    ...extra,
});

const COUNTS: EntryCounts = {
    total: 96,
    complete: 38,
    active: 57,
    downloading: 2,
    paused: 0,
    failed: 1,
};

describe("placeRows", () => {
    it("updates a held row where it is", () => {
        const placed = placeRows(
            [
                row("a"),
                row("b"),
                row("c"),
            ],
            [
                row("b", "complete"),
            ],
        );
        expect(
            placed.map((t) => [
                t.id,
                t.status,
            ]),
        ).toEqual([
            [
                "a",
                "downloading",
            ],
            [
                "b",
                "complete",
            ],
            [
                "c",
                "downloading",
            ],
        ]);
    });

    it("puts only rows new to the list on top", () => {
        const placed = placeRows(
            [
                row("a"),
                row("b"),
            ],
            [
                row("n"),
                row("b"),
            ],
        );
        expect(placed.map((t) => t.id)).toEqual([
            "n",
            "a",
            "b",
        ]);
    });

    it("never lists a group's video", () => {
        const placed = placeRows(
            [
                row("g"),
            ],
            [
                row("v", "pending", { parentId: "g" }),
            ],
        );
        expect(placed.map((t) => t.id)).toEqual([
            "g",
        ]);
    });

    it("drops a canceled row", () => {
        const placed = placeRows(
            [
                row("a"),
                row("b"),
            ],
            [
                row("a", "canceled"),
            ],
        );
        expect(placed.map((t) => t.id)).toEqual([
            "b",
        ]);
    });
});

describe("video speeds", () => {
    it("sums a group's running videos, and forgets one that stops", () => {
        let speeds = trackVideoSpeed({}, "g", "v1", 100);
        speeds = trackVideoSpeed(speeds, "g", "v2", 50);
        speeds = trackVideoSpeed(speeds, "h", "v3", 7);
        expect(groupSpeed(speeds, "g")).toBe(150);

        speeds = trackVideoSpeed(speeds, "g", "v1", 0);
        expect(groupSpeed(speeds, "g")).toBe(50);
        expect(groupSpeed(speeds, "none")).toBe(0);
    });

    it("gives each group its summed speed, and leaves other rows alone", () => {
        const plain = row("t", "downloading", { downloadSpeed: 9 });
        const tasks = [
            row("g", "downloading", { kind: "playlist", downloadSpeed: 1 }),
            plain,
        ];
        const sped = withGroupSpeeds(tasks, trackVideoSpeed({}, "g", "v", 300));
        expect(sped[0].downloadSpeed).toBe(300);
        expect(sped[1]).toBe(plain);
    });
});

describe("entries lists", () => {
    const open = {
        g: addEntriesPage(
            EMPTY_ENTRIES,
            [
                row("v1", "pending"),
                row("v2", "pending"),
            ],
            1,
            2,
        ),
    };

    it("adds a page after what's held, and keeps the held copy of a repeat", () => {
        const held = {
            ...open.g,
            rows: [
                row("v1", "complete"),
                row("v2"),
            ],
        };
        const next = addEntriesPage(
            held,
            [
                row("v2", "pending"),
                row("v3"),
            ],
            2,
            2,
        );
        expect(
            next.rows.map((t) => [
                t.id,
                t.status,
            ]),
        ).toEqual([
            [
                "v1",
                "complete",
            ],
            [
                "v2",
                "downloading",
            ],
            [
                "v3",
                "downloading",
            ],
        ]);
        expect([
            next.page,
            next.totalPages,
            next.loading,
        ]).toEqual([
            2,
            2,
            false,
        ]);
    });

    it("swaps a video's row in place, and takes a canceled one out", () => {
        const next = applyVideoRows(open, [
            row("v1", "downloading", { parentId: "g" }),
            row("v2", "canceled", { parentId: "g" }),
            row("x", "complete", { parentId: "shut" }),
        ]);
        expect(
            next.g.rows.map((t) => [
                t.id,
                t.status,
            ]),
        ).toEqual([
            [
                "v1",
                "downloading",
            ],
        ]);
        expect(next.shut).toBeUndefined();
    });

    it("patches a video's numbers from a progress frame", () => {
        const next = applyVideoProgress(open, "g", {
            id: "v1",
            progress: 40,
            speed_bps: 12,
        });
        expect([
            next.g.rows[0].progress,
            next.g.rows[0].downloadSpeed,
        ]).toEqual([
            40,
            12,
        ]);
        expect(applyVideoProgress(open, "shut", { id: "v1" })).toBe(open);
    });

    it("drops a removed video from whichever list holds it", () => {
        expect(dropVideo(open, "v1").g.rows.map((t) => t.id)).toEqual([
            "v2",
        ]);
        expect(dropVideo(open, "nope")).toBe(open);
    });
});

describe("the card's lines", () => {
    const group = row("g", "downloading", {
        kind: "playlist",
        title: "29C3",
        extractor: "Youtube",
        preset: "1080",
        entryCounts: COUNTS,
        downloadedBytes: 12.4 * 1024 ** 3,
    });

    it("names the site, the count and the preset", () => {
        expect(groupMeta(group)).toBe("YouTube · 96 videos · 1080p");
    });

    it("counts videos finished, and the bytes so far", () => {
        expect(groupProgressLine(group)).toBe("38 of 96 · 12.4 GB");
        expect(groupProgressLine({ ...group, downloadedBytes: 0 })).toBe(
            "38 of 96",
        );
    });

    it("adds how many were watched, once any were", () => {
        expect(
            groupProgressLine({
                ...group,
                entryCounts: { ...COUNTS, watched: 12 },
            }),
        ).toBe("38 of 96 · 12.4 GB · 12 watched");
    });

    it("says what's running, queued and failed, leaving out zeros", () => {
        expect(countsLine(COUNTS)).toBe("2 downloading · 55 queued · 1 failed");
        expect(countsLine(undefined)).toBe("");
    });

    it("shows a video's percentage while it downloads, else its status", () => {
        expect(videoStatus(row("v", "downloading", { progress: 41.7 }))).toBe(
            "41%",
        );
        expect(videoStatus(row("v", "pending"))).toBe("Queued");
    });
});

describe("groupToast", () => {
    const ended = (status: TaskStatus, counts: EntryCounts) =>
        row("g", status, {
            kind: "playlist",
            title: "29C3",
            entryCounts: counts,
        });

    it("says how a group ended, with its failures", () => {
        expect(
            groupToast(
                "downloading",
                ended("failed", {
                    ...COUNTS,
                    complete: 95,
                    active: 0,
                    downloading: 0,
                }),
            ),
        ).toEqual({
            tone: "error",
            message: "Finished: 29C3, 95 of 96, 1 failed",
        });
    });

    it("is a success when every video finished", () => {
        const done = {
            ...COUNTS,
            complete: 96,
            active: 0,
            downloading: 0,
            failed: 0,
        };
        expect(groupToast("downloading", ended("complete", done))).toEqual({
            tone: "success",
            message: "Finished: 29C3, 96 of 96",
        });
    });

    it("stays quiet on first sight, on pausing, and while it runs", () => {
        expect(groupToast(undefined, ended("complete", COUNTS))).toBeNull();
        expect(groupToast("downloading", ended("paused", COUNTS))).toBeNull();
        expect(groupToast("pending", ended("downloading", COUNTS))).toBeNull();
    });
});

describe("keepWatched", () => {
    it("keeps the last watched count a frame left out", () => {
        const held = row("g", "downloading", {
            kind: "playlist",
            entryCounts: { ...COUNTS, watched: 5 },
        });
        const frame = row("g", "downloading", {
            kind: "playlist",
            entryCounts: { ...COUNTS, complete: 39 },
        });
        const [
            kept,
        ] = keepWatched(
            [
                frame,
            ],
            [
                held,
            ],
        );
        expect(kept.entryCounts?.watched).toBe(5);
        expect(kept.entryCounts?.complete).toBe(39);
    });
});
