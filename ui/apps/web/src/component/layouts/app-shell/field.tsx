import { useState } from "react";
import { usePatch } from "@/hook/use-patch";
import {
    aggregateStats,
    canPause,
    canResume,
    duplicateOf,
    isActive,
    isSeeding,
    type AddInput,
    type CategoryItem,
    type EntriesView,
    type ResolvedTorrent,
    type TaskSummary,
    type UiTask,
} from "@/lib/api";
import { StatusBar } from "@/component/features/status-bar";
import { TopToolbar } from "@/component/layouts/top-toolbar";
import { TorrentList } from "@/component/features/torrent-list";
import { Sidebar, type SidebarFilter } from "@/component/layouts/sidebar";
import { AddTorrentModal } from "@/component/features/add-torrent-modal";
import { BatchModal } from "@/component/features/batch-modal";
import {
    SearchView,
    emptySearch,
    type SearchState,
} from "@/component/features/search-view";
import {
    SourceView,
    emptySourceView,
    type SourceViewState,
} from "@/component/features/source-view";
import {
    StatsView,
    emptyStatsView,
    rangeDates,
    type RangeId,
    type StatsViewState,
} from "@/component/features/stats-view";
import { getStats } from "@/lib/api/stats";
import {
    SourceModal,
    type SourceModalMode,
} from "@/component/features/source-modal";
import type { AddInitial } from "@/lib/api/search";
import {
    deleteSource,
    listSources,
    resetSource,
    testSource,
    updateSource,
    type SourceItem,
} from "@/lib/api/source";
import { errorMessage } from "@/lib/toast";
import type { PlayableFile } from "@/lib/media";
import type { PositionView } from "@/lib/api";
import { HeroInput } from "@/component/features/hero-input";
import { StatusLine } from "@/component/features/status-line";
import { PlayerModal } from "@/component/features/player-modal";
import { PlaylistPicker } from "@/component/features/playlist-picker";
import { CategoryMove } from "@/component/features/category-move";
import { RemoveDialog } from "@/component/features/remove-dialog";
import {
    SettingsModal,
    type ServerSettings,
} from "@/component/features/settings-modal";
import { ConfirmDialog } from "@/component/shared/confirm-dialog";
import { Toaster } from "@/component/shared/toast";
import type { Disk } from "@/lib/api/disk";
import type { PickerTarget, PlaylistRequest } from "@/lib/api/playlist";
import type { QueueItem } from "@/lib/queue";
import type { Connection } from "@/lib/connection";
import type { Prefs } from "@/lib/prefs";
import type { SortValue } from "@/lib/sort";
import type { Toast } from "@/lib/toast";
import "./field.css";

