import { component$, $ } from "@qwik.dev/core";
import {
    aggregateStats,
    canPause,
    canResume,
    isActive,
    isSeeding,
    type ResolvedTorrent,
    type TaskSummary,
    type UiTask,
} from "@/lib/api";
import { StatusBar } from "@/component/features/status-bar";
import { TopToolbar } from "@/component/layouts/top-toolbar";
import { TorrentList } from "@/component/features/torrent-list";
import { Sidebar, type SidebarFilter } from "@/component/layouts/sidebar";
import { AddTorrentModal } from "@/component/features/add-torrent-modal";
import { HeroInput } from "@/component/features/hero-input";
import { PlayerModal } from "@/component/features/player-modal";
import { RemoveDialog } from "@/component/features/remove-dialog";
import { ConfirmDialog } from "@/component/shared/confirm-dialog";
import { Toaster } from "@/component/shared/toast";
import type { Connection } from "@/lib/connection";
import type { SortValue } from "@/lib/sort";
import type { Toast } from "@/lib/toast";
import "./field.css";

export interface AppShellProps {
    tasks: UiTask[];
    filter: "all" | "downloading" | "seeding" | "completed";
    searchQuery: string;
    sort: SortValue;
    onSortChange: (sort: SortValue) => void;
    now: number;
    connection: Connection;
    summary: TaskSummary | null;
    page: number;
    totalPages: number;
    loadingMore: boolean;
    onLoadMore: () => void;
    toasts: Toast[];
    onDismissToast: (id: string) => void;
    sidebarOpen: boolean;
    sidebarCollapsed: boolean;
    addModalOpen: boolean;
    playerModalOpen: boolean;
    playerUrl: string;
    playerKind: string;
    onPlayClick: (value: string, kind: string) => void;
    onPlayerModalClose: () => void;
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
    removing: { id: string; title: string; status: string } | null;
    onRemoveCancel: () => void;
    onRemoveConfirm: (id: string, deleteFiles: boolean) => void;
    onBulk: (action: "pause_all" | "resume_all" | "clear_finished") => void;
    bulkPrompt: {
        heading: string;
        body: string;
        confirmLabel: string;
    } | null;
    onBulkCancel: () => void;
    onBulkConfirm: () => void;
    onStopSeeding: (id: string) => void;
    onAdd: (input: {
        type: "magnet" | "file" | "url";
        value: string;
        preset?: string;
        files?: number[];
    }) => void;
    onResolve: (torrent: string) => Promise<ResolvedTorrent>;
}

export const AppShell = component$<AppShellProps>(
    ({
        tasks,
        filter,
        searchQuery,
        sort,
        onSortChange,
        now,
        connection,
        summary,
        page,
        totalPages,
        loadingMore,
        onLoadMore,
        toasts,
        onDismissToast,
        sidebarOpen,
        sidebarCollapsed,
        addModalOpen,
        playerModalOpen,
        playerUrl,
        playerKind,
        onPlayClick,
        onPlayerModalClose,
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
        removing,
        onRemoveCancel,
        onRemoveConfirm,
        onBulk,
        bulkPrompt,
        onBulkCancel,
        onBulkConfirm,
        onStopSeeding,
        onAdd,
        onResolve,
    }) => {
        const noop = $(() => {});

        const stats = aggregateStats(tasks);

        // Counted by the API when it can be. Falling back to the loaded rows
        // keeps the numbers plausible before the first summary arrives, but
        // they are only ever a floor: the list is one page of many.
        // What each sweep would find, counted from the rows on screen. The
        // API decides for itself which rows an action applies to; this only
        // decides whether offering a button is worth the space. A row on an
        // unloaded page is not counted, so a button can be absent while the
        // sweep would still have found something — it errs towards quiet.
        const bulk = {
            pausable: tasks.filter((t) => canPause(t.status)).length,
            resumable: tasks.filter((t) => canResume(t.status)).length,
            finished: tasks.filter(
                (t) => t.status === "complete" || t.status === "failed",
            ).length,
        };

        const counts = summary ?? {
            all: tasks.length,
            downloading: tasks.filter((t) => isActive(t.status)).length,
            seeding: tasks.filter((t) => isSeeding(t.status)).length,
            completed: tasks.filter((t) => t.status === "complete").length,
        };

        return (
            <div class="app-shell" role="application">
                <TopToolbar
                    sort={sort}
                    onSortChange={onSortChange}
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
                        bulk={bulk}
                        onPauseAll={$(() => onBulk("pause_all"))}
                        onResumeAll={$(() => onBulk("resume_all"))}
                        onClearFinished={$(() => onBulk("clear_finished"))}
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
                            onPlay={$((value: string, kind: string) =>
                                onPlayClick(value, kind),
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
                                now={now}
                                hasMore={page < totalPages}
                                loadingMore={loadingMore}
                                onLoadMore={onLoadMore}
                                filter={filter}
                                searchQuery={searchQuery}
                                onPause={onPause}
                                onResume={onResume}
                                onDownloadFile={onDownloadFile}
                                onRemove={onRemove}
                                onStopSeeding={onStopSeeding}
                            />
                        </section>
                    </div>
                </div>

                <StatusBar
                    downloadSpeed={stats.downloadSpeed}
                    uploadSpeed={stats.uploadSpeed}
                    totalDownloaded={stats.totalDownloaded}
                    totalPeers={stats.totalPeers}
                    connection={connection}
                />

                <Toaster toasts={toasts} onDismiss={onDismissToast} />

                <ConfirmDialog
                    prompt={bulkPrompt}
                    onCancel={onBulkCancel}
                    onConfirm={onBulkConfirm}
                />

                <RemoveDialog
                    task={removing}
                    onCancel={onRemoveCancel}
                    onConfirm={onRemoveConfirm}
                />

                <AddTorrentModal
                    open={addModalOpen}
                    onClose={onAddModalClose}
                    onAdd={onAdd}
                    onResolve={onResolve}
                    onPlay={$((value: string, kind: string) =>
                        onPlayClick(value, kind),
                    )}
                />

                <PlayerModal
                    open={playerModalOpen}
                    url={playerUrl}
                    kind={playerKind}
                    onClose={onPlayerModalClose}
                />
            </div>
        );
    },
);
