import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import "../style/global.css";
import { AppShell } from "@/component/layouts/app-shell";
import {
    addTorrent,
    heldTorrentNotice,
    apiUrl,
    deleteApi,
    appendPage,
    getApi,
    getPageApi,
    applyProgressFrame,
    normalizeApiTask,
    normalizeCollection,
    postApi,
    keepSegments,
    keepPositions,
    withPosition,
    pickFileToOpen,
    type PositionView,
    resolveTorrent,
    searchSources,
    type AddInitial,
    playsFromTorrent,
    settlePage,
    type ResolvedTorrent,
    countsMoved,
    normalizeSummary,
    onUnauthorized,
    type TaskSummary,
    type UiTask,
    addLink,
    directRequest,
    duplicateOf,
    withRowOnTop,
    type AddInput,
    placeRows,
    keepWatched,
    trackVideoSpeed,
    withGroupSpeeds,
    applyVideoRows,
    applyVideoProgress,
    groupToast,
    type EntriesView,
    type VideoSpeeds,
    EMPTY_ENTRIES,
    addEntriesPage,
    dropVideo,
} from "@/lib/api";
import { parseDisk, type Disk } from "@/lib/api/disk";
import { loadApiKey, saveApiKey } from "@/lib/api/key";
import {
    FALLBACK_POLL_MS,
    connectionOnError,
    isDegraded,
    shouldWarn,
    type Connection,
} from "@/lib/connection";
import {
    createToast,
    dismiss,
    errorMessage,
    prune,
    raise,
    transitionToast,
    type Toast,
    type ToastTone,
} from "@/lib/toast";
import { DEFAULT_SORT, loadSort, saveSort, type SortValue } from "@/lib/sort";
import { DEFAULT_PREFS, loadPrefs, savePrefs, type Prefs } from "@/lib/prefs";
import { mediaFiles, type PlayableFile } from "@/lib/media";
import type { ServerSettings } from "@/component/features/settings-modal";
import { removePrompt } from "@/component/features/remove-dialog/prompt";
import type { PlaylistRequest } from "@/lib/api/playlist";
import {
    adjacentItem,
    queueFromVideos,
    resumeItem,
    startItem,
    type QueueItem,
} from "@/lib/queue";

/** Rows per request. The API caps this at 100. */
const PAGE_SIZE = 25;

/** Videos per Entries page. The API caps a page at 100. */
const ENTRIES_PAGE = 50;

/** Videos per page when a group's play queue fetches more. The API caps it at 100. */
const QUEUE_PAGE = 100;

type BulkAction = "pause_all" | "resume_all" | "clear_finished";

/** What each sweep did, for the line it leaves behind. */
const BULK_VERBS: Record<BulkAction, string> = {
    pause_all: "Paused",
    resume_all: "Resumed",
    clear_finished: "Cleared",
};