export interface AppShellProps {
    tasks: UiTask[];
    filter: "all" | "downloading" | "seeding" | "completed";
    searchQuery: string;
    sort: SortValue;
    onSortChange: (sort: SortValue) => void;
    settingsOpen: boolean;
    onSettingsOpen: () => void;
    onSettingsClose: () => void;
    prefs: Prefs;
    onPrefsChange: (prefs: Prefs) => void;
    serverSettings: ServerSettings | null;
    apiKey: string;
    apiKeyMessage: string | null;
    onApiKeySave: (key: string) => void;
    now: number;
    connection: Connection;
    disk: Disk | null;
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
    /** A finished download to play instead of a link, and a torrent's file. */
    playerTaskId: string;
    playerFileIndex: number | null;
    /** A torrent's media files, for the player's file menu (#98). */
    playerFiles: PlayableFile[];
    /** The task is a torrent still downloading, played from its stream (#95). */
    playerFromTorrent: boolean;
    /** Where the task's files were left, and where a save goes (#96). */
    playerPositions: PositionView[];
    onPositionSaved: (taskId: string, position: PositionView) => void;
    /** Play a link; for a torrent, which file and the files to switch between (#98). */
    onPlayClick: (
        value: string,
        kind: string,
        fileIndex?: number | null,
        files?: PlayableFile[],
    ) => void;
    onPlayerModalClose: () => void;
    onSidebarToggle: () => void;
    onSidebarCollapseToggle: () => void;
    onFilterChange: (filter: string) => void;
    /** Every category, for the sidebar, the add forms and the Settings section. */
    categories: CategoryItem[];
    /** The sidebar's category filter: an id, or null for every category. */
    category: string | null;
    /** Where the add forms save new downloads. */
    addCategory: string;
    onCategoryChange: (id: string | null) => void;
    onAddCategoryChange: (id: string) => void;
    /** Move a row to another category; the dialog closes once it has answered. */
    onMoveCategory: (taskId: string, categoryId: string) => Promise<void>;
    onCategoryCreate: (name: string, folder: string) => Promise<boolean>;
    onCategoryUpdate: (
        id: string,
        patch: { name?: string; folder?: string },
    ) => Promise<boolean>;
    onCategoryOrder: (ids: string[]) => Promise<void>;
    onCategoryDelete: (id: string) => Promise<void>;
    onSearchChange: (query: string) => void;
    onAddModalClose: () => void;
    onAddClick: () => void;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onDownloadFile: (id: string, fileIndex?: number) => void;
    /** Play a finished download (#94). */
    onPlay: (id: string) => void;
    onRemove: (id: string) => void;
    removing: {
        id: string;
        title: string;
        status: string;
        videos?: number;
    } | null;
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
    onAdd: (input: AddInput) => void;
    /** Bring a download the list already holds into view, outlined. */
    onOpenDownload: (id: string, collectionId?: string) => void;
    /** The row Open just brought into view. */
    highlightId: string;
    onResolve: (torrent: string) => Promise<ResolvedTorrent>;
    /** Add a playlist's ticked videos as one group (v0.5). */
    onAddPlaylist: (request: PlaylistRequest) => Promise<void>;
    /** After a batch of links was added: the counts are asked for again. */
    onBatchAdded: () => void;
    /** Each open group's Entries list, by group id (v0.5). */
    entries: Record<string, EntriesView>;
    onToggleEntries: (id: string) => void;
    onLoadMoreEntries: (id: string) => void;
    onPauseVideo: (id: string) => void;
    onResumeVideo: (id: string) => void;
    onRemoveVideo: (groupId: string, id: string) => void;
    /** Play all (part 4): a picker's ticked videos, or a group's. */
    onPlayQueue: (items: QueueItem[], start: number) => void;
    onPlayGroup: (id: string) => void;
    /** Where the play queue stands, for the player's Prev, Next and up-next. */
    playerHasPreviousItem: boolean;
    playerHasNextItem: boolean;
    playerNextItemTitle: string;
    onNextItem: () => void;
    onPreviousItem: () => void;
    /** Search: shown when the API has torrent sources or can search YouTube. */
    searchEnabled: boolean;
    /** Which of the two the Search view has; it shows a tab for each that is on. */
    searchTorrents: boolean;
    searchYoutube: boolean;
    /** Re-read search availability; run after every source mutation. */
    onSourcesChanged: () => void;
    /** What the add dialog opens on; a new id remounts it. */
    addInitial: (AddInitial & { id: number }) | null;
    onAddFound: (initial: AddInitial) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

export function AppShell({
    tasks,
    filter,
    searchQuery,
    sort,
    onSortChange,
    settingsOpen,
    onSettingsOpen,
    onSettingsClose,
    prefs,
    onPrefsChange,
    serverSettings,
    apiKey,
    apiKeyMessage,
    onApiKeySave,
    now,
    connection,
    disk,
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
    playerTaskId,
    playerFileIndex,
    playerFiles,
    playerFromTorrent,
    playerPositions,
    onPositionSaved,
    onPlayClick,
    onPlayerModalClose,
    onSidebarToggle,
    onSidebarCollapseToggle,
    onFilterChange,
    categories,
    category,
    addCategory,
    onCategoryChange,
    onAddCategoryChange,
    onMoveCategory,
    onCategoryCreate,
    onCategoryUpdate,
    onCategoryOrder,
    onCategoryDelete,
    onSearchChange,
    onAddModalClose,
    onAddClick,
    onPause,
    onResume,
    onDownloadFile,
    onPlay,
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
    onOpenDownload,
    highlightId,
    onResolve,
    onAddPlaylist,
    onBatchAdded,
    entries,
    onToggleEntries,
    onLoadMoreEntries,
    onPauseVideo,
    onResumeVideo,
    onRemoveVideo,
    onPlayQueue,
    onPlayGroup,
    playerHasPreviousItem,
    playerHasNextItem,
    playerNextItemTitle,
    onNextItem,
    onPreviousItem,
    searchEnabled,
    searchTorrents,
    searchYoutube,
    onSourcesChanged,
    addInitial,
    onAddFound,
    onNotify,
}: AppShellProps) {
    const stats = aggregateStats(tasks);

    /** The list the picker is open on, if any. Held here, not in the
            add box: `.app-shell-content` has `contain: layout`, which would
            pin a fixed overlay to the content column. */
    const [
        picker,
        setPicker,
    ] = useState<PickerTarget | null>(null);
    // The category waiting on its delete confirmation, if any.
    const [
        confirmingCategory,
        setConfirmingCategory,
    ] = useState<CategoryItem | null>(null);
    // The row whose category is being changed, while its dialog is open.
    const [
        moving,
        setMoving,
    ] = useState<string | null>(null);

    /** The list, Search, Sources, or Stats. Held here with Search's own state, so the
            query and results survive switching away and back. */
    const [
        view,
        setView,
    ] = useState<"list" | "search" | "source" | "stats">("list");
    const [
        search,
        patchSearch,
    ] = usePatch<SearchState>(emptySearch);
    const [
        sourceView,
        patchSourceView,
    ] = usePatch<SourceViewState>(emptySourceView);
    const [
        sourcesLoaded,
        setSourcesLoaded,
    ] = useState(false);
    const [
        statsView,
        patchStatsView,
    ] = usePatch<StatsViewState>(emptyStatsView);
    /** Read a range's statistics: the days, the providers and the kinds. */
    const loadStats = async (range: RangeId) => {
        patchStatsView({ range: range });
        patchStatsView({ loading: true });
        patchStatsView({ failure: null });
        const { start, end } = rangeDates(range, new Date());
        try {
            const [
                days,
                providers,
                kinds,
            ] = await Promise.all([
                getStats(start, end, "day"),
                getStats(start, end, "provider"),
                getStats(start, end, "kind"),
            ]);
            patchStatsView({ days: days });
            patchStatsView({ providers: providers });
            patchStatsView({ kinds: kinds });
        } catch (err) {
            patchStatsView({ failure: errorMessage(err) });
        } finally {
            patchStatsView({ loading: false });
        }
    };
    /** The add/edit dialog Task 9 renders; set here so the row buttons work. */
    const [
        sourceDialog,
        setSourceDialog,
    ] = useState<SourceModalMode | null>(null);
    /**
     * The dialog for adding many links, and what it opens with: the lines
     * pasted into the add box, or nothing from the menu. ``seq`` remounts it
     * for each opening, so a new paste starts a new dialog.
     */
    const [
        batch,
        setBatch,
    ] = useState<{
        text: string;
        seq: number;
    } | null>(null);
    const openBatch = (text: string) =>
        setBatch((held) => ({ text, seq: (held?.seq ?? 0) + 1 }));

    const clearBusy = (id: string) => {
        patchSourceView((held) => {
            const busy = { ...held.busy };
            delete busy[id];
            return { busy };
        });
    };

    const loadSources = async () => {
        patchSourceView({ loading: true });
        patchSourceView({ failure: null });
        try {
            patchSourceView({ items: await listSources() });
            setSourcesLoaded(true);
        } catch (err) {
            patchSourceView({ failure: errorMessage(err) });
        } finally {
            patchSourceView({ loading: false });
        }
    };

    const handleSourceToggle = async (id: string, enabled: boolean) => {
        patchSourceView((held) => ({
            busy: { ...held.busy, [id]: "Saving…" },
        }));
        try {
            const updated = await updateSource(id, { enabled });
            patchSourceView((held) => ({
                items: held.items.map((s) => (s.id === id ? updated : s)),
            }));
            await onSourcesChanged();
        } catch (err) {
            onNotify("error", errorMessage(err));
        } finally {
            await clearBusy(id);
        }
    };

    const handleSourceTest = async (id: string) => {
        patchSourceView((held) => ({
            busy: { ...held.busy, [id]: "Testing…" },
        }));
        try {
            const result = await testSource(id);
            patchSourceView((held) => ({
                lastTest: { ...held.lastTest, [id]: result },
            }));
        } catch (err) {
            onNotify("error", errorMessage(err));
        } finally {
            await clearBusy(id);
        }
    };

    const handleSourceReset = async (id: string) => {
        patchSourceView((held) => ({
            busy: { ...held.busy, [id]: "Resetting…" },
        }));
        try {
            const updated = await resetSource(id);
            patchSourceView((held) => ({
                items: held.items.map((s) => (s.id === id ? updated : s)),
            }));
            await onSourcesChanged();
        } catch (err) {
            onNotify("error", errorMessage(err));
        } finally {
            await clearBusy(id);
        }
    };

    const handleSourceDelete = (source: SourceItem) => {
        patchSourceView({ confirmingDelete: source });
    };

    const handleSourceDeleteCancel = () => {
        patchSourceView({ confirmingDelete: null });
    };

    const handleSourceDeleteConfirm = async () => {
        const target = sourceView.confirmingDelete;
        if (!target) return;
        patchSourceView({ confirmingDelete: null });
        patchSourceView((held) => ({
            busy: { ...held.busy, [target.id]: "Deleting…" },
        }));
        try {
            await deleteSource(target.id);
            patchSourceView((held) => ({
                items: held.items.filter((s) => s.id !== target.id),
            }));
            await onSourcesChanged();
        } catch (err) {
            onNotify("error", errorMessage(err));
        } finally {
            await clearBusy(target.id);
        }
    };

    const handleSourceSaved = async (saved: SourceItem) => {
        setSourceDialog(null);
        const known = sourceView.items.some((s) => s.id === saved.id);
        patchSourceView((held) => ({
            items: known
                ? held.items.map((s) => (s.id === saved.id ? saved : s))
                : [
                      ...held.items,
                      saved,
                  ],
        }));
        await onSourcesChanged();
    };

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
            (t) => t.status === "completed" || t.status === "failed",
        ).length,
    };

    const counts = summary ?? {
        all: tasks.length,
        downloading: tasks.filter((t) => isActive(t.status)).length,
        seeding: tasks.filter((t) => isSeeding(t.status)).length,
        completed: tasks.filter((t) => t.status === "completed").length,
    };

    return (
        <div className="app-shell" role="application">
            <TopToolbar
                searchQuery={searchQuery}
                onSearchChange={onSearchChange}
                sort={sort}
                onSortChange={onSortChange}
                onAddClick={onAddClick}
                onAddManyClick={() => openBatch("")}
                onSettingsClick={onSettingsOpen}
                sidebarOpen={sidebarOpen}
                onSidebarToggle={onSidebarToggle}
            />

            <div className="app-shell-main">
                <Sidebar
                    filter={filter as SidebarFilter}
                    onFilterChange={(f: SidebarFilter) => {
                        setView("list");
                        onFilterChange(f);
                    }}
                    categories={categories}
                    category={category}
                    onCategoryChange={(id: string | null) => {
                        setView("list");
                        onCategoryChange(id);
                    }}
                    search={{
                        enabled: searchEnabled,
                        active: view === "search",
                        onOpen: () => {
                            setView("search");
                        },
                    }}
                    sources={{
                        active: view === "source",
                        onOpen: async () => {
                            setView("source");
                            if (!sourcesLoaded) await loadSources();
                        },
                    }}
                    stats={{
                        active: view === "stats",
                        onOpen: async () => {
                            setView("stats");
                            // Read again on every opening: the numbers move while it is away.
                            await loadStats(statsView.range);
                        },
                    }}
                    counts={counts}
                    bulk={bulk}
                    onPauseAll={() => onBulk("pause_all")}
                    onResumeAll={() => onBulk("resume_all")}
                    onClearFinished={() => onBulk("clear_finished")}
                    collapsed={sidebarCollapsed}
                    open={sidebarOpen}
                    onToggleCollapse={onSidebarCollapseToggle}
                />

                <div className="app-shell-content">
                    {view === "stats" ? (
                        <StatsView state={statsView} onRange={loadStats} />
                    ) : view === "source" ? (
                        <SourceView
                            state={sourceView}
                            onToggle={handleSourceToggle}
                            onTest={handleSourceTest}
                            onEdit={(source: SourceItem) => {
                                setSourceDialog({
                                    type: "edit",
                                    source,
                                });
                            }}
                            onDelete={handleSourceDelete}
                            onAdd={() => {
                                setSourceDialog({ type: "add" });
                            }}
                            onReset={handleSourceReset}
                        />
                    ) : view === "search" && searchEnabled ? (
                        <SearchView
                            state={search}
                            patch={patchSearch}
                            torrents={searchTorrents}
                            youtube={searchYoutube}
                            onAdd={onAddFound}
                            onAddVideo={async (url: string) => {
                                try {
                                    await onAdd({
                                        type: "link",
                                        value: url,
                                    });
                                } catch (err) {
                                    // Search has no add box to answer in,
                                    // so a duplicate is a toast here.
                                    if (
                                        duplicateOf(err) &&
                                        err instanceof Error
                                    )
                                        onNotify("error", err.message);
                                    throw err;
                                }
                            }}
                            onPlay={onPlayClick}
                            onNotify={onNotify}
                        />
                    ) : (
                        <>
                            <HeroInput
                                compact={counts.all > 0}
                                defaultPreset={prefs.defaultPreset}
                                categories={categories}
                                addCategory={addCategory}
                                onAddCategoryChange={onAddCategoryChange}
                                onSubmit={async (input: AddInput) => {
                                    await onAdd(input);
                                }}
                                onOpen={onOpenDownload}
                                onMany={openBatch}
                                onPlay={(value: string, kind: string) =>
                                    onPlayClick(value, kind)
                                }
                                onChoose={(target: PickerTarget) => {
                                    setPicker(target);
                                }}
                            />

                            <section
                                className="app-shell-list"
                                aria-label="Downloads"
                            >
                                <div className="app-shell-list-header">
                                    {counts.all > 0 && (
                                        <StatusLine
                                            counts={counts}
                                            filter={filter as SidebarFilter}
                                            onFilterChange={(
                                                f: SidebarFilter,
                                            ) => onFilterChange(f)}
                                            downloadSpeed={stats.downloadSpeed}
                                            uploadSpeed={stats.uploadSpeed}
                                        />
                                    )}
                                    {searchQuery && (
                                        <span className="app-shell-search-hint">
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
                                    category={category}
                                    onMoveCategory={(id: string) => {
                                        setMoving(id);
                                    }}
                                    searchQuery={searchQuery}
                                    highlightId={highlightId}
                                    onPause={onPause}
                                    onResume={onResume}
                                    onDownloadFile={onDownloadFile}
                                    onPlay={onPlay}
                                    onRemove={onRemove}
                                    onStopSeeding={onStopSeeding}
                                    entries={entries}
                                    onToggleEntries={onToggleEntries}
                                    onLoadMoreEntries={onLoadMoreEntries}
                                    onPauseVideo={onPauseVideo}
                                    onResumeVideo={onResumeVideo}
                                    onRemoveVideo={onRemoveVideo}
                                    onPlayGroup={onPlayGroup}
                                    torrentSearch={searchTorrents}
                                    onOpenSearch={() => {
                                        // Browse latest is the torrent list, even if YouTube was the last tab.
                                        patchSearch({ source: "torrents" });
                                        setView("search");
                                    }}
                                />
                            </section>
                        </>
                    )}
                </div>
            </div>

            <StatusBar
                downloadSpeed={stats.downloadSpeed}
                uploadSpeed={stats.uploadSpeed}
                totalDownloaded={stats.totalDownloaded}
                totalPeers={stats.totalPeers}
                connection={connection}
                disk={disk}
            />

            <Toaster toasts={toasts} onDismiss={onDismissToast} />

            <ConfirmDialog
                prompt={bulkPrompt}
                onCancel={onBulkCancel}
                onConfirm={onBulkConfirm}
            />

            <ConfirmDialog
                prompt={
                    sourceView.confirmingDelete
                        ? {
                              heading: `Delete ${sourceView.confirmingDelete.name}?`,
                              body: "It will stop being searched.",
                              confirmLabel: "Delete",
                          }
                        : null
                }
                onCancel={handleSourceDeleteCancel}
                onConfirm={handleSourceDeleteConfirm}
            />

            {batch && (
                <BatchModal
                    key={`batch-${batch.seq}`}
                    initialText={batch.text}
                    defaultPreset={prefs.defaultPreset}
                    categories={categories}
                    addCategory={addCategory}
                    onClose={() => {
                        setBatch(null);
                    }}
                    onAdded={onBatchAdded}
                    onOpen={(id: string) => {
                        setBatch(null);
                        setView("list");
                        onOpenDownload(id);
                    }}
                />
            )}

            {sourceDialog && (
                <SourceModal
                    mode={sourceDialog}
                    onClose={() => {
                        setSourceDialog(null);
                    }}
                    onSaved={handleSourceSaved}
                    onNotify={onNotify}
                />
            )}

            <SettingsModal
                open={settingsOpen}
                prefs={prefs}
                sort={sort}
                server={serverSettings}
                categories={categories}
                onCategoryCreate={onCategoryCreate}
                onCategoryUpdate={onCategoryUpdate}
                onCategoryOrder={onCategoryOrder}
                onCategoryDelete={(category: CategoryItem) => {
                    setConfirmingCategory(category);
                }}
                onClose={onSettingsClose}
                onPrefsChange={onPrefsChange}
                onSortChange={onSortChange}
                apiKey={apiKey}
                apiKeyMessage={apiKeyMessage}
                onApiKeySave={onApiKeySave}
            />

            {/* After Settings: the two share a z-index, and the later one
                    is drawn on top, so this confirmation opened from inside
                    Settings must come after it. */}
            <ConfirmDialog
                prompt={
                    confirmingCategory
                        ? {
                              heading: `Delete ${confirmingCategory.name}?`,
                              body: "Its folder and files stay on disk.",
                              confirmLabel: "Delete",
                          }
                        : null
                }
                onCancel={() => {
                    setConfirmingCategory(null);
                }}
                onConfirm={async () => {
                    const category = confirmingCategory;
                    setConfirmingCategory(null);
                    if (category) await onCategoryDelete(category.id);
                }}
            />

            <RemoveDialog
                task={removing}
                onCancel={onRemoveCancel}
                onConfirm={onRemoveConfirm}
            />

            <PlayerModal
                open={playerModalOpen}
                url={playerUrl}
                kind={playerKind}
                taskId={playerTaskId}
                fileIndex={playerFileIndex}
                fromTorrent={playerFromTorrent}
                positions={playerPositions}
                audioLanguage={prefs.audioLanguage}
                subtitleLanguage={prefs.subtitleLanguage}
                onPositionSaved={onPositionSaved}
                files={playerFiles}
                hasPreviousItem={playerHasPreviousItem}
                hasNextItem={playerHasNextItem}
                nextItemTitle={playerNextItemTitle}
                onNextItem={onNextItem}
                onPreviousItem={onPreviousItem}
                onClose={onPlayerModalClose}
            />

            <CategoryMove
                task={
                    moving ? (tasks.find((t) => t.id === moving) ?? null) : null
                }
                categories={categories}
                onClose={() => {
                    setMoving(null);
                }}
                onMove={async (categoryId: string) => {
                    const id = moving;
                    if (!id) return;
                    await onMoveCategory(id, categoryId);
                    setMoving(null);
                }}
            />

            {/* Remounted by key for each search result. */}
            <AddTorrentModal
                key={addInitial ? `found-${addInitial.id}` : "blank"}
                initial={addInitial}
                open={addModalOpen}
                onClose={onAddModalClose}
                onAdd={onAdd}
                categories={categories}
                addCategory={addCategory}
                onAddCategoryChange={onAddCategoryChange}
                onOpen={(id: string, collectionId?: string) => {
                    // The modal can open over any view; the row is in the list.
                    setView("list");
                    onAddModalClose();
                    onOpenDownload(id, collectionId);
                }}
                onResolve={onResolve}
                onPlay={(
                    value: string,
                    kind: string,
                    fileIndex?: number | null,
                    files?: PlayableFile[],
                ) => onPlayClick(value, kind, fileIndex, files)}
            />

            {/* The picker's CSS puts it one z-index below the player. */}
            {picker && (
                <PlaylistPicker
                    key={picker.url}
                    target={picker}
                    onClose={() => {
                        setPicker(null);
                    }}
                    onDownload={async (url: string) => {
                        // A listed video, queued the way a pasted link is:
                        // looked up, then downloaded at the preferred
                        // preset or the closest one it offers.
                        await onAdd({
                            type: "link",
                            value: url,
                            preset: prefs.defaultPreset,
                        });
                    }}
                    onPlay={(url: string) => onPlayClick(url, "site")}
                    onPlayAll={onPlayQueue}
                    defaultPreset={prefs.defaultPreset}
                    categories={categories}
                    addCategory={addCategory}
                    onAdd={async (request: PlaylistRequest) => {
                        await onAddPlaylist(request);
                        setPicker(null);
                    }}
                />
            )}
        </div>
    );
}
