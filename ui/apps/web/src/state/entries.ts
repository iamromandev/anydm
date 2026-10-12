import {
    EMPTY_ENTRIES,
    addEntriesPage,
    getPageApi,
    normalizeApiTask,
    postApi,
} from "@/lib/api";
import { removePrompt } from "@/component/features/remove-dialog/prompt";
import { errorMessage } from "@/lib/toast";
import type { App } from "./app";
import type { Store } from "./store";

/** Videos per Entries page. The API caps a page at 100. */
const ENTRIES_PAGE = 50;

/** A group's Entries: the open lists of videos, by group id. */
export function createEntries(store: Store, app: App) {
    /** Open lists without `groupId`'s. */
    const without = (groupId: string) => {
        const { [groupId]: _shut, ...open } = store.get().entries;
        return open;
    };

    async function load(groupId: string): Promise<void> {
        const view = store.get().entries[groupId];
        if (!view) return;
        store.patch((s) => ({
            entries: { ...s.entries, [groupId]: { ...view, loading: true } },
        }));
        const result = await getPageApi<any[]>(
            `/collection/${groupId}/downloads?page=${view.page + 1}&page_size=${ENTRIES_PAGE}`,
        ).catch(() => null);
        // Read again: the stream may have written while the page was out.
        const current = store.get().entries[groupId];
        // Shut meanwhile: nothing to add it to.
        if (!current) return;
        store.patch((s) => ({
            entries: {
                ...s.entries,
                [groupId]:
                    result === null
                        ? { ...current, loading: false }
                        : addEntriesPage(
                              current,
                              result.data.map(normalizeApiTask),
                              result.meta.page,
                              result.meta.totalPages,
                          ),
            },
        }));
        if (result === null) {
            app.toasts.notify("error", "Couldn't load this group's videos");
        }
    }

    return {
        load,

        /** An open Entries list, from its first page again. */
        async reload(groupId: string): Promise<void> {
            if (!store.get().entries[groupId]) return;
            store.patch((s) => ({
                entries: { ...s.entries, [groupId]: EMPTY_ENTRIES },
            }));
            await load(groupId);
        },

        /** Open a group's Entries, fetching its first page; or shut them. */
        async toggle(groupId: string): Promise<void> {
            if (store.get().entries[groupId]) {
                store.patch({ entries: without(groupId) });
                return;
            }
            store.patch((s) => ({
                entries: { ...s.entries, [groupId]: EMPTY_ENTRIES },
            }));
            await load(groupId);
        },

        /** A video's own pause or resume. Its new row arrives by its frame. */
        async videoAction(
            videoId: string,
            action: "pause" | "resume",
        ): Promise<void> {
            try {
                await postApi(`/download/${videoId}/${action}`, {});
            } catch (err) {
                app.toasts.notify("error", errorMessage(err));
            }
        },

        async removeVideo(groupId: string, videoId: string): Promise<void> {
            const state = store.get();
            const video = state.entries[groupId]?.rows.find(
                (row) => row.id === videoId,
            );
            if (!video) return;
            if (!state.prefs.confirmBeforeRemove) {
                await app.actions.removeConfirm(
                    video.id,
                    !removePrompt(video.status).canKeepFiles,
                );
                return;
            }
            store.patch({
                removing: {
                    id: video.id,
                    title: video.title,
                    status: video.status,
                },
            });
        },
    };
}
