import { afterEach, describe, expect, test } from "bun:test";
import { getApi } from "@/lib/api/client";
import { createLive } from "@/lib/live";
import type { App } from "./app";
import { createSettings } from "./settings";
import { createInitialState } from "./store";

const realFetch = globalThis.fetch;

afterEach(() => {
    globalThis.fetch = realFetch;
});

function refuse(): void {
    globalThis.fetch = (async () =>
        new Response(
            JSON.stringify({
                status: "error",
                code: 401,
                message: "A valid API key is required",
            }),
            { status: 401 },
        )) as unknown as typeof globalThis.fetch;
}

describe("settings", () => {
    test("the first 401 opens settings with the key message, later ones do not reopen it", async () => {
        const store = createLive(createInitialState());
        const settings = createSettings(store, {} as App);
        refuse();
        const stop = settings.listenUnauthorized();

        await expect(getApi("/download")).rejects.toThrow();
        expect(store.get().settingsOpen).toBe(true);
        expect(store.get().apiKeyMessage).toContain("API_KEY");

        store.patch({ settingsOpen: false });
        await expect(getApi("/download")).rejects.toThrow();
        expect(store.get().settingsOpen).toBe(false);
        stop();
    });

    test("the message differs when a key was already saved", async () => {
        const store = createLive(createInitialState());
        store.patch({ apiKey: "secret" });
        const settings = createSettings(store, {} as App);
        refuse();
        const stop = settings.listenUnauthorized();

        await expect(getApi("/download")).rejects.toThrow();
        expect(store.get().apiKeyMessage).toContain("refused");
        stop();
    });

    test("changePrefs keeps the new prefs in state", () => {
        const store = createLive(createInitialState());
        const settings = createSettings(store, {} as App);
        const prefs = { ...store.get().prefs };
        settings.changePrefs(prefs);
        expect(store.get().prefs).toBe(prefs);
    });

    test("toggles flip the sidebar flags", () => {
        const store = createLive(createInitialState());
        const settings = createSettings(store, {} as App);
        const open = store.get().sidebarOpen;
        settings.toggleSidebar();
        expect(store.get().sidebarOpen).toBe(!open);
        const collapsed = store.get().sidebarCollapsed;
        settings.toggleSidebarCollapse();
        expect(store.get().sidebarCollapsed).toBe(!collapsed);
    });
});
