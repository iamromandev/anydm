import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { connectionOnError } from "@/lib/connection";
import { createLive } from "@/lib/live";
import { createApp } from "./app";
import { createInitialState } from "./store";

const realFetch = globalThis.fetch;
const realSetInterval = globalThis.setInterval;
const realClearInterval = globalThis.clearInterval;

class FakeEventSource {
    static instances: FakeEventSource[] = [];
    readyState = 0;
    closed = false;
    onopen: (() => void) | null = null;
    onerror: (() => void) | null = null;
    private listeners = new Map<string, ((e: { data: string }) => void)[]>();

    constructor(public url: string) {
        FakeEventSource.instances.push(this);
    }
    addEventListener(name: string, fn: (e: { data: string }) => void) {
        this.listeners.set(name, [
            ...(this.listeners.get(name) ?? []),
            fn,
        ]);
    }
    emit(name: string, data: string) {
        for (const fn of this.listeners.get(name) ?? []) fn({ data });
    }
    close() {
        this.closed = true;
        this.readyState = 2;
    }
}

const activeIntervals = new Set<unknown>();

beforeEach(() => {
    FakeEventSource.instances = [];
    activeIntervals.clear();
    globalThis.setInterval = ((fn: () => void, ms?: number) => {
        const id = realSetInterval(fn, ms);
        activeIntervals.add(id);
        return id;
    }) as unknown as typeof setInterval;
    globalThis.clearInterval = ((id: never) => {
        activeIntervals.delete(id);
        realClearInterval(id);
    }) as unknown as typeof clearInterval;
    globalThis.fetch = (async (url: string) => {
        const path = String(url);
        const body = path.includes("/download?")
            ? { data: [], meta: { page: 1, total_pages: 1 } }
            : { data: [] };
        return new Response(
            JSON.stringify({ status: "success", code: 200, ...body }),
        );
    }) as unknown as typeof globalThis.fetch;
});

afterEach(() => {
    globalThis.fetch = realFetch;
    globalThis.setInterval = realSetInterval;
    globalThis.clearInterval = realClearInterval;
});

function setup() {
    const store = createLive(createInitialState());
    const app = createApp(store, {
        EventSource: FakeEventSource as unknown as typeof EventSource,
    });
    return { store, app };
}

const rawTask = (id: string, status: string) =>
    JSON.stringify({
        id,
        url: `https://example.test/${id}`,
        status,
        media_kind: "video",
        title: id,
    });

describe("stream.start", () => {
    test("start, stop, start (StrictMode) leaves exactly one open stream", () => {
        const { app } = setup();

        const stopFirst = app.stream.start();
        stopFirst();
        const stopSecond = app.stream.start();

        const open = FakeEventSource.instances.filter((es) => !es.closed);
        expect(open.length).toBe(1);

        stopSecond();
        expect(FakeEventSource.instances.every((es) => es.closed)).toBe(true);
    });

    test("stop clears every timer it started", () => {
        const { app } = setup();
        const stop = app.stream.start();
        expect(activeIntervals.size).toBeGreaterThan(0);
        stop();
        expect(activeIntervals.size).toBe(0);
    });

    test("a malformed frame changes nothing, and later frames still arrive", () => {
        const { store, app } = setup();
        app.stream.start();
        const es = FakeEventSource.instances[0]!;

        es.emit("downloads", "{not json");
        es.emit("download", "also not json");
        expect(store.get().tasks).toEqual([]);

        es.emit("download", rawTask("a", "downloading"));
        expect(store.get().tasks.map((t) => t.id)).toEqual([
            "a",
        ]);
    });

    test("an error records the connection and when the gap began, once", async () => {
        const { store, app } = setup();
        app.stream.start();
        const es = FakeEventSource.instances[0]!;
        es.readyState = 0;

        es.onerror?.();
        const began = store.get().degradedSince;
        expect(store.get().connection).toBe(connectionOnError(0));
        expect(began).not.toBeNull();

        await new Promise((r) => setTimeout(r, 5));
        es.onerror?.();
        expect(store.get().degradedSince).toBe(began);
    });

    test("reconnecting takes the outage toast down and says so", () => {
        const { store, app } = setup();
        app.stream.start();
        const es = FakeEventSource.instances[0]!;
        app.toasts.notify("error", "Lost contact with the API.");
        const outage = store.get().toasts[0]!;
        store.patch({
            outageToastId: outage.id,
            degradedSince: Date.now() - 20_000,
            connection: "reconnecting",
        });

        es.onopen?.();

        expect(store.get().connection).toBe("live");
        expect(store.get().degradedSince).toBeNull();
        expect(store.get().outageToastId).toBeNull();
        const messages = store.get().toasts.map((t) => t.message);
        expect(messages).not.toContain("Lost contact with the API.");
        expect(messages).toContain("Back in contact");
    });

    test("when no stream can be opened the app is offline, not frozen", () => {
        const store = createLive(createInitialState());
        const app = createApp(store, {
            EventSource: class {
                constructor() {
                    throw new Error("blocked");
                }
            } as unknown as typeof EventSource,
        });
        app.stream.start();
        expect(store.get().connection).toBe("offline");
        expect(store.get().degradedSince).not.toBeNull();
    });
});
