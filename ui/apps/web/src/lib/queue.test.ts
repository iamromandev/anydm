import { describe, expect, it } from "bun:test";

import { toEntry } from "./api/playlist";
import type { UiTask } from "./api/task";
import {
    adjacentItem,
    queueFromEntries,
    queueFromVideos,
    resumeItem,
    startItem,
    type QueueItem,
} from "./queue";

const item = (key: string, extra: Partial<QueueItem> = {}): QueueItem => ({
    key,
    title: key,
    source: { url: `https://y.test/${key}` },
    playable: true,
    ...extra,
});

const watched = (positionSeconds = 0) => [
    { fileIndex: 0, positionSeconds, durationSeconds: 60, watched: true },
];
const partway = [
    { fileIndex: 0, positionSeconds: 20, durationSeconds: 60, watched: false },
];

describe("adjacentItem", () => {
    const items = [
        item("a"),
        item("b", { playable: false }),
        item("c"),
    ];

    it("steps over what can't play, and stops at either end", () => {
        expect(adjacentItem(items, 0, 1)).toBe(2);
        expect(adjacentItem(items, 2, -1)).toBe(0);
        expect(adjacentItem(items, 2, 1)).toBeNull();
        expect(adjacentItem(items, 0, -1)).toBeNull();
    });
});

describe("where to start", () => {
    it("picks the item left partway", () => {
        const items = [
            item("a", { positions: watched() }),
            item("b"),
            item("c", { positions: partway }),
        ];
        expect(resumeItem(items)).toBe(2);
    });

    it("else the first unwatched, skipping what can't play", () => {
        const items = [
            item("a", { positions: watched() }),
            item("b", { playable: false }),
            item("c"),
        ];
        expect(resumeItem(items)).toBe(2);
    });

    it("else, everything watched, the first that plays", () => {
        const items = [
            item("a", { playable: false }),
            item("b", { positions: watched() }),
        ];
        expect(resumeItem(items)).toBeNull();
        expect(startItem(items)).toBe(1);
        expect(
            startItem([
                item("x", { playable: false }),
            ]),
        ).toBeNull();
    });
});

describe("building a queue", () => {
    it("streams the ticked entries in order", () => {
        const raw = {
            id: "v",
            url: "https://y.test/v",
            title: "T",
            duration: 1,
            thumbnail: null,
            timestamp: null,
            available: true,
            have: null,
        };
        const entries = [
            toEntry({ ...raw, index: 1, id: "a", url: "https://y.test/a" }),
            toEntry({ ...raw, index: 2, id: "b", url: "https://y.test/b" }),
            toEntry({
                ...raw,
                index: 3,
                id: "c",
                url: "https://y.test/c",
                available: false,
            }),
        ];
        const queue = queueFromEntries(
            entries,
            new Set([
                3,
                1,
            ]),
        );
        expect(
            queue.map((q) => [
                q.key,
                q.playable,
                q.source,
            ]),
        ).toEqual([
            [
                "https://y.test/a",
                true,
                { url: "https://y.test/a" },
            ],
            [
                "https://y.test/c",
                false,
                { url: "https://y.test/c" },
            ],
        ]);
    });

    it("plays a group's finished videos from file, the rest from their page", () => {
        const video = (id: string, status: UiTask["status"]): UiTask => ({
            id,
            title: id,
            url: `https://y.test/${id}`,
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
            positions: status === "complete" ? watched() : undefined,
        });
        const queue = queueFromVideos([
            video("done", "complete"),
            video("next", "pending"),
            video("bad", "failed"),
        ]);
        expect(
            queue.map((q) => [
                q.key,
                q.playable,
                q.source,
            ]),
        ).toEqual([
            [
                "done",
                true,
                { taskId: "done" },
            ],
            [
                "next",
                true,
                { url: "https://y.test/next" },
            ],
            [
                "bad",
                false,
                { url: "https://y.test/bad" },
            ],
        ]);
        expect(queue[0].positions).toEqual(watched());
    });
});
