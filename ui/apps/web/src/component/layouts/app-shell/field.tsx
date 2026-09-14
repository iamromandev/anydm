import { component$, $ } from "@qwik.dev/core";
import { isActive, type UiTask } from "@/lib/api";
import { StatusBar } from "@/component/features/status-bar";
import { TopToolbar } from "@/component/layouts/top-toolbar";
import { TorrentList } from "@/component/features/torrent-list";
import { Sidebar, type SidebarFilter } from "@/component/layouts/sidebar";
import { AddTorrentModal } from "@/component/features/add-torrent-modal";
import { HeroInput } from "@/component/features/hero-input";
import "./field.css";

export interface GlobalStats {
    downloadSpeed: number;
    uploadSpeed: number;
    totalDownloaded: number;
    totalPeers: number;
}

export interface AppShellProps {
    tasks: UiTask[];
    filter: "all" | "downloading" | "seeding" | "completed";
    searchQuery: string;
    globalStats: GlobalStats | null;
    sidebarOpen: boolean;
    sidebarCollapsed: boolean;
    addModalOpen: boolean;
    onSidebarToggle: () => void;
    onSidebarCollapseToggle: () => void;
    onFilterChange: (filter: string) => void;
    onSearchChange: (query: string) => void;
    onAddModalClose: () => void;
    onAddClick: () => void;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onDownloadFile: (id: string) => void;
    onRemove: (id: string) => void;
    onAdd: (input: {
        type: "magnet" | "file" | "url";
        value: string;
        preset?: string;
    }) => void;
}

export const AppShell = component$<AppShellProps>(
    ({
        tasks,
        filter,
        searchQuery,
        globalStats,
        sidebarOpen,
        sidebarCollapsed,
        addModalOpen,
        onSidebarToggle,
        onSidebarCollapseToggle,
        onFilterChange,
        onSearchChange,
        onAddModalClose,
        onAddClick,
        onPause,
        onResume,
        onDownloadFile,
        onRemove,
        onAdd,
    }) => {
        const noop = $(() => {});

        const stats: GlobalStats = globalStats ?? {
            downloadSpeed: 0,
            uploadSpeed: 0,
            totalDownloaded: 0,
            totalPeers: 0,
        };

        const counts = {
            all: tasks.length,
            downloading: tasks.filter((t) => isActive(t.status)).length,
            // Seeding arrives with the torrent port; nothing reaches it yet.
            seeding: 0,
            completed: tasks.filter((t) => t.status === "complete").length,
        };

        return (
            <div class="app-shell" role="application">
                <TopToolbar
                    onAddClick={onAddClick}
                    onSettingsClick={noop}
                    sidebarOpen={sidebarOpen}
                    onSidebarToggle={onSidebarToggle}
                />

                <div class="app-shell-main">
                    <Sidebar
                        filter={filter as SidebarFilter}
                        onFilterChange={onFilterChange}
                        counts={counts}
                        collapsed={sidebarCollapsed}
                        open={sidebarOpen}
                        onToggleCollapse={onSidebarCollapseToggle}
                    />

                    <div class="app-shell-content">
                        <HeroInput
                            onSubmit={$(
                                async (input: {
                                    type: "magnet" | "file" | "url";
                                    value: string;
                                    preset?: string;
                                }) => {
                                    await onAdd(input);
                                },
                            )}
                        />

                        <section class="app-shell-list" aria-label="Downloads">
                            <div class="app-shell-list-header">
                                <h2 class="app-shell-list-title">
                                    {filter === "all" && "Recent downloads"}
                                    {filter === "downloading" &&
                                        "Active downloads"}
                                    {filter === "seeding" && "Seeding"}
                                    {filter === "completed" && "Completed"}
                                </h2>
                                {searchQuery && (
                                    <span class="app-shell-search-hint">
                                        Searching for “{searchQuery}”
                                    </span>
                                )}
                            </div>

                            <TorrentList
                                tasks={tasks}
                                filter={filter}
                                searchQuery={searchQuery}
                                onPause={onPause}
                                onResume={onResume}
                                onDownloadFile={onDownloadFile}
                                onRemove={onRemove}
                            />
                        </section>
                    </div>
                </div>

                <StatusBar stats={stats} />

                <AddTorrentModal
                    open={addModalOpen}
                    onClose={onAddModalClose}
                    onAdd={onAdd}
                />
            </div>
        );
    },
);
