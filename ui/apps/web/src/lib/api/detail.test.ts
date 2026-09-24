import { describe, expect, it } from "bun:test";

import { detailRows, relativeTime } from "./detail";
import type { UiTask } from "./task";

const NOW = Date.parse("2026-09-20T12:00:00Z");

const task = (overrides: Partial<UiTask> = {}): UiTask => ({
    id: "t",
    title: "clip",
    url: "https://example.com/clip.mp4",
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
    ...overrides,
});

const labels = (rows: { label: string }[]) => rows.map((r) => r.label);

describe("relativeTime", () => {
    it("counts in the largest unit that is not a fraction", () => {
        expect(relativeTime(NOW - 30_000, NOW)).toBe("30s ago");
        expect(relativeTime(NOW - 5 * 60_000, NOW)).toBe("5m ago");
        expect(relativeTime(NOW - 3 * 3_600_000, NOW)).toBe("3h ago");
        expect(relativeTime(NOW - 2 * 86_400_000, NOW)).toBe("2d ago");
    });

    it("says just now rather than 0s ago", () => {
        expect(relativeTime(NOW, NOW)).toBe("just now");
    });
});

describe("detailRows", () => {
    it("always leads with the source, and offers it for copying whole", () => {
        const rows = detailRows(task(), NOW);

        expect(rows[0].label).toBe("Source");
        expect(rows[0].copy).toBe("https://example.com/clip.mp4");
    });

    it("describes what kind of thing this is", () => {
        const rows = detailRows(
            task({ platform: "direct", kind: "file", preset: "best" }),
            NOW,
        );
        const type = rows.find((r) => r.label === "Type");

        expect(type?.value).toBe("direct · file · best");
    });

    it("names a site task's site rather than the bare platform", () => {
        const rows = detailRows(
            task({
                platform: "site",
                extractor: "Youtube",
                kind: "video",
                preset: "1080",
            }),
            NOW,
        );
        const type = rows.find((r) => r.label === "Type");

        expect(type?.value).toBe("YouTube · video · 1080");
    });

    it("leaves out a time nothing has recorded", () => {
        const rows = detailRows(task({ createdAt: NOW - 60_000 }), NOW);

        expect(labels(rows)).toContain("Added");
        expect(labels(rows)).not.toContain("Started");
        expect(labels(rows)).not.toContain("Finished");
    });

    it("shows a time both ways: relative to read, absolute to trust", () => {
        const rows = detailRows(task({ createdAt: NOW - 60_000 }), NOW);
        const added = rows.find((r) => r.label === "Added");

        expect(added?.value).toBe("1m ago");
        expect(added?.title).toContain("2026");
    });

    it("names the file and its size once there is one", () => {
        const rows = detailRows(
            task({ filename: "clip.mp4", fileSize: 1024 * 1024 }),
            NOW,
        );
        const file = rows.find((r) => r.label === "File");

        expect(file?.value).toBe("clip.mp4 · 1.0 MB");
    });

    it("offers a torrent's info hash for copying", () => {
        const rows = detailRows(task({ infoHash: "abc123" }), NOW);
        const hash = rows.find((r) => r.label === "Info hash");

        expect(hash?.copy).toBe("abc123");
    });

    it("stays quiet about attempts until there has been more than one", () => {
        expect(labels(detailRows(task({ attempts: 1 }), NOW))).not.toContain(
            "Attempts",
        );
        expect(
            labels(detailRows(task({ attempts: 2, maxAttempts: 3 }), NOW)),
        ).toContain("Attempts");
    });

    it("reports a failure with its code", () => {
        const rows = detailRows(
            task({
                status: "failed",
                error: "connection reset",
                errorCode: "network",
            }),
            NOW,
        );
        const error = rows.find((r) => r.label === "Error");

        expect(error?.value).toBe("network: connection reset");
    });

    it("reports an error that arrived without a code", () => {
        const rows = detailRows(task({ error: "something broke" }), NOW);

        expect(rows.find((r) => r.label === "Error")?.value).toBe(
            "something broke",
        );
    });
});
