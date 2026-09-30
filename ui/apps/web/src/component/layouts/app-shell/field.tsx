import type { AddType } from "@/lib/api/site";
import { component$, $, useSignal, useStore } from "@qwik.dev/core";
import {
    aggregateStats,
    canPause,
    canResume,
    isActive,
    isSeeding,
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
import {
    SearchView,
    emptySearch,
    type SearchState,
} from "@/component/features/search-view";
import type { AddInitial } from "@/lib/api/search";
import type { PlayableFile } from "@/lib/media";
import type { PositionView } from "@/lib/api";
import { HeroInput } from "@/component/features/hero-input";
import { StatusLine } from "@/component/features/status-line";
import { PlayerModal } from "@/component/features/player-modal";
import { PlaylistPicker } from "@/component/features/playlist-picker";
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
    onAdd: (input: {
        type: AddType;
        value: string;
        preset?: string;
        files?: number[];
    }) => void;
    onResolve: (torrent: string) => Promise<ResolvedTorrent>;
    /** Add a playlist's ticked videos as one group (v0.5). */
    onAddPlaylist: (request: PlaylistRequest) => Promise<void>;
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
    /** What the add dialog opens on; a new id remounts it. */
    addInitial: (AddInitial & { id: number }) | null;
    onAddFound: (initial: AddInitial) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

export const AppShell = component$<AppShellProps>(
    ({
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
        onResolve,
        onAddPlaylist,
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
        addInitial,
        onAddFound,
        onNotify,
    }) => {
        const stats = aggregateStats(tasks);

        /** The list the picker is open on, if any. Held here, not in the
            add box: `.app-shell-content` has `contain: layout`, which would
            pin a fixed overlay to the content column. */
        const picker = useSignal<PickerTarget | null>(null);

        /** The list, or Search. Held here with Search's own state, so the
            query and results survive switching away and back. */
        const view = useSignal<"list" | "search">("list");
        const search = useStore<SearchState>(emptySearch());

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
                    searchQuery={searchQuery}
                    onSearchChange={onSearchChange}
                    sort={sort}
                    onSortChange={onSortChange}
                    onAddClick={onAddClick}
                    onSettingsClick={onSettingsOpen}
                    sidebarOpen={sidebarOpen}
                    onSidebarToggle={onSidebarToggle}
                />

                <div class="app-shell-main">
                    <Sidebar
                        filter={filter as SidebarFilter}
                        onFilterChange={$((f: SidebarFilter) => {
                            view.value = "list";
                            onFilterChange(f);
                        })}
                        search={{
                            enabled: searchEnabled,
                            active: view.value === "search",
                            onOpen: $(() => {
                                view.value = "search";
                            }),
                        }}
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
                        {view.value === "search" && searchEnabled ? (
                            <SearchView
                                state={search}
                                torrents={searchTorrents}
                                youtube={searchYoutube}
                                onAdd={onAddFound}
                                onAddVideo={$((url: string) =>
                                    onAdd({ type: "link", value: url }),
                                )}
                                onNotify={onNotify}
                            />
                        ) : (
                            <>
                                <HeroInput
                                    compact={counts.all > 0}
                                    defaultPreset={prefs.defaultPreset}
                                    onSubmit={$(
                                        async (input: {
                                            type: AddType;
                                            value: string;
                                            preset?: string;
                                        }) => {
                                            await onAdd(input);
                                        },
                                    )}
                                    onPlay={$((value: string, kind: string) =>
                                        onPlayClick(value, kind),
                                    )}
                                    onChoose={$((target: PickerTarget) => {
                                        picker.value = target;
                                    })}
                                />

                                <section
                                    class="app-shell-list"
                                    aria-label="Downloads"
                                >
                                    <div class="app-shell-list-header">
                                        {counts.all > 0 && (
                                            <StatusLine
                                                counts={counts}
                                                filter={filter as SidebarFilter}
                                                onFilterChange={$(
                                                    (f: SidebarFilter) =>
                                                        onFilterChange(f),
                                                )}
                                                downloadSpeed={
                                                    stats.downloadSpeed
                                                }
                                                uploadSpeed={stats.uploadSpeed}
                                            />
                                        )}
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
                                        onOpenSearch={$(() => {
                                            // Browse latest is the torrent list, even if YouTube was the last tab.
                                            search.source = "torrents";
                                            view.value = "search";
                                        })}
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

                <SettingsModal
                    open={settingsOpen}
                    prefs={prefs}
                    sort={sort}
                    server={serverSettings}
                    onClose={onSettingsClose}
                    onPrefsChange={onPrefsChange}
                    onSortChange={onSortChange}
                    apiKey={apiKey}
                    apiKeyMessage={apiKeyMessage}
                    onApiKeySave={onApiKeySave}
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

                {/* After the player: remounted by key for each search result,
                    and a remounted component placed before the player broke
                    it (Qwik 2 beta.43, see the picker below). */}
                <AddTorrentModal
                    key={addInitial ? `found-${addInitial.id}` : "blank"}
                    initial={addInitial}
                    open={addModalOpen}
                    onClose={onAddModalClose}
                    onAdd={onAdd}
                    onResolve={onResolve}
                    onPlay={$(
                        (
                            value: string,
                            kind: string,
                            fileIndex?: number | null,
                            files?: PlayableFile[],
                        ) => onPlayClick(value, kind, fileIndex, files),
                    )}
                />

                {/* Last, after the player. Mounted before it while the player
                    rendered nothing, the player's overlay never reached the
                    page once opened (Qwik 2 beta.43). The picker's CSS puts
                    it one z-index below the player instead. */}
                {picker.value && (
                    <PlaylistPicker
                        key={picker.value.url}
                        target={picker.value}
                        onClose={$(() => {
                            picker.value = null;
                        })}
                        onDownload={$(async (url: string) => {
                            // A listed video, queued the way a pasted link is:
                            // looked up, then downloaded at the preferred
                            // preset or the closest one it offers.
                            await onAdd({
                                type: "link",
                                value: url,
                                preset: prefs.defaultPreset,
                            });
                        })}
                        onPlay={$((url: string) => onPlayClick(url, "site"))}
                        onPlayAll={onPlayQueue}
                        defaultPreset={prefs.defaultPreset}
                        onAdd={$(async (request: PlaylistRequest) => {
                            await onAddPlaylist(request);
                            picker.value = null;
                        })}
                    />
                )}
            </div>
        );
    },
);
