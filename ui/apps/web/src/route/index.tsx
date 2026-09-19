import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import "../style/global.css";
import { AppShell } from "@/component/layouts/app-shell";
import {
    addTorrent,
    apiUrl,
    deleteApi,
    getApi,
    normalizeApiTask,
    normalizeSegments,
    postApi,
    resolveTorrent,
    type ResolvedTorrent,
    type UiTask,
} from "@/lib/api";
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

const MAX_TASKS = 50;

export default component$(() => {
    const store = useStore({
        tasks: [] as UiTask[],
        toasts: [] as Toast[],
        // The task the remove dialog is asking about, or null when it is shut.
        removing: null as { id: string; title: string; status: string } | null,
        // Ticked only while a retry is actually pending; see the clock below.
        now: Date.now(),
        connection: "connecting" as Connection,
        // When the current gap in live updates began, for the warning's grace
        // period. Null whenever the stream is live.
        degradedSince: null as number | null,
        // The id of the "lost contact" toast, so reconnecting can take it
        // down rather than leaving a stale alarm on screen.
        outageToastId: null as string | null,
        filter: "all" as "all" | "downloading" | "seeding" | "completed",
        searchQuery: "" as string,
        sidebarOpen: false as boolean,
        sidebarCollapsed: true as boolean,
        addModalOpen: false as boolean,
        playerModalOpen: false as boolean,
        playerUrl: "" as string,
        playerKind: "" as string,
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
     * Announce any row whose status moved since the copy already on screen.
     *
     * Called by both update paths before either writes, so whichever arrives
     * first announces and the other finds nothing changed. A row that is new to
     * the list says nothing, which is what keeps a reload quiet.
     */
    const noteTransitions = $((rows: UiTask[]) => {
        const previous = new Map(
            store.tasks.map((t) => [
                t.id,
                t.status,
            ]),
        );
        let next = store.toasts;

        for (const row of rows) {
            const announcement = transitionToast(previous.get(row.id), row);
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
    });

    /**
     * Keep the segment strip across a refresh.
     *
     * Segments ride progress frames only — the task row the REST list and the
     * `task` event return has no `segments` field at all. Without this, the
     * 2.5s poll would blank the strip on every tick and the bars would flicker
     * in and out for the whole download.
     */
    const carrySegments = $((rows: UiTask[]) => {
        const prior = new Map(
            store.tasks.map((task) => [
                task.id,
                task,
            ]),
        );
        return rows.map((row) => {
            const held = prior.get(row.id)?.segments;
            return held ? { ...row, segments: held } : row;
        });
    });

    const syncTask = $(async () => {
        const rows = await getApi<any[]>("/download").catch(() => null);

        // A request that never landed leaves the list exactly as it is. The
        // list is the only record of what was running, and emptying it during
        // an outage throws away the very thing the connection indicator is
        // saying is merely stale.
        if (rows === null) return;

        const tasks = rows.map(normalizeApiTask);
        await noteTransitions(tasks);
        store.tasks = (await carrySegments(tasks)).slice(0, MAX_TASKS);
    });

    /** Fold rows from an SSE frame into the list, replacing what they match. */
    const mergeTasks = $(async (rows: UiTask[]) => {
        await noteTransitions(rows);
        const incoming = new Set(rows.map((row) => row.id));
        const kept = store.tasks.filter((t) => !incoming.has(t.id));
        store.tasks = [
            ...(await carrySegments(rows)),
            ...kept,
        ].slice(0, MAX_TASKS);
    });

    /**
     * Patch the numbers on one row in place, without a refetch.
     *
     * Every field falls back to what the row already holds. The API serialises
     * progress frames with `exclude_none`, so an absent `total_bytes` means
     * "unchanged", not "zero" — defaulting to 0 blanked the size mid-download.
     */
    const applyProgress = $((data: any) => {
        store.tasks = store.tasks.map((t) =>
            t.id === data.id
                ? {
                      ...t,
                      progress: data.progress ?? t.progress,
                      eta: data.eta_seconds ?? t.eta,
                      downloadedBytes:
                          data.downloaded_bytes ?? t.downloadedBytes,
                      totalBytes: data.total_bytes ?? t.totalBytes,
                      downloadSpeed: data.speed_bps ?? t.downloadSpeed,
                      segments: normalizeSegments(data) ?? t.segments,
                  }
                : t,
        );
    });

    useVisibleTask$(
        ({ cleanup }) => {
            syncTask();
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
                apiEvents = new EventSource(apiUrl("/download/events"));
                apiEvents.addEventListener("tasks", (event) => {
                    try {
                        const rows = JSON.parse(
                            (event as MessageEvent).data,
                        ) as any[];
                        mergeTasks(rows.map(normalizeApiTask));
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("task", (event) => {
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
                apiEvents.addEventListener("progress", (event) => {
                    try {
                        applyProgress(JSON.parse((event as MessageEvent).data));
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

    const handleFilterChange = $((filter: string) => {
        store.filter = filter as any;
    });

    const handleSearchChange = $((query: string) => {
        store.searchQuery = query;
    });

    const handleAddClick = $(() => {
        store.addModalOpen = true;
    });

    const handleTaskAction = $(
        async (taskId: string, action: "pause" | "resume") => {
            const task = store.tasks.find((t) => t.id === taskId);
            if (!task) return;

            try {
                const updated = await postApi<any>(
                    `/download/${taskId}/${action}`,
                    {},
                );
                if (updated) {
                    const row = normalizeApiTask(updated);
                    store.tasks = store.tasks.map((t) =>
                        t.id === taskId ? row : t,
                    );
                }
            } catch (err) {
                notify("error", errorMessage(err));
            }
        },
    );

    const handlePause = $((id: string) => handleTaskAction(id, "pause"));

    const handleResume = $((id: string) => handleTaskAction(id, "resume"));

    /** Open the dialog. Nothing is removed until it is confirmed. */
    const handleRemove = $((taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;
        store.removing = {
            id: task.id,
            title: task.title,
            status: task.status,
        };
    });

    const handleRemoveCancel = $(() => {
        store.removing = null;
    });

    const handleRemoveConfirm = $(
        async (taskId: string, deleteFiles: boolean) => {
            store.removing = null;

            try {
                await deleteApi(
                    `/download/${taskId}?delete_files=${deleteFiles}`,
                );
            } catch (err) {
                // The row still goes: the person asked for it gone, and a failure
                // here is nearly always a row the API has already forgotten.
                notify("error", errorMessage(err));
            }

            store.tasks = store.tasks.filter((t) => t.id !== taskId);
        },
    );

    const handleDownloadFile = $((taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        const a = document.createElement("a");
        a.href = apiUrl(`/download/${taskId}/file`);
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    });

    const handleAddModalClose = $(() => {
        store.addModalOpen = false;
    });

    const handlePlayClick = $((value: string, kind: string) => {
        store.playerUrl = value;
        store.playerKind = kind;
        store.playerModalOpen = true;
    });

    const handlePlayerModalClose = $(() => {
        store.playerModalOpen = false;
    });

    const handleResolveTorrent = $(
        (torrent: string): Promise<ResolvedTorrent> => {
            return resolveTorrent(torrent);
        },
    );

    const handleAdd = $(
        async (input: {
            type: "magnet" | "file" | "url";
            value: string;
            preset?: string;
            files?: number[];
        }) => {
            try {
                if (input.type === "url") {
                    const url = input.value.trim().toLowerCase();
                    const isYouTube =
                        url.includes("youtube.com") ||
                        url.includes("youtu.be") ||
                        url.includes("music.youtube.com");

                    // unwrap() throws with the service's own message on either
                    // envelope, so there is no response.ok check to write here.
                    await (isYouTube
                        ? postApi("/download/youtube", {
                              url: input.value,
                              preset: input.preset || "best",
                          })
                        : postApi("/download/url", { url: input.value }));
                } else {
                    await addTorrent(input.value, input.files ?? []);
                }
            } catch (err) {
                notify("error", errorMessage(err));
                // Rethrown so the modal keeps what was typed instead of
                // closing over a submission that never landed.
                throw err;
            }

            store.addModalOpen = false;
            syncTask();
        },
    );

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
            now={store.now}
            connection={store.connection}
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
            onPlayClick={handlePlayClick}
            onPlayerModalClose={handlePlayerModalClose}
            onPause={handlePause}
            onResume={handleResume}
            onDownloadFile={handleDownloadFile}
            onRemove={handleRemove}
            removing={store.removing}
            onRemoveCancel={handleRemoveCancel}
            onRemoveConfirm={handleRemoveConfirm}
            onAdd={handleAdd}
            onResolve={handleResolveTorrent}
            onStopSeeding={handleStopSeeding}
        />
    );
});
