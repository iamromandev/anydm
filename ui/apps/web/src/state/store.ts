import {
    DOWNLOADS_CATEGORY_ID,
    type AddInitial,
    type CategoryItem,
    type EntriesView,
    type PositionView,
    type TaskSummary,
    type UiTask,
    type VideoSpeeds,
} from "@/lib/api";
import type { Disk } from "@/lib/api/disk";
import type { ServerSettings } from "@/lib/api/server-settings";
import type { Connection } from "@/lib/connection";
import type { Live } from "@/lib/live";
import type { PlayableFile } from "@/lib/media";
import { DEFAULT_PREFS, type Prefs } from "@/lib/prefs";
import type { QueueItem } from "@/lib/queue";
import { DEFAULT_SORT, type SortValue } from "@/lib/sort";
import type { Toast } from "@/lib/toast";

export type BulkAction = "pause_all" | "resume_all" | "clear_finished";

/**
 * Everything the page knows. One flat object, as the Qwik store was, so a
 * handler that reads the category while it merges a task needs no wiring.
 */
export interface AppState {
    tasks: UiTask[];
    // The last page fetched, and how many there are for the current filter.
    // Pages accumulate: nothing already on screen is dropped to make room.
    page: number;
    totalPages: number;
    loadingMore: boolean;
    // Counts for every filter, from the database rather than from the rows
    // that happen to be loaded.
    summary: TaskSummary | null;
    summaryLoading: boolean;
    summaryAgain: boolean;
    toasts: Toast[];
    // The task the remove dialog is asking about, or null when it is shut.
    removing: {
        id: string;
        title: string;
        status: string;
        videos?: number;
    } | null;
    // The sweep waiting to be confirmed, or null when nothing is pending.
    pendingBulk: BulkAction | null;
    // Ticked only while a retry is actually pending.
    now: number;
    connection: Connection;
    // When the current gap in live updates began; null whenever it is live.
    degradedSince: number | null;
    // The id of the "lost contact" toast, so reconnecting can take it down.
    outageToastId: string | null;
    // From the event stream's `disk` frames; null until the first arrives.
    disk: Disk | null;
    filter: "all" | "downloading" | "seeding" | "completed";
    categories: CategoryItem[];
    // The sidebar's category filter: an id, or null for every category.
    category: string | null;
    // Where the add forms save new downloads.
    addCategory: string;
    searchQuery: string;
    // The row Open just brought into view, outlined for a moment.
    highlightId: string;
    sort: SortValue;
    settingsOpen: boolean;
    prefs: Prefs;
    // Null until the API answers, and after it refuses.
    serverSettings: ServerSettings | null;
    searchEnabled: boolean;
    searchTorrents: boolean;
    searchYoutube: boolean;
    addInitial: (AddInitial & { id: number }) | null;
    addInitialSeq: number;
    apiKey: string;
    // Set by the first 401 and kept: it is what puts the key field first, and
    // what stops every later 401 from reopening a closed modal.
    apiKeyMessage: string | null;
    sidebarOpen: boolean;
    sidebarCollapsed: boolean;
    addModalOpen: boolean;
    playerModalOpen: boolean;
    playerUrl: string;
    playerKind: string;
    playerTaskId: string;
    playerFileIndex: number | null;
    playerFiles: PlayableFile[];
    playerFromTorrent: boolean;
    playerPositions: PositionView[];
    // A count of stream writes, and the count at each task's latest one: what
    // lets a page fetch tell which rows went stale while it was out.
    streamSeq: number;
    streamTouched: Record<string, number>;
    // Each open Entries list, by group id. Only open lists are held.
    entries: Record<string, EntriesView>;
    // Each running group video's speed, for its group's live speed.
    videoSpeeds: VideoSpeeds;
    // A play queue: its items, the one playing, and for a group, which of its
    // pages are loaded. Empty when nothing queues.
    queue: QueueItem[];
    queueIndex: number;
    queueGroup: { id: string; page: number; totalPages: number } | null;
}

export type Store = Live<AppState>;

/**
 * The state before anything has run. It reads no storage: what the browser
 * remembers is loaded by `settings.loadStored()` when the app starts.
 */
export function createInitialState(): AppState {
    return {
        tasks: [],
        page: 1,
        totalPages: 1,
        loadingMore: false,
        summary: null,
        summaryLoading: false,
        summaryAgain: false,
        toasts: [],
        removing: null,
        pendingBulk: null,
        now: Date.now(),
        connection: "connecting",
        degradedSince: null,
        outageToastId: null,
        disk: null,
        filter: "all",
        categories: [],
        category: null,
        addCategory: DOWNLOADS_CATEGORY_ID,
        searchQuery: "",
        highlightId: "",
        sort: DEFAULT_SORT,
        settingsOpen: false,
        prefs: DEFAULT_PREFS,
        serverSettings: null,
        searchEnabled: false,
        searchTorrents: false,
        searchYoutube: false,
        addInitial: null,
        addInitialSeq: 0,
        apiKey: "",
        apiKeyMessage: null,
        sidebarOpen: false,
        sidebarCollapsed: true,
        addModalOpen: false,
        playerModalOpen: false,
        playerUrl: "",
        playerKind: "",
        playerTaskId: "",
        playerFileIndex: null,
        playerFiles: [],
        playerFromTorrent: false,
        playerPositions: [],
        streamSeq: 0,
        streamTouched: {},
        entries: {},
        videoSpeeds: {},
        queue: [],
        queueIndex: -1,
        queueGroup: null,
    };
}
