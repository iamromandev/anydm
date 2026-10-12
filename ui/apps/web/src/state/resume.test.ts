import { afterEach, describe, expect, test } from "bun:test";
import { EMPTY_ENTRIES } from "@/lib/api";
import { createLive } from "@/lib/live";
import type { QueueItem } from "@/lib/queue";
import type { App } from "./app";
import { createEntries } from "./entries";
import { createPlayer } from "./player";
import { createInitialState } from "./store";
import { createToasts } from "./toasts";

const realFetch = globalThis.fetch;

afterEach(() => {
    globalThis.fetch = realFetch;
});

/** One pending request, answered when the test says so. */
function pending() {
    let answer: (r: Response) => void = () => {};
    globalThis.fetch = (() =>
        new Promise<Response>((resolve) => {
            answer = resolve;
        })) as unknown as typeof globalThis.fetch;
    return (body: object) =>
        answer(
            new Response(
                JSON.stringify({ status: "success", code: 200, ...body }),
            ),
        );
}

const video = (id: string) => ({
    id,
    url: `https://example.test/${id}`,
    status: "completed",
    media_kind: "video",
    title: id,
    parent_id: "g",
});

function setup() {
    const store = createLive(createInitialState());
    const app = {} as App;
    app.toasts = createToasts(store);
    app.entries = createEntries(store, app);
    app.player = createPlayer(store, app);
    return { store, app };
}

describe("acting on the state at resume time", () => {
    test("an Entries page that lands after the list was shut is dropped", async () => {
        const { store, app } = setup();
        store.patch({ entries: { g: EMPTY_ENTRIES } });
        const answer = pending();

        const loading = app.entries.load("g");
        store.patch({ entries: {} });
        answer({
            data: [
                video("v1"),
            ],
            meta: { page: 1, total_pages: 1 },
        });
        await loading;

        expect(store.get().entries).toEqual({});
    });

    test("a queue page that lands after the queue was closed adds nothing", async () => {
        const { store, app } = setup();
        const held: QueueItem = {
            key: "a",
            title: "a",
            source: { taskId: "a" },
            playable: true,
        };
        store.patch({
            queue: [
                held,
            ],
            queueIndex: 0,
            queueGroup: { id: "g", page: 1, totalPages: 2 },
        });
        const answer = pending();

        const loading = app.player.loadQueuePage();
        app.player.close();
        answer({
            data: [
                video("v2"),
            ],
            meta: { page: 2, total_pages: 2 },
        });

        expect(await loading).toBe(false);
        expect(store.get().queue).toEqual([]);
        expect(store.get().queueGroup).toBeNull();
    });
});
