import { afterEach, describe, expect, test } from "bun:test";
import type { PlaylistRequest } from "@/lib/api/playlist";
import { createLive } from "@/lib/live";
import { createAdd } from "./add";
import type { App } from "./app";
import { createInitialState } from "./store";
import { createTasks } from "./tasks";
import { createToasts } from "./toasts";

const realFetch = globalThis.fetch;

afterEach(() => {
    globalThis.fetch = realFetch;
});

function reply(status: number, body: object) {
    return new Response(JSON.stringify(body), { status });
}

function setup(handler: (path: string, init?: RequestInit) => Response) {
    globalThis.fetch = (async (url: string, init?: RequestInit) =>
        handler(
            String(url).replace(/^https?:\/\/[^/]+/, ""),
            init,
        )) as unknown as typeof globalThis.fetch;
    const store = createLive(createInitialState());
    const app = {} as App;
    app.toasts = createToasts(store);
    app.tasks = createTasks(store, app);
    app.add = createAdd(store, app);
    return { store, app };
}

const ok = (data: unknown, meta?: object) =>
    reply(200, { status: "success", code: 200, data, meta });

describe("add dialog", () => {
    test("click opens it empty", () => {
        const { store, app } = setup(() => ok({}));
        store.patch({ addInitial: { id: 1 } as never });
        app.add.click();
        expect(store.get().addInitial).toBeNull();
        expect(store.get().addModalOpen).toBe(true);
    });

    test("two adds of the same search result get different ids", () => {
        const { store, app } = setup(() => ok({}));
        const initial = { value: "magnet:?xt=1" } as never;
        app.add.found(initial);
        const first = store.get().addInitial?.id;
        app.add.found(initial);
        expect(store.get().addInitial?.id).not.toBe(first);
        expect(store.get().addModalOpen).toBe(true);
    });

    test("close shuts it", () => {
        const { store, app } = setup(() => ok({}));
        store.patch({ addModalOpen: true });
        app.add.close();
        expect(store.get().addModalOpen).toBe(false);
    });
});

describe("addPlaylist", () => {
    const request = {
        entries: [
            {},
            {},
        ],
    } as unknown as PlaylistRequest;

    test("says how many videos were added", async () => {
        const { store, app } = setup((path) =>
            path.startsWith("/collection") ? ok({}) : ok({}),
        );
        await app.add.addPlaylist(request);
        expect(store.get().toasts.map((t) => t.message)).toContain(
            "Added 2 videos",
        );
    });

    test("a refusal is said and rethrown, so the picker stays open", async () => {
        const { store, app } = setup(() =>
            reply(422, { status: "error", code: 422, message: "bad playlist" }),
        );
        await expect(app.add.addPlaylist(request)).rejects.toThrow();
        expect(store.get().toasts.some((t) => t.tone === "error")).toBe(true);
    });
});

describe("openDownload", () => {
    test("a row that is gone is reported, after the list was reset to everything", async () => {
        const { store, app } = setup((path) =>
            path.startsWith("/download?")
                ? ok([], { page: 1, total_pages: 1 })
                : path.startsWith("/download/zzz")
                  ? reply(404, { status: "error", code: 404, message: "gone" })
                  : ok({}),
        );
        store.patch({ filter: "seeding", category: "c1", searchQuery: "x" });

        await app.add.openDownload("zzz");

        expect(store.get().filter).toBe("all");
        expect(store.get().category).toBeNull();
        expect(store.get().searchQuery).toBe("");
        expect(store.get().toasts.map((t) => t.message)).toContain(
            "That download is no longer in your list",
        );
    });
});
