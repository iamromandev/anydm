import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import "../style/global.css";
import { AppShell } from "@/component/layouts/app-shell";
import {
    apiUrl,
    deleteApi,
    getApi,
    isActive,
    normalizeApiTask,
    postApi,
    type UiTask,
} from "@/lib/api";

const MAX_TASKS = 50;

export default component$(() => {
    const store = useStore({
        tasks: [] as UiTask[],
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
        const tasks = await getApi<any[]>("/download")
            .then((rows) => rows.map(normalizeApiTask))
            .catch(() => [] as UiTask[]);

        store.tasks = tasks.slice(0, MAX_TASKS);
    });

    /** Fold rows from an SSE frame into the list, replacing what they match. */
    const mergeTasks = $((rows: UiTask[]) => {
        const incoming = new Set(rows.map((row) => row.id));
        const kept = store.tasks.filter((t) => !incoming.has(t.id));
        store.tasks = [
            ...rows,
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
                  }
                : t,
        );
    });

    useVisibleTask$(
        ({ cleanup }) => {
            syncTask();
            const interval = setInterval(syncTask, 2500);

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
                apiEvents.onerror = () => {
                    // EventSource reconnects automatically
                };
            } catch {
                apiEvents = null;
            }

            cleanup(() => {
                clearInterval(interval);
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
                console.error(err);
            }
        },
    );

    const handlePause = $((id: string) => handleTaskAction(id, "pause"));

    const handleResume = $((id: string) => handleTaskAction(id, "resume"));

    const handleRemove = $(async (taskId: string) => {
        const task = store.tasks.find((t) => t.id === taskId);
        if (!task) return;

        if (isActive(task.status) && !confirm("Stop and remove this download?"))
            return;

        try {
            await deleteApi(`/download/${taskId}`);
        } catch (err) {
            console.error(err);
        }

        store.tasks = store.tasks.filter((t) => t.id !== taskId);
    });

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
                    // Torrents are not ported yet. This route will exist on the
                    // same service when they are; until then the API answers
                    // 404 and the modal surfaces it.
                    await postApi("/download/torrent", {
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
