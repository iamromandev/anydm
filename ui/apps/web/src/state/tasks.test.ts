import { afterEach, describe, expect, test } from "bun:test";
import { normalizeApiTask } from "@/lib/api";
import { createLive } from "@/lib/live";
import type { App } from "./app";
import { createInitialState } from "./store";
import { createTasks } from "./tasks";

const realFetch = globalThis.fetch;

afterEach(() => {
    globalThis.fetch = realFetch;
});

const raw = (id: string, status: string) => ({
    id,
    url: `https://example.test/${id}`,
    status,
    media_kind: "video",
    title: id,
});

const envelope = (data: unknown, meta?: Record<string, unknown>) =>
    new Response(JSON.stringify({ status: "success", code: 200, data, meta }), {
        status: 200,
    });

/** A fetch whose answers the test releases by hand, one per request. */
function deferredFetch() {
    const waiting: ((response: Response) => void)[] = [];
    const urls: string[] = [];
    globalThis.fetch = ((url: string) => {
        urls.push(String(url));
        return new Promise<Response>((resolve) => waiting.push(resolve));
    }) as unknown as typeof globalThis.fetch;
    return { urls, answer: (index: number, r: Response) => waiting[index]!(r) };
}

function setup() {
    const store = createLive(createInitialState());
    const app = {} as App;
    const tasks = createTasks(store, app);
    app.tasks = tasks;
    return { store, tasks };
}

describe("tasks.loadSummary", () => {
    test("five asks while one is in flight cost two requests in all", async () => {
        const { tasks } = setup();
        const net = deferredFetch();

        const first = tasks.loadSummary();
        const rest = [
            1,
            2,
            3,
            4,
        ].map(() => tasks.loadSummary());
        expect(net.urls.length).toBe(1);

        net.answer(0, envelope({ all: 3 }));
        await Promise.resolve();
        await new Promise((r) => setTimeout(r, 0));
        expect(net.urls.length).toBe(2);
        net.answer(1, envelope({ all: 4 }));
        await Promise.all([
            first,
            ...rest,
        ]);

        expect(net.urls.length).toBe(2);
    });
});

describe("tasks.loadPage", () => {
    test("a row the stream wrote while the page was in flight is not overwritten", async () => {
        const { store, tasks } = setup();
        store.patch({
            tasks: [
                normalizeApiTask(raw("a", "downloading")),
            ],
        });
        const net = deferredFetch();

        const loading = tasks.loadPage(1);
        // The stream reports `a` finished while the page request is out.
        tasks.merge([
            normalizeApiTask(raw("a", "completed")),
        ]);
        // The page lands carrying the older copy.
        net.answer(
            0,
            envelope(
                [
                    raw("a", "downloading"),
                ],
                { page: 1, total_pages: 1 },
            ),
        );
        await loading;

        expect(store.get().tasks.map((t) => t.status)).toEqual([
            "completed",
        ]);
    });

    test("a request that never landed leaves the list as it is", async () => {
        const { store, tasks } = setup();
        const held = [
            normalizeApiTask(raw("a", "downloading")),
        ];
        store.patch({ tasks: held });
        globalThis.fetch = (async () => {
            throw new Error("offline");
        }) as unknown as typeof globalThis.fetch;

        await tasks.loadPage(1);

        expect(store.get().tasks).toBe(held);
    });
});

describe("tasks.merge", () => {
    test("a status change raises one toast; a row new to the list raises none", async () => {
        const { store, tasks } = setup();
        store.patch({
            tasks: [
                normalizeApiTask(raw("a", "downloading")),
            ],
        });
        globalThis.fetch = (async () =>
            envelope({})) as unknown as typeof globalThis.fetch;

        tasks.merge([
            normalizeApiTask(raw("a", "completed")),
        ]);
        expect(store.get().toasts.map((t) => t.message)).toEqual([
            "Finished: a",
        ]);

        tasks.merge([
            normalizeApiTask(raw("b", "completed")),
        ]);
        expect(store.get().toasts.length).toBe(1);
    });

    test("every merge advances streamSeq and marks the rows it touched", () => {
        const { store, tasks } = setup();
        globalThis.fetch = (async () =>
            envelope({})) as unknown as typeof globalThis.fetch;
        tasks.merge([
            normalizeApiTask(raw("a", "downloading")),
        ]);
        tasks.merge([
            normalizeApiTask(raw("a", "downloading")),
        ]);
        expect(store.get().streamSeq).toBe(2);
        expect(store.get().streamTouched["a"]).toBe(2);
    });
});

describe("tasks filters", () => {
    test("changeFilter resets paging and loads page one of the new filter", async () => {
        const { store, tasks } = setup();
        store.patch({ page: 3, totalPages: 5 });
        const net = deferredFetch();

        const changing = tasks.changeFilter("seeding");
        expect(store.get().filter).toBe("seeding");
        expect(store.get().page).toBe(1);
        expect(net.urls[0]).toContain("group=seeding");
        net.answer(0, envelope([], { page: 1, total_pages: 1 }));
        await changing;
    });

    test("changeSearch stores the query", () => {
        const { store, tasks } = setup();
        tasks.changeSearch("abc");
        expect(store.get().searchQuery).toBe("abc");
    });
});
