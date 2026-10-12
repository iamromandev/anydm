import { getApi, onUnauthorized } from "@/lib/api/client";
import { loadApiKey, saveApiKey } from "@/lib/api/key";
import { searchSources } from "@/lib/api";
import type { ServerSettings } from "@/lib/api/server-settings";
import { loadPrefs, savePrefs, type Prefs } from "@/lib/prefs";
import { loadSort, saveSort, type SortValue } from "@/lib/sort";
import type { App } from "./app";
import type { Store } from "./store";

export function createSettings(store: Store, app: App) {
    return {
        /**
         * What this browser remembers, applied before the first fetch so the
         * list does not arrive newest-first and then reshuffle.
         */
        loadStored(): void {
            store.patch({
                sort: loadSort(),
                prefs: loadPrefs(),
                apiKey: loadApiKey(),
            });
        },

        /** Registered before the first request, so its answer is heard. */
        listenUnauthorized(): () => void {
            return onUnauthorized(() => {
                const { apiKeyMessage, apiKey } = store.get();
                if (apiKeyMessage !== null) return;
                store.patch({
                    apiKeyMessage: apiKey
                        ? "The API refused the saved key. Check it matches API_KEY in api/.env."
                        : "The API requires a key. Enter the one set as API_KEY in api/.env.",
                    settingsOpen: true,
                });
            });
        },

        saveApiKey(key: string): void {
            saveApiKey(key);
            // A reload rather than patching things up in place: the event
            // stream, the list and any open player were all opened with the
            // old key, and starting again is the one way to be sure none of
            // them kept it.
            location.reload();
        },

        changePrefs(prefs: Prefs): void {
            store.patch({ prefs });
            savePrefs(prefs);
        },

        async changeSort(sort: SortValue): Promise<void> {
            store.patch({ sort, page: 1, totalPages: 1 });
            saveSort(sort);
            // A different order is a different list, so it starts at the top.
            await app.tasks.loadPage(1);
        },

        async open(): Promise<void> {
            store.patch({ settingsOpen: true });
            // Fetched on opening rather than on load: nothing else needs it,
            // and it cannot change without the API restarting.
            const server = await getApi<ServerSettings>("/settings").catch(
                () => null,
            );
            store.patch({ serverSettings: server });
        },

        async close(): Promise<void> {
            store.patch({ settingsOpen: false });
            // Another client's edits to the categories show up once Settings
            // closes.
            await app.categories.load();
        },

        async refreshSearchAvailability(): Promise<void> {
            try {
                const sources = await searchSources();
                store.patch({
                    searchTorrents: sources.enabled,
                    searchYoutube: sources.youtube,
                    searchEnabled: sources.enabled || sources.youtube,
                });
            } catch {
                store.patch({
                    searchTorrents: false,
                    searchYoutube: false,
                    searchEnabled: false,
                });
            }
        },

        toggleSidebar(): void {
            store.patch((s) => ({ sidebarOpen: !s.sidebarOpen }));
        },

        toggleSidebarCollapse(): void {
            store.patch((s) => ({ sidebarCollapsed: !s.sidebarCollapsed }));
        },
    };
}