export default component$(() => {
    const store = useStore({
        tasks: [] as UiTask[],
        // The last page fetched, and how many there are for the current
        // filter. Pages accumulate: nothing already on screen is dropped to
        // make room, which is what lets the list grow as it is scrolled.
        page: 1,
        totalPages: 1,
        loadingMore: false,
        // Counts for every filter, from the database rather than from the
        // rows that happen to be loaded.
        summary: null as TaskSummary | null,
        summaryLoading: false,
        summaryAgain: false,
        toasts: [] as Toast[],
        // The task the remove dialog is asking about, or null when it is shut.
        removing: null as {
            id: string;
            title: string;
            status: string;
            videos?: number;
        } | null,
        // The sweep waiting to be confirmed, or null when nothing is pending.
        pendingBulk: null as BulkAction | null,
        // Ticked only while a retry is actually pending; see the clock below.
        now: Date.now(),
        connection: "connecting" as Connection,
        // When the current gap in live updates began, for the warning's grace
        // period. Null whenever the stream is live.
        degradedSince: null as number | null,
        // The id of the "lost contact" toast, so reconnecting can take it
        // down rather than leaving a stale alarm on screen.
        outageToastId: null as string | null,
        // From the event stream's `disk` frames; null until the first arrives.
        disk: null as Disk | null,
        filter: "all" as "all" | "downloading" | "seeding" | "completed",
        searchQuery: "" as string,
        // The row Open just brought into view, outlined for a moment.
        highlightId: "" as string,
        // Read from storage once the browser is running; the server render
        // has no localStorage and must not guess at one.
        sort: DEFAULT_SORT as SortValue,
        settingsOpen: false,
        prefs: DEFAULT_PREFS as Prefs,
        // Null until the API answers, and after it refuses.
        serverSettings: null as ServerSettings | null,
        searchEnabled: false as boolean,
        searchTorrents: false as boolean,
        searchYoutube: false as boolean,
        addInitial: null as (AddInitial & { id: number }) | null,
        addInitialSeq: 0,
        apiKey: "" as string,
        // Set by the first 401 and kept: it is what puts the key field first,
        // and what stops every later 401 from reopening a closed modal.
        apiKeyMessage: null as string | null,
        sidebarOpen: false as boolean,
        sidebarCollapsed: true as boolean,
        addModalOpen: false as boolean,
        playerModalOpen: false as boolean,
        playerUrl: "" as string,
        playerKind: "" as string,
        playerTaskId: "" as string,
        playerFileIndex: null as number | null,
        playerFiles: [] as PlayableFile[],
        playerFromTorrent: false as boolean,
        playerPositions: [] as PositionView[],
        // A count of stream writes, and the count at each task's latest one:
        // what lets a page fetch tell which rows went stale while it was out.
        // Only ids the stream has written are here, so it grows with the
        // tasks seen this session and no faster.
        streamSeq: 0,
        streamTouched: {} as Record<string, number>,
        // Each open Entries list, by group id (v0.5). Only open lists are
        // held, and a video frame for a shut one is dropped.
        entries: {} as Record<string, EntriesView>,
        // Each running group video's speed, for its group's live speed.
        videoSpeeds: {} as VideoSpeeds,
        // A play queue (part 4): its items, the one playing, and for a group,
        // which of its /downloads pages are loaded. Empty when nothing queues.
        queue: [] as QueueItem[],
        queueIndex: -1,
        queueGroup: null as {
            id: string;
            page: number;
            totalPages: number;
        } | null,
    });

    const notify = $((tone: ToastTone, message: string) => {
        store.toasts = raise(
            store.toasts,
            createToast(tone, message, Date.now()),
        );
    });

    const handleDismissToast = $((id: string) => {
        store.toasts = dismiss(store.toasts, id);
    });

    /**
     * Ask for the counts again. A burst of frames (a playlist paused, a bulk
     * action) costs at most two requests: while one is in flight, later asks
     * only mark that it should run once more when it lands.
     *
     * Declared before `noteTransitions`, which calls it: a `$` closure is
     * captured where it is declared, so one declared below is not defined yet.
     */
    const loadSummary = $(async () => {
        if (store.summaryLoading) {
            store.summaryAgain = true;
            return;
        }
        store.summaryLoading = true;
        try {
            do {
                store.summaryAgain = false;
                const summary = await getApi<any>("/download/summary").catch(
                    () => null,
                );
                if (summary) store.summary = normalizeSummary(summary);
            } while (store.summaryAgain);
        } finally {
            store.summaryLoading = false;
        }
    });

    /**
     * Announce any row whose status moved since `before`, the list as it was
     * just before the write that brought `rows` in.
     *
     * Each update path reads the list, writes it, and only then calls this with
     * what it read, so whichever path lands first announces and the other finds
     * nothing changed. A row that is new to the list says nothing, which is
     * what keeps a reload quiet.
     */
    const noteTransitions = $((rows: UiTask[], before: UiTask[]) => {
        const previous = new Map(
            before.map((t) => [
                t.id,
                t.status,
            ]),
        );
        let next = store.toasts;
        let moved = false;

        for (const row of rows) {
            if (countsMoved(previous.get(row.id), row.status)) moved = true;
            const announcement =
                row.kind === "playlist"
                    ? groupToast(previous.get(row.id), row)
                    : transitionToast(previous.get(row.id), row);
            if (announcement) {
                next = raise(
                    next,
                    createToast(
                        announcement.tone,
                        announcement.message,
                        Date.now(),
                    ),
                );
            }
        }

        store.toasts = next;

        // A row moved between the sidebar's counts, so they are now wrong.
        // Pause, resume and remove raise no toast, but move them all the
        // same. Only a real move triggers this: the torrent monitor publishes
        // a task frame every tick whether or not anything changed, and
        // refetching on each of those would be a poll by another name.
        if (moved) loadSummary();
    });

    /**
     * Fetch one page of the current filter.
     *
     * Page 1 replaces the list; later pages are added to it. A request that
     * never landed leaves everything exactly as it is — the list is the only
     * record of what was running, and emptying it during an outage throws away
     * the very thing the connection indicator is saying is merely stale.
     *
     * Everything from reading the list to writing it happens without an
     * `await`: a stream frame applied in between would be overwritten.
     */
    const loadPage = $(async (page: number) => {
        const query =
            `page=${page}&page_size=${PAGE_SIZE}` +
            `&group=${store.filter}&sort=${store.sort}`;
        const since = store.streamSeq;
        const result = await getPageApi<any[]>(`/download?${query}`).catch(
            () => null,
        );
        if (result === null) return;

        const before = store.tasks;
        // Rows the stream wrote while this was in flight are newer than the
        // page's copies of them.
        const touched = new Set(
            Object.entries(store.streamTouched)
                .filter((entry) => entry[1] > since)
                .map((entry) => entry[0]),
        );
        const rows = keepSegments(
            settlePage(result.data.map(normalizeApiTask), before, touched),
            before,
        );
        store.tasks = page === 1 ? rows : appendPage(before, rows);
        store.page = result.meta.page;
        store.totalPages = result.meta.totalPages;

        await noteTransitions(rows, before);
    });

    /** Back to the top of the current filter. */
    const syncTask = $(async () => {
        await loadPage(1);
        await loadSummary();
    });

    const handleLoadMore = $(async () => {
        if (store.loadingMore || store.page >= store.totalPages) return;
        store.loadingMore = true;
        try {
            await loadPage(store.page + 1);
        } finally {
            store.loadingMore = false;
        }
    });

    /**
     * Fold rows from an SSE frame into the list, replacing what they match.
     *
     * Like `loadPage`, it reads, marks and writes without an `await`, so a
     * page landing mid-merge sees either none of this frame or all of it.
     */
    const mergeTasks = $(async (rows: UiTask[]) => {
        const before = store.tasks;
        const top = rows.filter((row) => row.parentId === undefined);
        const videos = rows.filter((row) => row.parentId !== undefined);

        // What lets a page fetch in flight know these rows are newer than its
        // copies of them. Videos never reach a page of the list.
        store.streamSeq += 1;
        for (const row of top) store.streamTouched[row.id] = store.streamSeq;

        // A group's videos update its open Entries list, and one that has
        // stopped stops counting towards its speed (v0.5).
        if (videos.length > 0) {
            let speeds = store.videoSpeeds;
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
            store.videoSpeeds = speeds;
            store.entries = applyVideoRows(store.entries, videos);
        }

        // In place, not moved to the top: a group recounts on every video's
        // status change, and would otherwise jump up the list each time. A
        // canceled row leaves: cancelling publishes the row it removed.
        // Stream frames carry neither segments nor positions (#96).
        store.tasks = withGroupSpeeds(
            placeRows(
                before,
                keepWatched(
                    keepPositions(keepSegments(top, before), before),
                    before,
                ),
            ),
            store.videoSpeeds,
        );

        // A video raises no toast; its group does, when it ends.
        await noteTransitions(top, before);
    });

    /**
     * Patch the numbers on one row in place, without a refetch.
     *
     * Every field falls back to what the row already holds. The API serialises
     * progress frames with `exclude_none`, so an absent `total_size` means
     * "unchanged", not "zero" — defaulting to 0 blanked the size mid-download.
     */
    const applyProgress = $((data: any) => {
        // A collection's video: its row in an open Entries list, and its
        // collection's live speed. Never a row of the list.
        if (data.collection_id) {
            const groupId = String(data.collection_id);
            store.videoSpeeds = trackVideoSpeed(
                store.videoSpeeds,
                groupId,
                data.id,
                data.live?.speed_bps ?? 0,
            );
            store.entries = applyVideoProgress(store.entries, groupId, data);
            store.tasks = withGroupSpeeds(store.tasks, store.videoSpeeds);
            return;
        }
        store.tasks = store.tasks.map((t) =>
            t.id === data.id ? applyProgressFrame(t, data) : t,
        );
    });

    // Declared before the visible task that calls it: a `$` closure is captured
    // where the task is declared, so one declared below it is not defined yet.
    /** Search shows when the API has torrent sources or can search YouTube; a failure here just hides it. */
    const refreshSearchAvailability = $(async () => {
        try {
            const sources = await searchSources();
            store.searchTorrents = sources.enabled;
            store.searchYoutube = sources.youtube;
            store.searchEnabled = sources.enabled || sources.youtube;
        } catch {
            store.searchTorrents = false;
            store.searchYoutube = false;
            store.searchEnabled = false;
        }
    });

    useVisibleTask$(
        ({ cleanup }) => {
            // The remembered order, applied before the first fetch so the
            // list does not arrive newest-first and then reshuffle.
            store.sort = loadSort();
            store.prefs = loadPrefs();
            store.apiKey = loadApiKey();
            // Registered before the first request, so its answer is heard.
            const stopListening = onUnauthorized(() => {
                if (store.apiKeyMessage !== null) return;
                store.apiKeyMessage = store.apiKey
                    ? "The API refused the saved key. Check it matches API_KEY in api/.env."
                    : "The API requires a key. Enter the one set as API_KEY in api/.env.";
                store.settingsOpen = true;
            });
            syncTask();
            refreshSearchAvailability();
            // One clock for every toast, rather than a timer per toast: an
            // expiry is a deadline, and a sweep is how a deadline is noticed.
            const sweeper = setInterval(() => {
                // Only write when something actually expired. `prune` returns
                // a fresh array every call, and assigning it unconditionally
                // re-rendered the whole shell twice a second — which, among
                // other things, kept restarting the modal's entry animation.
                const kept = prune(store.toasts, Date.now());
                if (kept.length !== store.toasts.length) store.toasts = kept;
            }, 500);
            // The retry countdown's clock. It writes only while a deadline is
            // live, so a list with nothing retrying re-renders at the poll's
            // pace rather than every second. The two second grace lets the
            // last tick land, turning "Retrying in 1s" into "Retrying…"
            // instead of freezing on the final second.
            const clock = setInterval(() => {
                const at = Date.now();
                const waiting = store.tasks.some(
                    (task) =>
                        task.status === "pending" &&
                        task.nextAttemptAt !== undefined &&
                        task.nextAttemptAt > at - 2000,
                );
                if (waiting) store.now = at;
            }, 1000);

            /**
             * What used to be an unconditional poll every 2.5s.
             *
             * The stream carries every change and replays a full snapshot on
             * each connection, so fetching the list on a timer bought nothing
             * except a request per tab per tick — and hid any stream bug, by
             * papering over it within seconds. Fetching now happens only while
             * the stream is not confirmed live, which also covers the case it
             * never opens at all: a proxy that strips `text/event-stream`
             * leaves a degraded app rather than a frozen one.
             */
            let lastFallbackAt = 0;
            const watch = setInterval(() => {
                const at = Date.now();
                if (!isDegraded(store.connection)) return;

                if (at - lastFallbackAt >= FALLBACK_POLL_MS) {
                    lastFallbackAt = at;
                    syncTask();
                }

                if (
                    store.outageToastId === null &&
                    // A refused key already has the modal saying so; "lost
                    // contact" would send someone to check the wrong thing.
                    store.apiKeyMessage === null &&
                    shouldWarn(store.connection, store.degradedSince, at)
                ) {
                    const toast = createToast(
                        "error",
                        "Lost contact with the API. Still trying, and the list may be out of date.",
                        at,
                    );
                    store.outageToastId = toast.id;
                    store.toasts = raise(store.toasts, toast);
                }
            }, 1000);

            let apiEvents: EventSource | null = null;
            try {
                apiEvents = new EventSource(
                    apiUrl("/download/events", { withKey: true }),
                );
                apiEvents.addEventListener("downloads", (event) => {
                    try {
                        const rows = JSON.parse(
                            (event as MessageEvent).data,
                        ) as any[];
                        mergeTasks(rows.map(normalizeApiTask));
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("download", (event) => {
                    try {
                        mergeTasks([
                            normalizeApiTask(
                                JSON.parse((event as MessageEvent).data),
                            ),
                        ]);
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("collection", (event) => {
                    try {
                        mergeTasks([
                            normalizeCollection(
                                JSON.parse((event as MessageEvent).data),
                            ),
                        ]);
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("progress", (event) => {
                    try {
                        applyProgress(JSON.parse((event as MessageEvent).data));
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("disk", (event) => {
                    try {
                        const disk = parseDisk(
                            JSON.parse((event as MessageEvent).data),
                        );
                        if (disk) store.disk = disk;
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.onopen = () => {
                    const wasWarned = store.outageToastId;
                    store.connection = "live";
                    store.degradedSince = null;
                    // The snapshot that follows an open replaces whatever went
                    // stale during the gap, so nothing else has to be undone.
                    if (wasWarned) {
                        store.toasts = raise(
                            dismiss(store.toasts, wasWarned),
                            createToast("info", "Back in contact", Date.now()),
                        );
                        store.outageToastId = null;
                    }
                };
                apiEvents.onerror = () => {
                    // The browser reopens on its own; this only records that
                    // it is currently not open, so the footer can say so.
                    store.connection = connectionOnError(
                        apiEvents?.readyState ?? 2,
                    );
                    store.degradedSince ??= Date.now();
                };
            } catch {
                apiEvents = null;
                store.connection = "offline";
                store.degradedSince ??= Date.now();
            }

            cleanup(() => {
                stopListening();
                clearInterval(watch);
                clearInterval(sweeper);
                clearInterval(clock);
                apiEvents?.close();
            });
        },
        { strategy: "document-ready" },
    );

    const toggleSidebar = $(() => {
        store.sidebarOpen = !store.sidebarOpen;
    });

    const toggleSidebarCollapse = $(() => {
        store.sidebarCollapsed = !store.sidebarCollapsed;
    });

    const handleFilterChange = $(async (filter: string) => {
        store.filter = filter as any;
        // The filter is answered by the database now, so changing it is a new
        // list rather than a different view of this one.
        store.page = 1;
        store.totalPages = 1;
        await loadPage(1);
    });

    const handleSettingsOpen = $(async () => {
        store.settingsOpen = true;
        // Fetched on opening rather than on load: nothing else needs it, and
        // it cannot change without the API restarting.
        const server = await getApi<ServerSettings>("/settings").catch(
            () => null,
        );
        store.serverSettings = server;
    });

    const handleSettingsClose = $(() => {
        store.settingsOpen = false;
    });

    const handleApiKeySave = $((key: string) => {
        saveApiKey(key);
        // A reload rather than patching things up in place: the event stream,
        // the list and any open player were all opened with the old key, and
        // starting again is the one way to be sure none of them kept it.
        location.reload();
    });

    const handlePrefsChange = $((prefs: Prefs) => {
        store.prefs = prefs;
        savePrefs(prefs);
    });

    const handleSortChange = $(async (sort: SortValue) => {
        store.sort = sort;
        saveSort(sort);
        // A different order is a different list, so it starts again at the top.
        store.page = 1;
        store.totalPages = 1;
        await loadPage(1);
    });

    const handleSearchChange = $((query: string) => {
        store.searchQuery = query;
    });

    const handleAddClick = $(() => {
        store.addInitial = null;
        store.addModalOpen = true;
    });

    /** Add on a search result: the dialog opens on it, already resolving. */
    const handleAddFound = $((initial: AddInitial) => {
        store.addInitialSeq += 1;
        store.addInitial = { ...initial, id: store.addInitialSeq };
        store.addModalOpen = true;
    });

    /** The next page of a group's open Entries list. */
    const loadEntries = $(async (groupId: string) => {
        const view = store.entries[groupId];
        if (!view) return;
        store.entries = {
            ...store.entries,
            [groupId]: { ...view, loading: true },
        };
        const result = await getPageApi<any[]>(
            `/collection/${groupId}/downloads?page=${view.page + 1}&page_size=${ENTRIES_PAGE}`,
        ).catch(() => null);
        // Read again: the stream may have written while the page was out.
        const current = store.entries[groupId];
        // Shut meanwhile: nothing to add it to.
        if (!current) return;
        store.entries = {
            ...store.entries,
            [groupId]:
                result === null
                    ? { ...current, loading: false }
                    : addEntriesPage(
                          current,
                          result.data.map(normalizeApiTask),
                          result.meta.page,
                          result.meta.totalPages,
                      ),
        };
        if (result === null) {
            notify("error", "Couldn't load this group's videos");
        }
    });

    /** An open Entries list, from its first page again. */
    const reloadEntries = $(async (groupId: string) => {
        if (!store.entries[groupId]) return;
        store.entries = { ...store.entries, [groupId]: EMPTY_ENTRIES };
        await loadEntries(groupId);
    });

    const handleTaskAction = $(
        async (taskId: string, action: "pause" | "resume") => {
            const task = store.tasks.find((t) => t.id === taskId);
            if (!task) return;

            try {
                const updated = await postApi<any>(
                    task.kind === "playlist"
                        ? `/collection/${taskId}/${action}`
                        : `/download/${taskId}/${action}`,
                    {},
                );
                if (updated) {
                    const row = normalizeApiTask(updated);
                    store.tasks = store.tasks.map((t) =>
                        t.id === taskId ? row : t,
                    );
                }
                // A group moves its videos in one UPDATE and publishes only
                // its own frame, so an open Entries list is read again.
                if (task.kind === "playlist") await reloadEntries(taskId);
            } catch (err) {
                notify("error", errorMessage(err));
            }
        },
    );

    const handlePause = $((id: string) => handleTaskAction(id, "pause"));

    const handleResume = $((id: string) => handleTaskAction(id, "resume"));

    /** Open the dialog. Nothing is removed until it is confirmed. */
    const handleRemoveConfirm = $(
        async (taskId: string, deleteFiles: boolean) => {
            store.removing = null;
            // A collection is removed by its own route; a collection's video,
            // which is never a row of the list, by the download's.
            const removing = store.tasks.find((t) => t.id === taskId);

            try {
                await deleteApi(
                    removing?.kind === "playlist"
                        ? `/collection/${taskId}?delete_files=${deleteFiles}`
                        : `/download/${taskId}?delete_files=${deleteFiles}`,
                );
            } catch (err) {
                // The row still goes: the person asked for it gone, and a failure
                // here is nearly always a row the API has already forgotten.
                notify("error", errorMessage(err));
            }

            store.tasks = store.tasks.filter((t) => t.id !== taskId);
            // A video leaves its Entries list; a group's own list shuts.
            const { [taskId]: _shut, ...open } = store.entries;
            store.entries = dropVideo(open, taskId);
            // The row leaves before its frame lands, so the frame finds nothing
            // to compare with: the counts are asked for here, as the desktop does.
            loadSummary();
        },
    );

    const handleRemove = $(async (taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        // A group asks about its videos, and can always keep what finished.
        const videos =
            task.kind === "playlist"
                ? (task.entryCounts?.total ?? 0)
                : undefined;

        if (!store.prefs.confirmBeforeRemove) {
            // Straight through, on the same terms the dialog would have
            // offered by default: keep a finished download's files, and take
            // the partial remains of anything else.
            const keepable = removePrompt(task.status, videos).canKeepFiles;
            await handleRemoveConfirm(task.id, !keepable);
            return;
        }

        store.removing = {
            id: task.id,
            title: task.title,
            status: task.status,
            videos,
        };
    });

    /** Open a group's Entries, fetching its first page; or shut them. */
    const handleToggleEntries = $(async (groupId: string) => {
        if (store.entries[groupId]) {
            const { [groupId]: _shut, ...open } = store.entries;
            store.entries = open;
            return;
        }
        store.entries = { ...store.entries, [groupId]: EMPTY_ENTRIES };
        await loadEntries(groupId);
    });

    /** A video's own pause or resume. Its new row arrives by its frame. */
    const handleVideoAction = $(
        async (videoId: string, action: "pause" | "resume") => {
            try {
                await postApi(`/download/${videoId}/${action}`, {});
            } catch (err) {
                notify("error", errorMessage(err));
            }
        },
    );

    const handleRemoveVideo = $(async (groupId: string, videoId: string) => {
        const video = store.entries[groupId]?.rows.find(
            (row) => row.id === videoId,
        );
        if (!video) return;
        if (!store.prefs.confirmBeforeRemove) {
            await handleRemoveConfirm(
                video.id,
                !removePrompt(video.status).canKeepFiles,
            );
            return;
        }
        store.removing = {
            id: video.id,
            title: video.title,
            status: video.status,
        };
    });

    const runBulk = $(async (action: BulkAction) => {
        try {
            const result = await postApi<any>("/download/bulk", { action });
            const affected = result?.affected ?? 0;
            notify(
                "info",
                affected === 0
                    ? "Nothing to do"
                    : `${BULK_VERBS[action]} ${affected} download${affected === 1 ? "" : "s"}`,
            );
        } catch (err) {
            notify("error", errorMessage(err));
        }
        await syncTask();
    });

    /**
     * Run a sweep, or ask first when it removes things.
     *
     * Pausing and resuming are reversible in one click, so they just happen.
     * Clearing is not, so it asks — and says how many rows it will take, since
     * the button was drawn from the rows on screen and the sweep is not.
     */
    const handleBulk = $(async (action: BulkAction) => {
        if (action === "clear_finished") {
            store.pendingBulk = action;
            return;
        }
        await runBulk(action);
    });

    const handleBulkCancel = $(() => {
        store.pendingBulk = null;
    });

    const handleBulkConfirm = $(async () => {
        const action = store.pendingBulk;
        store.pendingBulk = null;
        if (action) await runBulk(action);
    });

    const handleRemoveCancel = $(() => {
        store.removing = null;
    });

    const handleDownloadFile = $((taskId: string, fileIndex?: number) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        // A torrent's file by its index; any other download's one file is index 0,
        // and a torrent of one selected file hands over that file.
        const index =
            fileIndex ??
            (task.kind === "torrent"
                ? (task.files?.find((file) => file.selected)?.index ?? 0)
                : 0);
        const a = document.createElement("a");
        a.href = apiUrl(`/download/${taskId}/file/${index}`, { withKey: true });
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    });

    const handleAddModalClose = $(() => {
        store.addModalOpen = false;
    });

    const handlePlayClick = $(
        (
            value: string,
            kind: string,
            fileIndex: number | null = null,
            files: PlayableFile[] = [],
        ) => {
            store.queue = [];
            store.queueIndex = -1;
            store.queueGroup = null;
            store.playerTaskId = "";
            // A torrent from the dialog: which file, and the ones to switch between (#98).
            store.playerFileIndex = fileIndex;
            store.playerFiles = files;
            store.playerFromTorrent = false;
            store.playerUrl = value;
            store.playerKind = kind;
            store.playerModalOpen = true;
        },
    );

    // A finished download, played from its file rather than its source (#94).
    const handlePlayTask = $((taskId: string) => {
        store.queue = [];
        store.queueIndex = -1;
        store.queueGroup = null;
        const task = store.tasks.find((t) => t.id === taskId);
        store.playerUrl = "";
        store.playerKind = "";
        // The file left partway through, else the first not yet watched
        // (#97); `null` for a task with no file list, which lets the API
        // pick as it always has.
        store.playerFileIndex = task
            ? pickFileToOpen(task, task.positions)
            : null;
        // A torrent's files, for the player's file menu (#98).
        store.playerFiles = mediaFiles(task?.files ?? []);
        // Still downloading: its stream, not its partial file (#95).
        store.playerFromTorrent = task ? playsFromTorrent(task) : false;
        // Where each file was left, to resume there (#96).
        store.playerPositions = task?.positions ?? [];
        store.playerTaskId = taskId;
        store.playerModalOpen = true;
    });

    // The player saved where it is: the card's bar follows at once, and the
    // next Play resumes there (#96). One synchronous read and write of
    // store.tasks, so no stream frame lands in between.
    const handlePositionSaved = $((taskId: string, position: PositionView) => {
        store.tasks = store.tasks.map((task) =>
            task.id === taskId ? withPosition(task, position) : task,
        );
        if (store.playerTaskId === taskId) {
            store.playerPositions = [
                ...store.playerPositions.filter(
                    (p) => p.fileIndex !== position.fileIndex,
                ),
                position,
            ];
        }
    });

    const handlePlayerModalClose = $(() => {
        store.queue = [];
        store.queueIndex = -1;
        store.queueGroup = null;
        store.playerModalOpen = false;
    });

    /** Point the player at one queue item. Synchronous: the player re-runs on its own signal. */
    const playQueueItem = $((index: number) => {
        const item = store.queue[index];
        if (!item) return;
        store.queueIndex = index;
        store.playerFileIndex = null;
        store.playerFiles = [];
        store.playerFromTorrent = false;
        if ("taskId" in item.source) {
            store.playerUrl = "";
            store.playerKind = "";
            store.playerPositions = item.positions ?? [];
            store.playerTaskId = item.source.taskId;
        } else {
            store.playerTaskId = "";
            store.playerPositions = [];
            store.playerKind = "site";
            store.playerUrl = item.source.url;
        }
        store.playerModalOpen = true;
    });

    /** The next page of a group's videos onto the queue, if there is one. */
    const loadQueuePage = $(async () => {
        const group = store.queueGroup;
        if (!group || group.page >= group.totalPages) return false;
        const result = await getPageApi<any[]>(
            `/collection/${group.id}/downloads?page=${group.page + 1}&page_size=${QUEUE_PAGE}`,
        ).catch(() => null);
        // Read again: the queue may have been closed or replaced meanwhile.
        if (result === null || store.queueGroup?.id !== group.id) return false;
        store.queue = [
            ...store.queue,
            ...queueFromVideos(result.data.map(normalizeApiTask)),
        ];
        store.queueGroup = {
            id: group.id,
            page: result.meta.page,
            totalPages: result.meta.totalPages,
        };
        return true;
    });

    /** Next or Previous from the player; near the end of what's loaded, load more. */
    const stepQueue = $(async (dir: 1 | -1) => {
        let next = adjacentItem(store.queue, store.queueIndex, dir);
        if (next === null && dir === 1 && (await loadQueuePage())) {
            next = adjacentItem(store.queue, store.queueIndex, dir);
        }
        if (next === null) return;
        await playQueueItem(next);
        if (dir === 1 && store.queue.length - next <= 3) loadQueuePage();
    });

    /** Play all from the picker: its ticked videos, streamed in order. */
    const handlePlayQueue = $(async (items: QueueItem[], start: number) => {
        store.queueGroup = null;
        store.queue = items;
        await playQueueItem(start);
    });

    /**
     * Play all from a group's card: from where it was left, finished videos
     * from their files and the rest from their pages. Pages are loaded until
     * one holds a video to resume, or there are none left.
     */
    const handlePlayGroup = $(async (groupId: string) => {
        store.queue = [];
        store.queueIndex = -1;
        store.queueGroup = { id: groupId, page: 0, totalPages: 1 };
        while (resumeItem(store.queue) === null && (await loadQueuePage())) {
            // keep loading
        }
        if (store.queueGroup?.id !== groupId) return;
        const start = startItem(store.queue);
        if (start === null) {
            notify("info", "Nothing in this playlist can be played yet");
            return;
        }
        await playQueueItem(start);
    });

    const handleResolveTorrent = $(
        (torrent: string): Promise<ResolvedTorrent> => {
            return resolveTorrent(torrent);
        },
    );

    const handleAdd = $(async (input: AddInput) => {
        try {
            // unwrap() throws with the service's own message on either
            // envelope, so there is no response.ok check to write here.
            if (input.type === "site" || input.type === "url") {
                // For a page, the add box already asked the API about it
                // and picked a preset it offers.
                const request = directRequest(input);
                await postApi(request.path, request.body);
            } else if (input.type === "link") {
                // A link nobody has looked at yet: ask, then route it.
                await addLink(
                    input.value,
                    input.preset || store.prefs.defaultPreset,
                    postApi,
                    input.allowDuplicate,
                );
            } else {
                const known = new Set(store.tasks.map((task) => task.id));
                const notice = heldTorrentNotice(
                    await addTorrent(input.value, input.files ?? []),
                    known,
                );
                if (notice) notify("info", notice);
            }
        } catch (err) {
            // A duplicate is answered where it was asked, with Open and
            // Add anyway, so it raises no toast of its own.
            if (!duplicateOf(err)) notify("error", errorMessage(err));
            // Rethrown so the modal keeps what was typed instead of
            // closing over a submission that never landed.
            throw err;
        }

        store.addModalOpen = false;
        // The new row arrives by its own frame, on top, without taking
        // the list back to page 1. Only the counts need asking again.
        loadSummary();
    });

    /**
     * Bring a download the list already holds into view and outline it.
     *
     * The list is the database's answer for a filter and a page, so the row
     * may be on none of what is loaded: reset to every download, and fetch the
     * one row if page 1 still lacks it.
     */
    const handleOpenDownload = $(async (id: string) => {
        store.filter = "all";
        store.searchQuery = "";
        store.page = 1;
        store.totalPages = 1;
        await loadPage(1);
        if (!store.tasks.some((task) => task.id === id)) {
            const row = await getApi<any>(`/download/${id}`).catch(() => null);
            if (row === null) {
                notify("error", "That download is no longer in your list");
                return;
            }
            store.tasks = withRowOnTop(store.tasks, normalizeApiTask(row));
        }
        store.highlightId = id;
        setTimeout(() => {
            document
                .getElementById(`download-${id}`)
                ?.scrollIntoView({ block: "center", behavior: "smooth" });
        }, 50);
        setTimeout(() => {
            if (store.highlightId === id) store.highlightId = "";
        }, 2600);
    });

    /** A playlist's ticked videos, as one group. Its row arrives by its frame. */
    const handleAddPlaylist = $(async (request: PlaylistRequest) => {
        try {
            await postApi("/collection", request);
        } catch (err) {
            notify("error", errorMessage(err));
            // Rethrown so the picker stays open on what was ticked.
            throw err;
        }
        const n = request.entries.length;
        notify(
            "info",
            `Added ${n.toLocaleString("en-US")} ${n === 1 ? "video" : "videos"}`,
        );
        loadSummary();
    });

    const handleStopSeeding = $(async (taskId: string) => {
        try {
            await postApi(`/download/${taskId}/seed/stop`, {});
        } catch (err) {
            notify("error", errorMessage(err));
            return;
        }
        syncTask();
    });

    return (
        <AppShell
            tasks={store.tasks}
            filter={store.filter}
            searchQuery={store.searchQuery}
            sort={store.sort}
            onSortChange={handleSortChange}
            settingsOpen={store.settingsOpen}
            onSettingsOpen={handleSettingsOpen}
            onSettingsClose={handleSettingsClose}
            prefs={store.prefs}
            onPrefsChange={handlePrefsChange}
            serverSettings={store.serverSettings}
            apiKey={store.apiKey}
            apiKeyMessage={store.apiKeyMessage}
            onApiKeySave={handleApiKeySave}
            now={store.now}
            connection={store.connection}
            disk={store.disk}
            summary={store.summary}
            page={store.page}
            totalPages={store.totalPages}
            loadingMore={store.loadingMore}
            onLoadMore={handleLoadMore}
            toasts={store.toasts}
            onDismissToast={handleDismissToast}
            sidebarOpen={store.sidebarOpen}
            sidebarCollapsed={store.sidebarCollapsed}
            onSidebarToggle={toggleSidebar}
            onSidebarCollapseToggle={toggleSidebarCollapse}
            onFilterChange={handleFilterChange}
            onSearchChange={handleSearchChange}
            addModalOpen={store.addModalOpen}
            onAddModalClose={handleAddModalClose}
            onAddClick={handleAddClick}
            playerModalOpen={store.playerModalOpen}
            playerUrl={store.playerUrl}
            playerKind={store.playerKind}
            playerTaskId={store.playerTaskId}
            playerFileIndex={store.playerFileIndex}
            playerFiles={store.playerFiles}
            playerFromTorrent={store.playerFromTorrent}
            playerPositions={store.playerPositions}
            onPositionSaved={handlePositionSaved}
            onPlayClick={handlePlayClick}
            onPlayerModalClose={handlePlayerModalClose}
            onPause={handlePause}
            onResume={handleResume}
            onDownloadFile={handleDownloadFile}
            onPlay={handlePlayTask}
            onRemove={handleRemove}
            removing={store.removing}
            onRemoveCancel={handleRemoveCancel}
            onRemoveConfirm={handleRemoveConfirm}
            onBulk={handleBulk}
            bulkPrompt={
                store.pendingBulk === "clear_finished"
                    ? {
                          heading: "Clear finished downloads?",
                          body: "Completed downloads leave the list and keep their files. Anything that failed is discarded along with whatever it had downloaded.",
                          confirmLabel: "Clear finished",
                      }
                    : null
            }
            onBulkCancel={handleBulkCancel}
            onBulkConfirm={handleBulkConfirm}
            onAdd={handleAdd}
            onOpenDownload={handleOpenDownload}
            highlightId={store.highlightId}
            onResolve={handleResolveTorrent}
            searchEnabled={store.searchEnabled}
            searchTorrents={store.searchTorrents}
            searchYoutube={store.searchYoutube}
            addInitial={store.addInitial}
            onAddFound={handleAddFound}
            onNotify={notify}
            onSourcesChanged={refreshSearchAvailability}
            onStopSeeding={handleStopSeeding}
            onAddPlaylist={handleAddPlaylist}
            entries={store.entries}
            onToggleEntries={handleToggleEntries}
            onLoadMoreEntries={loadEntries}
            onPauseVideo={$((id: string) => handleVideoAction(id, "pause"))}
            onResumeVideo={$((id: string) => handleVideoAction(id, "resume"))}
            onRemoveVideo={handleRemoveVideo}
            onPlayQueue={handlePlayQueue}
            onPlayGroup={handlePlayGroup}
            playerHasPreviousItem={
                adjacentItem(store.queue, store.queueIndex, -1) !== null
            }
            playerHasNextItem={
                adjacentItem(store.queue, store.queueIndex, 1) !== null ||
                Boolean(
                    store.queueGroup &&
                    store.queueGroup.page < store.queueGroup.totalPages,
                )
            }
            playerNextItemTitle={
                store.queue[
                    adjacentItem(store.queue, store.queueIndex, 1) ?? -1
                ]?.title ?? ""
            }
            onNextItem={$(() => stepQueue(1))}
            onPreviousItem={$(() => stepQueue(-1))}
        />
    );
});
