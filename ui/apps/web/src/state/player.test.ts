import { describe, expect, test } from "bun:test";
import type { PositionView } from "@/lib/api";
import { normalizeApiTask } from "@/lib/api";
import { createLive } from "@/lib/live";
import type { App } from "./app";
import { createPlayer, selectPlayerNav } from "./player";
import type { QueueItem } from "@/lib/queue";
import { createInitialState, type AppState } from "./store";

const item = (id: string): QueueItem => ({
    key: id,
    title: `title ${id}`,
    source: { taskId: id },
    playable: true,
});

const stateWith = (patch: Partial<AppState>): AppState => ({
    ...createInitialState(),
    ...patch,
});

describe("selectPlayerNav", () => {
    test("nothing before the first item", () => {
        const nav = selectPlayerNav(
            stateWith({
                queue: [
                    item("a"),
                    item("b"),
                ],
                queueIndex: 0,
            }),
        );
        expect(nav.hasPrevious).toBe(false);
        expect(nav.hasNext).toBe(true);
        expect(nav.nextTitle).toBe("title b");
    });

    test("at the end of what is loaded, a group with more pages still has a next", () => {
        const base = {
            queue: [
                item("a"),
            ],
            queueIndex: 0,
        };
        const more = selectPlayerNav(
            stateWith({
                ...base,
                queueGroup: { id: "g", page: 1, totalPages: 2 },
            }),
        );
        const exhausted = selectPlayerNav(
            stateWith({
                ...base,
                queueGroup: { id: "g", page: 2, totalPages: 2 },
            }),
        );
        expect(more.hasNext).toBe(true);
        expect(exhausted.hasNext).toBe(false);
        expect(exhausted.nextTitle).toBe("");
    });
});

describe("player", () => {
    function setup() {
        const store = createLive(createInitialState());
        const app = {} as App;
        const player = createPlayer(store, app);
        return { store, player };
    }

    test("close empties the queue and shuts the modal", () => {
        const { store, player } = setup();
        store.patch({
            queue: [
                item("a"),
            ],
            queueIndex: 0,
            queueGroup: { id: "g", page: 1, totalPages: 1 },
            playerModalOpen: true,
        });
        player.close();
        expect(store.get().queue).toEqual([]);
        expect(store.get().queueIndex).toBe(-1);
        expect(store.get().queueGroup).toBeNull();
        expect(store.get().playerModalOpen).toBe(false);
    });

    test("playClick opens the player on a source and clears any queue", () => {
        const { store, player } = setup();
        store.patch({
            queue: [
                item("a"),
            ],
            queueIndex: 0,
        });
        player.playClick("magnet:?xt=1", "magnet");
        const s = store.get();
        expect(s.playerUrl).toBe("magnet:?xt=1");
        expect(s.playerKind).toBe("magnet");
        expect(s.playerModalOpen).toBe(true);
        expect(s.queue).toEqual([]);
        expect(s.playerTaskId).toBe("");
    });

    test("positionSaved updates the row, and the open player when it is that task's", () => {
        const { store, player } = setup();
        const task = normalizeApiTask({
            id: "a",
            url: "u",
            status: "completed",
            media_kind: "video",
            title: "a",
        });
        store.patch({
            tasks: [
                task,
            ],
            playerTaskId: "a",
        });
        const position = {
            fileIndex: 0,
            positionSeconds: 12,
        } as unknown as PositionView;

        player.positionSaved("a", position);

        expect(store.get().playerPositions).toEqual([
            position,
        ]);
    });

    test("playQueueItem points the player at a task item", () => {
        const { store, player } = setup();
        store.patch({
            queue: [
                item("a"),
                item("b"),
            ],
        });
        player.playQueueItem(1);
        expect(store.get().queueIndex).toBe(1);
        expect(store.get().playerTaskId).toBe("b");
        expect(store.get().playerModalOpen).toBe(true);
    });
});
