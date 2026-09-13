import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import "../style/global.css";
import { AppShell } from "@/component/layouts/app-shell";
import {
    apiUrl,
    bunUrl,
    deleteApi,
    deleteBun,
    getApi,
    getBun,
    normalizeApiTask,
    normalizeBunTask,
    postApi,
    postBun,
    type UiTask,
} from "@/lib/api";

const MAX_TASKS = 50;

export default component$(() => {
    const store = useStore({
        tasks: [] as any[],
        filter: "all" as "all" | "downloading" | "seeding" | "completed",
        searchQuery: "" as string,
        globalStats: null as {
            downloadSpeed: number;
            uploadSpeed: number;
            totalDownloaded: number;
            totalPeers: number;
        } | null,
        sidebarOpen: false as boolean,
        sidebarCollapsed: true as boolean,
        addModalOpen: false as boolean,
    });

    const syncTask = $(async () => {
        // Downloads come from FastAPI, torrents from the Bun service. Each is
        // caught separately so one being down cannot blank the other's rows.
        //
        // The Bun list is GET /download, not /download/torrent: that router
        // registers its list at "/" and, mounted under a prefix, the path is
        // unreachable — it falls through to /:id and answers "Task not found".
        // Filtering by kind is what narrows it to torrents now that YouTube
        // tasks live in FastAPI.
        const [
            apiTasks,
            bunTasks,
            bunStats,
        ] = await Promise.all([
            getApi<any[]>("/download")
                .then((rows) => rows.map(normalizeApiTask))
                .catch(() => [] as UiTask[]),
            getBun<any[]>("/download")
                .then((rows) =>
                    rows
                        .filter((row) => row.kind === "torrent")
                        .map(normalizeBunTask),
                )
                .catch(() => [] as UiTask[]),
            getBun<any>("/download/torrent/global/stats").catch(() => null),
        ]);

        store.tasks = [
            ...apiTasks,
            ...bunTasks,
        ].slice(0, MAX_TASKS);

        // Mapped inline rather than through a $() helper: calling one QRL
        // from inside another loses the closure capture, and `store` arrives
        // undefined in the extracted chunk.
        store.globalStats = {
            downloadSpeed: bunStats?.downloadSpeed ?? 0,
            uploadSpeed: bunStats?.uploadSpeed ?? 0,
            totalDownloaded: bunStats?.totalDownloaded ?? 0,
            totalPeers: bunStats?.totalPeers ?? 0,
        };
    });

    /** Replace every task from one service, leaving the other's rows alone. */
    const mergeTasks = $((rows: UiTask[], source: "api" | "bun") => {
        const others = store.tasks.filter((t) => t.source !== source);
        store.tasks = [
            ...rows,
            ...others,
        ].slice(0, MAX_TASKS);
    });

    /** Patch the numbers on one row in place, without a refetch. */
    const applyProgress = $((data: any) => {
        store.tasks = store.tasks.map((t) =>
            t.id === data.id
                ? {
                      ...t,
                      progress: data.progress ?? t.progress,
                      eta: data.eta_seconds ?? 0,
                      progressDetails: {
                          ...t.progressDetails,
                          downloadedBytes: data.downloaded_bytes ?? 0,
                          totalBytes: data.total_bytes ?? 0,
                          downloadSpeed: data.speed_bps ?? 0,
                          eta: data.eta_seconds ?? 0,
                      },
                  }
                : t,
        );
    });

    useVisibleTask$(
        ({ cleanup }) => {
            syncTask();
            const interval = setInterval(syncTask, 2500);

            let apiEvents: EventSource | null = null;
            let bunEvents: EventSource | null = null;

            try {
                apiEvents = new EventSource(apiUrl("/download/events"));
                apiEvents.addEventListener("tasks", (event) => {
                    try {
                        const rows = JSON.parse(
                            (event as MessageEvent).data,
                        ) as any[];
                        mergeTasks(rows.map(normalizeApiTask), "api");
                    } catch {
                        // malformed event
                    }
                });
                apiEvents.addEventListener("task", (event) => {
                    try {
                        mergeTasks(
                            [
                                normalizeApiTask(
                                    JSON.parse((event as MessageEvent).data),
                                ),
                            ],
                            "api",
                        );
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
                apiEvents.onerror = () => {
                    // EventSource reconnects automatically
                };
            } catch {
                apiEvents = null;
            }

            try {
                bunEvents = new EventSource(bunUrl("/download/torrent/events"));
                bunEvents.addEventListener("stats", (event) => {
                    try {
                        const data = JSON.parse(
                            (event as MessageEvent).data,
                        ) as any;
                        if (data && typeof data === "object") {
                            store.globalStats = {
                                downloadSpeed: data.downloadSpeed ?? 0,
                                uploadSpeed: data.uploadSpeed ?? 0,
                                totalDownloaded: data.totalDownloaded ?? 0,
                                totalPeers: data.totalPeers ?? 0,
                            };
                        }
                    } catch {
                        // malformed event
                    }
                });
                bunEvents.onerror = () => {
                    // EventSource reconnects automatically
                };
            } catch {
                bunEvents = null;
            }

            cleanup(() => {
                clearInterval(interval);
                apiEvents?.close();
                bunEvents?.close();
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
                // Each task goes back to the service that owns it. The `source`
                // tag the normalizers set is what makes that a lookup rather
                // than a guess about kind.
                const updated =
                    task.source === "api"
                        ? await postApi<any>(
                              `/download/${taskId}/${action}`,
                              {},
                          )
                        : await postBun<any>(
                              `/download/torrent/${taskId}/${action}`,
                          );
                if (updated) {
                    const row =
                        task.source === "api"
                            ? normalizeApiTask(updated)
                            : normalizeBunTask(updated);
                    store.tasks = store.tasks.map((t) =>
                        t.id === taskId ? row : t,
                    );
                }
            } catch (err) {
                console.error(err);
            }
        },
    );

    const handlePause = $((id: string) => handleTaskAction(id, "pause"));

    const handleResume = $((id: string) => handleTaskAction(id, "resume"));

    const handleRemove = $(async (taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        const active =
            task.status === "downloading" || task.status === "seeding";
        if (active && !confirm("Stop and remove this download?")) return;

        try {
            if (task.source === "api") {
                await deleteApi(`/download/${taskId}`);
            } else {
                await deleteBun(`/download/torrent/${taskId}`);
            }
        } catch (err) {
            console.error(err);
        }

        store.tasks = store.tasks.filter((t) => t.id !== taskId);
    });

    const handleDownloadFile = $((taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        const path = `/download/${taskId}/file`;
        const a = document.createElement("a");
        a.href = task.source === "api" ? apiUrl(path) : bunUrl(path);
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    });

    const handleAddModalClose = $(() => {
        store.addModalOpen = false;
    });

    const handleAdd = $(
        async (input: {
            type: "magnet" | "file" | "url";
            value: string;
            preset?: string;
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
                    await postBun("/download/torrent", {
                        torrent: input.value,
                    });
                }
            } catch (err) {
                console.error(err);
                throw err;
            }

            store.addModalOpen = false;
            syncTask();
        },
    );

    return (
        <AppShell
            tasks={store.tasks}
            filter={store.filter}
            searchQuery={store.searchQuery}
            globalStats={store.globalStats}
            sidebarOpen={store.sidebarOpen}
            sidebarCollapsed={store.sidebarCollapsed}
            onSidebarToggle={toggleSidebar}
            onSidebarCollapseToggle={toggleSidebarCollapse}
            onFilterChange={handleFilterChange}
            onSearchChange={handleSearchChange}
            addModalOpen={store.addModalOpen}
            onAddModalClose={handleAddModalClose}
            onAddClick={handleAddClick}
            onPause={handlePause}
            onResume={handleResume}
            onDownloadFile={handleDownloadFile}
            onRemove={handleRemove}
            onAdd={handleAdd}
        />
    );
});
