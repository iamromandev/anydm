import {
    EMPTY_ENTRIES,
    addLink,
    addTorrent,
    directRequest,
    duplicateOf,
    entryStanding,
    getApi,
    heldTorrentNotice,
    normalizeApiTask,
    postApi,
    resolveTorrent,
    withRowOnTop,
    type AddInitial,
    type AddInput,
    type ResolvedTorrent,
} from "@/lib/api";
import type { PlaylistRequest } from "@/lib/api/playlist";
import { errorMessage } from "@/lib/toast";
import type { App } from "./app";
import type { Store } from "./store";

/** Adding downloads: the dialog, its forms, and bringing a row into view. */
export function createAdd(store: Store, app: App) {
    return {
        click(): void {
            store.patch({ addInitial: null, addModalOpen: true });
        },

        close(): void {
            store.patch({ addModalOpen: false });
        },

        /** Add on a search result: the dialog opens on it, already resolving. */
        found(initial: AddInitial): void {
            const id = store.get().addInitialSeq + 1;
            store.patch({
                addInitialSeq: id,
                addInitial: { ...initial, id },
                addModalOpen: true,
            });
        },

        resolve(torrent: string): Promise<ResolvedTorrent> {
            return resolveTorrent(torrent);
        },

        async add(input: AddInput): Promise<void> {
            const state = store.get();
            // Where the form said to save; Downloads when it said nothing.
            const category = input.categoryId ?? state.addCategory;
            try {
                // unwrap() throws with the service's own message on either
                // envelope, so there is no response.ok check to write here.
                if (input.type === "site" || input.type === "url") {
                    // For a page, the add box already asked the API about it
                    // and picked a preset it offers.
                    const request = directRequest({
                        ...input,
                        categoryId: category,
                    });
                    await postApi(request.path, request.body);
                } else if (input.type === "link") {
                    // A link nobody has looked at yet: ask, then route it.
                    await addLink(
                        input.value,
                        input.preset || state.prefs.defaultPreset,
                        postApi,
                        input.allowDuplicate,
                        category,
                    );
                } else {
                    const known = new Set(state.tasks.map((task) => task.id));
                    const notice = heldTorrentNotice(
                        await addTorrent(
                            input.value,
                            input.files ?? [],
                            category,
                        ),
                        known,
                    );
                    if (notice) app.toasts.notify("info", notice);
                }
            } catch (err) {
                // A duplicate is answered where it was asked, with Open and
                // Add anyway, so it raises no toast of its own.
                if (!duplicateOf(err)) {
                    app.toasts.notify("error", errorMessage(err));
                }
                // Rethrown so the modal keeps what was typed instead of
                // closing over a submission that never landed.
                throw err;
            }

            store.patch({ addModalOpen: false });
            // The new row arrives by its own frame, on top, without taking the
            // list back to page 1. Only the counts need asking again.
            void app.tasks.loadSummary();
        },

        /**
         * Bring a download the list already holds into view and outline it.
         *
         * The list is the database's answer for a filter and a page, so the
         * row may be on none of what is loaded: reset to every download, and
         * fetch the one row if page 1 still lacks it.
         */
        async openDownload(id: string, collectionId?: string): Promise<void> {
            // The row may sit in another category, so the category filter goes
            // too.
            store.patch({
                filter: "all",
                category: null,
                searchQuery: "",
                page: 1,
                totalPages: 1,
            });
            await app.tasks.loadPage(1);
            // A video a playlist holds is not a row of the list: its group is,
            // and the video is in the group's Entries.
            const rowId = collectionId ?? id;
            if (!store.get().tasks.some((task) => task.id === rowId)) {
                const row = await getApi<any>(
                    collectionId
                        ? `/collection/${rowId}`
                        : `/download/${rowId}`,
                ).catch(() => null);
                if (row === null) {
                    app.toasts.notify(
                        "error",
                        "That download is no longer in your list",
                    );
                    return;
                }
                store.patch((s) => ({
                    tasks: withRowOnTop(s.tasks, normalizeApiTask(row)),
                }));
            }
            if (collectionId) {
                if (!store.get().entries[collectionId]) {
                    store.patch((s) => ({
                        entries: {
                            ...s.entries,
                            [collectionId]: EMPTY_ENTRIES,
                        },
                    }));
                }
                // Entries come a page at a time: fetch on until the video is
                // in. A page that fails to advance ends the wait.
                const view = () => store.get().entries[collectionId];
                let standing = entryStanding(view(), id);
                while (standing === "more") {
                    const fetched = view()?.page ?? 0;
                    await app.entries.load(collectionId);
                    if ((view()?.page ?? 0) <= fetched) break;
                    standing = entryStanding(view(), id);
                }
                if (standing !== "found") {
                    app.toasts.notify(
                        "error",
                        "That video is no longer in the playlist",
                    );
                    return;
                }
            }
            store.patch({ highlightId: id });
            // The row may not have rendered yet (a group's videos come in a
            // hundred at a time), so look again for a second before giving up.
            let tries = 0;
            const scrollToRow = () => {
                const row = document.getElementById(`download-${id}`);
                if (row) {
                    // Instant inside a group: two nested smooth scrolls (the
                    // Entries list, then the page) cancel each other.
                    const show = () =>
                        row.scrollIntoView({
                            block: "center",
                            behavior: collectionId ? "auto" : "smooth",
                        });
                    show();
                    // Again once the page has settled around a long Entries
                    // list.
                    if (collectionId) setTimeout(show, 250);
                } else if (tries++ < 20) {
                    setTimeout(scrollToRow, 50);
                }
            };
            setTimeout(scrollToRow, 50);
            setTimeout(() => {
                if (store.get().highlightId === id) {
                    store.patch({ highlightId: "" });
                }
            }, 2600);
        },

        /** A playlist's ticked videos, as one group. Its row arrives by its frame. */
        async addPlaylist(request: PlaylistRequest): Promise<void> {
            try {
                await postApi("/collection", request);
            } catch (err) {
                app.toasts.notify("error", errorMessage(err));
                // Rethrown so the picker stays open on what was ticked.
                throw err;
            }
            const n = request.entries.length;
            app.toasts.notify(
                "info",
                `Added ${n.toLocaleString("en-US")} ${n === 1 ? "video" : "videos"}`,
            );
            void app.tasks.loadSummary();
        },
    };
}
