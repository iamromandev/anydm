import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import "../style/global.css";
import { AppShell } from "@/component/layouts/app-shell";

const MAX_TASKS = 50;

function getBaseUrl(): string {
    return (
        import.meta.env.PUBLIC_BASE_URL ||
        (import.meta.env.DEV ? "http://localhost:3000" : "")
    );
}

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

    const getGlobalStats = $((payload: any) => {
        const gs = payload.globalStats;
        return gs
            ? {
                  downloadSpeed: gs.downloadSpeed ?? 0,
                  uploadSpeed: gs.uploadSpeed ?? 0,
                  totalDownloaded: gs.totalDownloaded ?? 0,
                  totalPeers: gs.totalPeers ?? 0,
              }
            : {
                  downloadSpeed: 0,
                  uploadSpeed: 0,
                  totalDownloaded: 0,
                  totalPeers: 0,
              };
    });

    const syncTask = $(async () => {
        const BASE_URL = getBaseUrl();
        if (!BASE_URL) return;

        try {
            const response = await fetch(`${BASE_URL}/download`);
            const payload = await response.json();
            if (!payload.success) return;

            const serverMap = new Map<string, any>(
                payload.data.map((t: any) => [
                    t.id,
                    t,
                ]),
            );

            let changed = false;
            store.tasks = store.tasks.map((t) => {
                const server = serverMap.get(t.id);
                if (
                    server &&
                    (server.status !== t.status ||
                        server.progress !== t.progress ||
                        server.error !== t.error ||
                        server.downloadSpeed !== t.downloadSpeed ||
                        server.uploadSpeed !== t.uploadSpeed)
                ) {
                    changed = true;
                    return { ...t, ...server };
                }
                return t;
            });

            if (!changed) {
                store.tasks = payload.data.slice(0, MAX_TASKS);
            }

            store.globalStats = await getGlobalStats(payload);
        } catch {
            // poll failures are silent
        }
    });

    useVisibleTask$(
        ({ cleanup }) => {
            syncTask();
            const interval = setInterval(syncTask, 2500);

            let eventSource: EventSource | null = null;
            try {
                eventSource = new EventSource(
                    `${getBaseUrl()}/download/torrent/events`,
                );
                eventSource.addEventListener("stats", (event) => {
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
                eventSource.onerror = () => {
                    // EventSource reconnects automatically
                };
            } catch {
                eventSource = null;
            }

            cleanup(() => {
                clearInterval(interval);
                eventSource?.close();
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

    const handleTorrentAction = $(
        async (taskId: string, action: "pause" | "resume") => {
            const BASE_URL = getBaseUrl();
            if (!BASE_URL) return;

            try {
                const response = await fetch(
                    `${BASE_URL}/download/torrent/${taskId}/${action}`,
                    { method: "POST" },
                );
                const payload = await response.json();
                if (!response.ok || !payload.success) {
                    throw new Error(payload.error || "Torrent action failed");
                }
                if (payload.data) {
                    store.tasks = store.tasks.map((t) =>
                        t.id === taskId ? { ...t, ...payload.data } : t,
                    );
                }
            } catch (err) {
                console.error(err);
            }
        },
    );

    const handlePause = $((id: string) => handleTorrentAction(id, "pause"));

    const handleResume = $((id: string) => handleTorrentAction(id, "resume"));

    const handleRemove = $(async (taskId: string) => {
        const BASE_URL = getBaseUrl();
        if (!BASE_URL) return;

        const task = store.tasks.find((t) => t.id === taskId);
        const active =
            task?.status === "downloading" || task?.status === "seeding";
        if (active && !confirm("Stop and remove this download?")) return;

        try {
            if (task?.kind === "torrent") {
                await fetch(`${BASE_URL}/download/torrent/${taskId}`, {
                    method: "DELETE",
                });
            }
        } catch (err) {
            console.error(err);
        }

        store.tasks = store.tasks.filter((t) => t.id !== taskId);
    });

    const handleDownloadFile = $((taskId: string) => {
        const BASE_URL = getBaseUrl();
        if (!BASE_URL) return;

        const a = document.createElement("a");
        a.href = `${BASE_URL}/download/${taskId}/file`;
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
            const BASE_URL = getBaseUrl();
            if (!BASE_URL) {
                // no-op when running without API
                store.addModalOpen = false;
                return;
            }

            if (input.type === "url") {
                // determine youtube vs generic URL
                const url = input.value.trim().toLowerCase();
                const isYouTube =
                    url.includes("youtube.com") ||
                    url.includes("youtu.be") ||
                    url.includes("music.youtube.com");

                const endpoint = isYouTube
                    ? `${BASE_URL}/download/youtube`
                    : `${BASE_URL}/download/url`;
                const body = isYouTube
                    ? { url: input.value, preset: input.preset || "best" }
                    : { url: input.value };

                const response = await fetch(endpoint, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(body),
                });

                const payload = await response.json();
                if (!response.ok || !payload.success) {
                    throw new Error(
                        payload.error || "Failed to start download",
                    );
                }
            } else {
                const response = await fetch(`${BASE_URL}/download/torrent`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ torrent: input.value }),
                });

                const payload = await response.json();
                if (!response.ok || !payload.success) {
                    throw new Error(payload.error || "Failed to start torrent");
                }
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
