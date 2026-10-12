import { afterEach, describe, expect, test } from "bun:test";
import { normalizeApiTask } from "@/lib/api";
import { createLive } from "@/lib/live";
import { createActions } from "./actions";
import type { App } from "./app";
import { createEntries } from "./entries";
import { createInitialState } from "./store";
import { createTasks } from "./tasks";
import { createToasts } from "./toasts";

const realFetch = globalThis.fetch;

afterEach(() => {
    globalThis.fetch = realFetch;
});

type Call = { method: string; path: string };

/** Answers every request by path and records what was asked, in order. */
function serve(overrides: Record<string, () => Response> = {}) {
    const calls: Call[] = [];
    globalThis.fetch = (async (url: string, init?: RequestInit) => {
        const path = String(url).replace(/^https?:\/\/[^/]+/, "");
        calls.push({ method: init?.method ?? "GET", path });
        for (const [
            prefix,
            answer,
        ] of Object.entries(overrides)) {
            if (path.startsWith(prefix)) return answer();
        }
        const body = path.startsWith("/download?")
            ? { data: [], meta: { page: 1, total_pages: 1 } }
            : { data: {} };
        return new Response(
            JSON.stringify({ status: "success", code: 200, ...body }),
            {
                status: 200,
            },
        );
    }) as unknown as typeof globalThis.fetch;
    return calls;
}

const row = (id: string, status: string) =>
    normalizeApiTask({
        id,
        url: `https://example.test/${id}`,
        status,
        media_kind: "video",
        title: id,
    });

function setup() {
    const store = createLive(createInitialState());
    const app = {} as App;
    app.toasts = createToasts(store);
    app.tasks = createTasks(store, app);
    app.entries = createEntries(store, app);
    app.actions = createActions(store, app);
    return { store, app };
}

describe("actions.bulk", () => {
    test("clear_finished is held for confirmation and sent once confirmed", async () => {
        const { store, app } = setup();
        const calls = serve({
            "/download/bulk": () =>
                new Response(
                    JSON.stringify({
                        status: "success",
                        code: 200,
                        data: { affected: 2 },
                    }),
                ),
        });

        await app.actions.bulk("clear_finished");
        expect(store.get().pendingBulk).toBe("clear_finished");
        expect(calls.filter((c) => c.path === "/download/bulk")).toEqual([]);

        await app.actions.bulkConfirm();
        expect(store.get().pendingBulk).toBeNull();
        expect(calls.filter((c) => c.path === "/download/bulk").length).toBe(1);
        expect(store.get().toasts.map((t) => t.message)).toContain(
            "Cleared 2 downloads",
        );
    });

    test("bulkCancel drops the pending sweep without sending it", async () => {
        const { store, app } = setup();
        const calls = serve();
        await app.actions.bulk("clear_finished");
        app.actions.bulkCancel();
        expect(store.get().pendingBulk).toBeNull();
        expect(calls.length).toBe(0);
    });

    test("pausing runs at once, with no prompt", async () => {
        const { store, app } = setup();
        const calls = serve();
        await app.actions.bulk("pause_all");
        expect(store.get().pendingBulk).toBeNull();
        expect(calls.some((c) => c.path === "/download/bulk")).toBe(true);
    });
});

describe("actions.remove", () => {
    test("removeCancel shuts the dialog", () => {
        const { store, app } = setup();
        store.patch({
            removing: { id: "a", title: "a", status: "completed" },
        });
        app.actions.removeCancel();
        expect(store.get().removing).toBeNull();
    });

    test("with confirmation on, remove opens the dialog and deletes nothing", async () => {
        const { store, app } = setup();
        const calls = serve();
        store.patch({
            tasks: [
                row("a", "completed"),
            ],
        });
        await app.actions.remove("a");
        expect(store.get().removing?.id).toBe("a");
        expect(calls.length).toBe(0);
    });

    test("the row still goes when the API refuses, and the refusal is said", async () => {
        const { store, app } = setup();
        serve({
            "/download/a": () =>
                new Response(
                    JSON.stringify({
                        status: "error",
                        code: 404,
                        message: "no such download",
                    }),
                    { status: 404 },
                ),
        });
        store.patch({
            tasks: [
                row("a", "completed"),
                row("b", "completed"),
            ],
        });

        await app.actions.removeConfirm("a", false);

        expect(store.get().tasks.map((t) => t.id)).toEqual([
            "b",
        ]);
        expect(store.get().toasts.some((t) => t.tone === "error")).toBe(true);
    });
});

describe("entries", () => {
    test("toggle opens a group's list and toggling again shuts it", async () => {
        const { store, app } = setup();
        const calls = serve({
            "/collection/g/downloads": () =>
                new Response(
                    JSON.stringify({
                        status: "success",
                        code: 200,
                        data: [],
                        meta: { page: 1, total_pages: 1 },
                    }),
                ),
        });

        await app.entries.toggle("g");
        expect(store.get().entries["g"]).toBeDefined();
        expect(
            calls.some((c) => c.path.startsWith("/collection/g/downloads")),
        ).toBe(true);

        await app.entries.toggle("g");
        expect(store.get().entries["g"]).toBeUndefined();
    });
});
