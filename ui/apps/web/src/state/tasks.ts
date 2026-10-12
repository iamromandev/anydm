import {
    appendPage,
    applyProgressFrame,
    applyVideoProgress,
    applyVideoRows,
    countsMoved,
    getApi,
    getPageApi,
    groupToast,
    keepPositions,
    keepSegments,
    keepWatched,
    moveToCategory as sendMove,
    normalizeApiTask,
    normalizeSummary,
    placeRows,
    settlePage,
    trackVideoSpeed,
    withGroupSpeeds,
    type UiTask,
} from "@/lib/api";
import { createToast, errorMessage, raise, transitionToast } from "@/lib/toast";
import type { App } from "./app";
import type { AppState, Store } from "./store";

/** Rows per request. The API caps this at 100. */
const PAGE_SIZE = 25;

/**
 * The task list: paging, the counts, and folding stream frames into both.
 *
 * Every read of the list and the write that follows it happen without an
 * `await` in between, so a frame or a page that lands meanwhile sees all of it
 * or none of it.
 */
export function createTasks(store: Store, app: App) {
    /**
     * Ask for the counts again. A burst of frames (a playlist paused, a bulk
     * action) costs at most two requests: while one is in flight, later asks
     * only mark that it should run once more when it lands.
     */
    async function loadSummary(): Promise<void> {
        if (store.get().summaryLoading) {
            store.patch({ summaryAgain: true });
            return;
        }
        store.patch({ summaryLoading: true });
        try {
            do {
                store.patch({ summaryAgain: false });
                // The counts follow the category filter, unlike the status
                // filter's.
                const { category } = store.get();
                const narrowed = category
                    ? `?category=${encodeURIComponent(category)}`
                    : "";
                const summary = await getApi<any>(
                    `/download/summary${narrowed}`,
                ).catch(() => null);
                if (summary)
                    store.patch({ summary: normalizeSummary(summary) });
            } while (store.get().summaryAgain);
        } finally {
            store.patch({ summaryLoading: false });
        }
    }

    /**
     * Announce any row whose status moved since `before`, the list as it was
     * just before the write that brought `rows` in. A row that is new to the
     * list says nothing, which is what keeps a reload quiet.
     */
    function noteTransitions(rows: UiTask[], before: UiTask[]): void {
        const previous = new Map(
            before.map((t) => [
                t.id,
                t.status,
            ]),
        );
        let toasts = store.get().toasts;
        let moved = false;

        for (const row of rows) {
            if (countsMoved(previous.get(row.id), row.status)) moved = true;
            const announcement =
                row.kind === "playlist"
                    ? groupToast(previous.get(row.id), row)
                    : transitionToast(previous.get(row.id), row);
            if (announcement) {
                toasts = raise(
                    toasts,
                    createToast(
                        announcement.tone,
                        announcement.message,
                        Date.now(),
                    ),
                );
            }
        }
        store.patch({ toasts });

        // A row moved between the sidebar's counts, so they are now wrong.
        // Only a real move triggers this: the torrent monitor publishes a task
        // frame every tick whether or not anything changed, and refetching on
        // each of those would be a poll by another name.
        if (moved) void loadSummary();
    }

    /**
     * Fetch one page of the current filter. Page 1 replaces the list; later
     * pages are added to it. A request that never landed leaves everything as
     * it is: the list is the only record of what was running, and emptying it
     * during an outage throws away what the connection indicator says is
     * merely stale.
     */
    async function loadPage(page: number): Promise<void> {
        const start = store.get();
        const narrowed = start.category
            ? `&category=${encodeURIComponent(start.category)}`
            : "";
        const query =
            `page=${page}&page_size=${PAGE_SIZE}` +
            `&group=${start.filter}&sort=${start.sort}${narrowed}`;
        const since = start.streamSeq;
        const result = await getPageApi<any[]>(`/download?${query}`).catch(
            () => null,
        );
        if (result === null) return;

        const now = store.get();
        const before = now.tasks;
        // Rows the stream wrote while this was in flight are newer than the
        // page's copies of them.
        const touched = new Set(
            Object.entries(now.streamTouched)
                .filter((entry) => entry[1] > since)
                .map((entry) => entry[0]),
        );
        const rows = keepSegments(
            settlePage(result.data.map(normalizeApiTask), before, touched),
            before,
        );
        store.patch({
            tasks: page === 1 ? rows : appendPage(before, rows),
            page: result.meta.page,
            totalPages: result.meta.totalPages,
        });

        noteTransitions(rows, before);
    }

    /** Back to the top of the current filter. */
    async function sync(): Promise<void> {
        await loadPage(1);
        await loadSummary();
    }

    /** A new filter, order or category is a new list, not a view of this one. */
    async function restart(): Promise<void> {
        store.patch({ page: 1, totalPages: 1 });
        await loadPage(1);
    }

    /** Fold rows from an SSE frame into the list, replacing what they match. */
    function merge(rows: UiTask[]): void {
        const state = store.get();
        const before = state.tasks;
        const top = rows.filter((row) => row.parentId === undefined);
        const videos = rows.filter((row) => row.parentId !== undefined);

        // What lets a page fetch in flight know these rows are newer than its
        // copies of them. Videos never reach a page of the list.
        const streamSeq = state.streamSeq + 1;
        const streamTouched = { ...state.streamTouched };
        for (const row of top) streamTouched[row.id] = streamSeq;

        // A group's videos update its open Entries list, and one that has
        // stopped stops counting towards its speed.
        let speeds = state.videoSpeeds;
        let entries = state.entries;
        if (videos.length > 0) {
            for (const video of videos) {
                if (video.status !== "downloading") {
                    speeds = trackVideoSpeed(
                        speeds,
                        video.parentId!,
                        video.id,
                        0,
                    );
                }
            }
            entries = applyVideoRows(entries, videos);
        }

        // In place, not moved to the top: a group recounts on every video's
        // status change, and would otherwise jump up the list each time. A
        // cancelled row leaves. Stream frames carry neither segments nor
        // positions.
        const tasks = withGroupSpeeds(
            placeRows(
                before,
                keepWatched(
                    keepPositions(keepSegments(top, before), before),
                    before,
                ),
            ),
            speeds,
        );

        store.patch({
            streamSeq,
            streamTouched,
            videoSpeeds: speeds,
            entries,
            tasks,
        });

        // A video raises no toast; its group does, when it ends.
        noteTransitions(top, before);
    }

    /**
     * Patch the numbers on one row in place, without a refetch. Every field
     * falls back to what the row already holds: the API leaves out what is
     * unchanged, and defaulting to 0 would blank the size mid-download.
     */
    function applyProgress(data: any): void {
        const state = store.get();
        // A collection's video: its row in an open Entries list, and its
        // collection's live speed. Never a row of the list.
        if (data.collection_id) {
            const groupId = String(data.collection_id);
            const videoSpeeds = trackVideoSpeed(
                state.videoSpeeds,
                groupId,
                data.id,
                data.live?.speed_bps ?? 0,
            );
            store.patch({
                videoSpeeds,
                entries: applyVideoProgress(state.entries, groupId, data),
                tasks: withGroupSpeeds(state.tasks, videoSpeeds),
            });
            return;
        }
        store.patch({
            tasks: state.tasks.map((t) =>
                t.id === data.id ? applyProgressFrame(t, data) : t,
            ),
        });
    }

    return {
        loadSummary,
        sync,
        loadPage,

        async loadMore(): Promise<void> {
            const { loadingMore, page, totalPages } = store.get();
            if (loadingMore || page >= totalPages) return;
            store.patch({ loadingMore: true });
            try {
                await loadPage(store.get().page + 1);
            } finally {
                store.patch({ loadingMore: false });
            }
        },

        merge,
        applyProgress,

        async changeFilter(filter: string): Promise<void> {
            store.patch({ filter: filter as AppState["filter"] });
            await restart();
        },

        /**
         * A new category in the sidebar: a new list, and new counts, since the
         * counts follow the category where the status counts did not.
         */
        async changeCategory(id: string | null): Promise<void> {
            store.patch({ category: id });
            await restart();
            await loadSummary();
        },

        changeSearch(query: string): void {
            store.patch({ searchQuery: query });
        },

        /**
         * Move a row to another category. The row is replaced from the answer,
         * in one synchronous read-then-write of the list, so a stream frame
         * landing meanwhile cannot undo it.
         */
        async moveToCategory(
            taskId: string,
            categoryId: string,
        ): Promise<void> {
            const task = store.get().tasks.find((t) => t.id === taskId);
            if (!task) return;
            let updated: any;
            try {
                updated = await sendMove(
                    { id: taskId, collection: task.kind === "playlist" },
                    categoryId,
                );
            } catch (err) {
                app.toasts.notify("error", errorMessage(err));
                return;
            }
            store.patch((s) => ({
                tasks: s.tasks.map((t) =>
                    t.id === taskId ? normalizeApiTask(updated) : t,
                ),
            }));
            await loadSummary();
            await app.categories.load();
        },
    };
}
