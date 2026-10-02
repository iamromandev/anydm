/**
 * The download contract, in one place.
 *
 * These types mirror `src/data/type/download/download.py` on the API side. The
 * UI keeps its own row, `UiTask`: a collection is a row of kind `playlist`, and
 * a collection's video carries its collection as `parentId`. They are
 * written as unions rather than `string` so that a status the API gains — and
 * the UI has not been taught to draw — is a compile error here instead of an
 * exception in the middle of a render.
 */

import { hasMediaExtension, mediaFiles } from "../media";

export type TaskStatus =
    | "pending"
    | "downloading"
    | "muxing"
    | "paused"
    | "seeding"
    | "complete"
    | "failed"
    | "canceled";

/** A download's `media_kind`, or `torrent` for its platform. `playlist` is a collection's own row. */
export type TaskKind = "video" | "audio" | "file" | "torrent" | "playlist";

/** How a group's videos stand (v0.5). */
export type EntryCounts = {
    total: number;
    complete: number;
    /** Queued or downloading. */
    active: number;
    /** Downloading, of `active`. */
    downloading: number;
    paused: number;
    failed: number;
    /** Watched to the end; only the list's reads carry it (part 4). */
    watched?: number;
};

export type UiTask = {
    id: string;
    title: string;
    url: string;
    kind: TaskKind;
    status: TaskStatus;
    progress: number;
    eta: number;
    error?: string;
    downloadedBytes: number;
    totalBytes: number;
    downloadSpeed: number;
    uploadSpeed: number;
    peersConnected: number;
    /** Tries so far, this one included. */
    attempts: number;
    /** Carried for the detail panel rather than the card's own face. */
    platform?: string;
    /** yt-dlp's name for the site of a `site` task: "Youtube", "Vimeo", ... */
    extractor?: string;
    preset?: string;
    filename?: string;
    fileSize?: number;
    createdAt?: number;
    startedAt?: number;
    completedAt?: number;
    /** The retry budget, as the API has it configured. */
    maxAttempts?: number;
    /** When the queue looks at this task again, while a retry is pending. */
    nextAttemptAt?: number;
    errorCode?: string;
    /** Present only while a segmented transfer is running. */
    segments?: SegmentView[];
    /** Torrent-only. Absent for every other download, whose one file is `filename`. */
    files?: FileView[];
    /**
     * Where each file was left in the player (#96). Only a row with its files
     * carries it; `progress` frames don't, and `keepPositions` holds it.
     */
    positions?: PositionView[];
    /** A group's video: its group's id. Such a row never enters the list. */
    parentId?: string;
    /** A group's video: its number in the listing. */
    position?: number;
    /** A group's own row: how its videos stand. */
    entryCounts?: EntryCounts;
    /** A group's own row: the folder its videos finish into. */
    folder?: string;
    /**
     * Torrent-only, and never filled: the engine reports connected peers and
     * does not split a swarm into seeders and leechers. Kept because the card
     * hides what it has no value for.
     */
    seeders?: number;
    leechers?: number;
    ratio?: number;
    infoHash?: string;
};

/** One file inside a torrent, and how much of it has landed. */
export type FileView = {
    index: number;
    path: string;
    sizeBytes: number;
    selected: boolean;
    downloadedBytes: number;
};

/** One byte range of the file, and how much of it has landed. */
export type SegmentView = {
    index: number;
    start: number;
    end: number;
    downloaded: number;
    downloadSpeed: number;
};

/**
 * The segments of a progress frame, or `undefined` when there are none.
 *
 * `undefined` rather than `[]` on purpose: the API omits the key for a transfer
 * it did not segment, and the caller treats a missing key as "unchanged" rather
 * than "now empty" — the same rule every other field in a progress frame follows.
 */
export function normalizeSegments(raw: any): SegmentView[] | undefined {
    if (!Array.isArray(raw?.segments)) return undefined;
    return raw.segments.map((segment: any) => ({
        index: segment.index ?? 0,
        start: segment.start ?? 0,
        end: segment.end ?? 0,
        downloaded: segment.downloaded ?? 0,
        downloadSpeed: segment.speed_bps ?? 0,
    }));
}

/**
 * Widths and fills for the segment strip.
 *
 * The width is proportional to the segment's share of the file, which is what
 * makes the strip a map of the file rather than a row of equal boxes: a lagging
 * segment reads as a lagging region.
 */
export function segmentLayout(
    segments: SegmentView[],
): { index: number; widthPercent: number; fillPercent: number }[] {
    const total = segments.reduce(
        (sum, segment) => sum + (segment.end - segment.start + 1),
        0,
    );
    if (total <= 0) return [];
    return segments.map((segment) => {
        const length = segment.end - segment.start + 1;
        return {
            index: segment.index,
            widthPercent: (length / total) * 100,
            fillPercent: Math.min(100, (segment.downloaded / length) * 100),
        };
    });
}

/** An API list item (a download or a collection), flattened into the shape the components read. */
export function normalizeApiTask(raw: any): UiTask {
    if (raw?.type === "collection") return normalizeCollection(raw);
    const downloadedBytes = raw.downloaded_bytes ?? 0;
    const uploadedBytes = raw.torrent?.uploaded_bytes ?? 0;
    const isTorrent = raw.platform === "torrent";
    const files: any[] = Array.isArray(raw.files) ? raw.files : [];
    // A site or direct download has exactly one file, at index 0.
    const single = isTorrent
        ? undefined
        : files.find((file) => (file.index ?? 0) === 0);

    return {
        id: raw.id,
        title: raw.title || single?.path || raw.source_url,
        url: raw.source_url ?? "",
        kind: isTorrent ? "torrent" : raw.media_kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.live?.eta_seconds ?? 0,
        error: raw.error ?? undefined,
        downloadedBytes,
        totalBytes: raw.total_bytes ?? 0,
        downloadSpeed: raw.live?.speed_bps ?? 0,
        // Torrent-only: the engine reports an upload rate. Every other
        // platform's is 0.
        uploadSpeed: raw.live?.upload_speed_bps ?? 0,
        peersConnected: raw.live?.peers ?? 0,
        attempts: raw.attempts ?? 0,
        platform: raw.platform ?? undefined,
        extractor: raw.site?.extractor ?? undefined,
        preset: raw.site?.preset ?? undefined,
        filename: single?.path || undefined,
        // Zero until it finishes: the size is the finished file's.
        fileSize:
            single && single.size_bytes > 0 ? single.size_bytes : undefined,
        createdAt: raw.created_at ? Date.parse(raw.created_at) : undefined,
        startedAt: raw.started_at ? Date.parse(raw.started_at) : undefined,
        completedAt: raw.completed_at
            ? Date.parse(raw.completed_at)
            : undefined,
        maxAttempts: raw.max_attempts ?? undefined,
        // An instant, not a string: the card counts down against the local
        // clock, and comparing formatted times is how off-by-a-timezone bugs
        // are made.
        nextAttemptAt: raw.next_attempt_at
            ? Date.parse(raw.next_attempt_at)
            : undefined,
        errorCode: raw.error_code ?? undefined,
        infoHash: raw.torrent?.info_hash ?? undefined,
        // Undefined rather than 0 when nothing has downloaded: the card hides
        // a ratio it has no value for instead of claiming a ratio of zero.
        ratio:
            downloadedBytes > 0 ? uploadedBytes / downloadedBytes : undefined,
        files: isTorrent ? normalizeFiles(raw) : undefined,
        positions: normalizePositions(raw),
        parentId: raw.collection_id ?? undefined,
        position: raw.position ?? undefined,
    };
}

/** A collection, as the playlist row the group card draws. */
export function normalizeCollection(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title || raw.source_url,
        url: raw.source_url ?? "",
        kind: "playlist",
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: 0,
        downloadedBytes: raw.downloaded_bytes ?? 0,
        totalBytes: raw.total_bytes ?? 0,
        // The sum of its videos' live speeds, as the API last computed it.
        downloadSpeed: raw.speed_bps ?? 0,
        uploadSpeed: 0,
        peersConnected: 0,
        attempts: 0,
        platform: "site",
        extractor: raw.extractor ?? undefined,
        preset: raw.preset ?? undefined,
        createdAt: raw.created_at ? Date.parse(raw.created_at) : undefined,
        entryCounts: normalizeEntryCounts(raw.counts),
        folder: raw.folder || undefined,
    };
}

/**
 * A `progress` frame's numbers on a row. Absent fields are unchanged, never
 * zero: the API leaves out what did not move.
 */
export function applyProgressFrame(task: UiTask, data: any): UiTask {
    const moved = new Map<number, number>(
        Array.isArray(data?.files)
            ? data.files.map((file: any) => [
                  file.index,
                  file.downloaded_bytes,
              ])
            : [],
    );
    return {
        ...task,
        progress: data.progress ?? task.progress,
        eta: data.live?.eta_seconds ?? task.eta,
        downloadedBytes: data.downloaded_bytes ?? task.downloadedBytes,
        totalBytes: data.total_bytes ?? task.totalBytes,
        downloadSpeed: data.live?.speed_bps ?? task.downloadSpeed,
        uploadSpeed: data.live?.upload_speed_bps ?? task.uploadSpeed,
        peersConnected: data.live?.peers ?? task.peersConnected,
        segments: normalizeSegments(data) ?? task.segments,
        files: task.files?.map((file) =>
            moved.has(file.index)
                ? { ...file, downloadedBytes: moved.get(file.index) ?? 0 }
                : file,
        ),
    };
}

/** A collection's counts, or `undefined` for any other row. */
export function normalizeEntryCounts(raw: any): EntryCounts | undefined {
    if (!raw || typeof raw !== "object") return undefined;
    return {
        total: raw.total ?? 0,
        complete: raw.complete ?? 0,
        active: raw.active ?? 0,
        downloading: raw.downloading ?? 0,
        paused: raw.paused ?? 0,
        failed: raw.failed ?? 0,
        ...(typeof raw.watched === "number" ? { watched: raw.watched } : {}),
    };
}

/**
 * The torrent's files, or `undefined` when there are none.
 *
 * `undefined` rather than `[]`, following the same rule `normalizeSegments`
 * uses: the API omits the key for anything that is not a torrent, and a
 * missing key means "not applicable" rather than "an empty torrent".
 */
export function normalizeFiles(raw: any): FileView[] | undefined {
    if (!Array.isArray(raw?.files)) return undefined;
    return raw.files.map((file: any) => ({
        index: file.index ?? 0,
        path: file.path ?? "",
        sizeBytes: file.size_bytes ?? 0,
        selected: file.selected ?? true,
        downloadedBytes: file.downloaded_bytes ?? 0,
    }));
}

/** What a status is called, and which icon key draws it. */
export type StatusView = {
    key: TaskStatus | "unknown";
    label: string;
};

// A total map rather than a switch: adding a member to TaskStatus without a
// label stops the typecheck, which a switch with no default never did.
const STATUS_LABELS: Record<TaskStatus, string> = {
    pending: "Queued",
    downloading: "Downloading",
    muxing: "Processing",
    paused: "Paused",
    seeding: "Seeding",
    complete: "Complete",
    failed: "Failed",
    canceled: "Canceled",
};

/**
 * The view for a status, including one the API invented after this build.
 *
 * Takes `string` deliberately: rows arrive as JSON over SSE and a declared
 * union is a claim about them, not a guarantee.
 */
export function statusView(status: string): StatusView {
    const label = STATUS_LABELS[status as TaskStatus];
    return label
        ? { key: status as TaskStatus, label }
        : { key: "unknown", label: "Unknown" };
}

/** Statuses the API's own `pause` accepts. */
export function canPause(status: string): boolean {
    return (
        status === "pending" || status === "downloading" || status === "seeding"
    );
}

/** Statuses the API's own `resume` accepts — a retry is a resume of a failure. */
export function canResume(status: string): boolean {
    return status === "paused" || status === "failed";
}

/** Still on its way to a file: what the Active filter and its count mean. */
export function isActive(status: string): boolean {
    return (
        status === "pending" || status === "downloading" || status === "muxing"
    );
}

/** Still sharing a finished torrent. */
export function isSeeding(status: string): boolean {
    return status === "seeding";
}

/** Only a seeding torrent can be told to stop. */
export function canStopSeeding(status: string): boolean {
    return status === "seeding";
}

/** Done enough to hand over a file: a seeding torrent is finished, too. */
function isFinished(status: string): boolean {
    return status === "complete" || status === "seeding";
}

/**
 * What the card's download button does (#107): nothing yet, fetch the one
 * file, or, for a torrent of several files, open the detail to pick one.
 * The API answers 409 for a single download of several files.
 */
export function downloadAction(
    task: Pick<UiTask, "status" | "files">,
): "none" | "file" | "choose" {
    if (!isFinished(task.status)) return "none";
    const selected = (task.files ?? []).filter((file) => file.selected);
    return selected.length > 1 ? "choose" : "file";
}

/**
 * Whether a finished task's card offers Play (#94): a video or audio
 * download, a direct download of a media file, or a torrent downloading one.
 */
export function canPlayTask(
    task: Pick<UiTask, "status" | "kind" | "filename" | "files">,
): boolean {
    if (task.kind === "torrent") {
        // A torrent plays while it downloads too, from rqbit (#95).
        if (!isFinished(task.status) && !playsFromTorrent(task)) return false;
        return (task.files ?? []).some(
            (file) => file.selected && hasMediaExtension(file.path),
        );
    }
    if (!isFinished(task.status)) return false;
    if (task.kind === "video" || task.kind === "audio") return true;
    return hasMediaExtension(task.filename ?? "");
}

/**
 * A torrent still downloading (or paused) plays through rqbit's stream, not
 * from its partial file on disk (#95): the player skips the native check and
 * shows the swarm.
 */
export function playsFromTorrent(
    task: Pick<UiTask, "status" | "kind">,
): boolean {
    return (
        task.kind === "torrent" &&
        (task.status === "pending" ||
            task.status === "downloading" ||
            task.status === "paused")
    );
}

/** Whether a torrent's file row gets its own download link. */
export function canDownloadTorrentFile(
    status: string,
    file: FileView,
): boolean {
    return isFinished(status) && file.selected;
}

/** The four numbers the status bar draws. */
export type GlobalStats = {
    downloadSpeed: number;
    uploadSpeed: number;
    totalDownloaded: number;
    totalPeers: number;
};

/**
 * The status bar's numbers, from the rows already on screen.
 *
 * Rates count only rows that are still transferring. The API zeroes `speed_bps`
 * on every terminal transition, but a row left `downloading` by a killed
 * process keeps its last speed until recovery requeues it, and that stale
 * number would otherwise read as live throughput in the footer.
 *
 * Bytes are the opposite: every row counts, because a finished download is
 * precisely what a total downloaded is made of.
 */
export function aggregateStats(tasks: UiTask[]): GlobalStats {
    const stats: GlobalStats = {
        downloadSpeed: 0,
        uploadSpeed: 0,
        totalDownloaded: 0,
        totalPeers: 0,
    };

    for (const task of tasks) {
        stats.totalDownloaded += task.downloadedBytes;
        if (!isActive(task.status) && !isSeeding(task.status)) continue;
        stats.downloadSpeed += task.downloadSpeed;
        stats.uploadSpeed += task.uploadSpeed;
        stats.totalPeers += task.peersConnected;
    }

    return stats;
}

/** What a card says about a task that is being retried, or has given up. */
export type RetryView = {
    tone: "warning" | "error";
    headline: string;
    detail?: string;
};

/** The error code of a task waiting for disk space rather than retrying. */
const DISK_WAIT_CODE = "insufficient_storage";

function attemptsPhrase(attempts: number): string {
    return attempts === 1 ? "1 attempt" : `${attempts} attempts`;
}

/**
 * The line explaining a retry, or `null` when there is nothing to explain.
 *
 * A task waiting to be retried is `pending` with a deadline in the future,
 * which on its own looks exactly like a task waiting for a free worker. The
 * deadline is the only thing that distinguishes them, so it is also the thing
 * that decides whether this says anything at all.
 *
 * `now` is passed in rather than read, so the countdown is a pure function of
 * the clock the caller is ticking.
 */
export function retryLabel(task: UiTask, now: number): RetryView | null {
    if (task.status === "failed") {
        const code = task.errorCode ? ` · ${task.errorCode}` : "";
        return {
            tone: "error",
            headline: `Failed after ${attemptsPhrase(task.attempts)}${code}`,
            detail: task.error,
        };
    }

    if (task.status !== "pending" || task.nextAttemptAt === undefined) {
        return null;
    }

    const remaining = Math.ceil((task.nextAttemptAt - now) / 1000);

    // Nothing failed: the API parked the task until the disk has room, and
    // handed back the attempt, so a retry count would only mislead.
    if (task.errorCode === DISK_WAIT_CODE) {
        const check =
            remaining > 0 ? `checking again in ${remaining}s` : "checking…";
        return {
            tone: "warning",
            headline: `Waiting for disk space · ${check}`,
            detail: task.error,
        };
    }

    const budget = task.maxAttempts ? ` of ${task.maxAttempts}` : "";
    const wait = remaining > 0 ? `Retrying in ${remaining}s` : "Retrying…";

    return {
        tone: "warning",
        headline: `${wait} · attempt ${task.attempts}${budget}`,
        detail: task.error,
    };
}

/** How many tasks each sidebar filter would show, counted by the API. */
export type TaskSummary = {
    all: number;
    downloading: number;
    seeding: number;
    completed: number;
};

/**
 * The counts, with anything absent read as zero.
 *
 * Zero rather than undefined so the sidebar always has a number to draw. A
 * blank where a count should be reads as a bug; a zero reads as an empty
 * filter, which is what a missing count almost always means.
 */
export function normalizeSummary(raw: any): TaskSummary {
    return {
        all: raw?.all ?? 0,
        downloading: raw?.downloading ?? 0,
        seeding: raw?.seeding ?? 0,
        completed: raw?.completed ?? 0,
    };
}

/**
 * A freshly fetched page, added to what is already on screen.
 *
 * Rows already loaded keep their position, so the list never reshuffles under
 * a reader. Where a page overlaps what is held — which happens whenever a task
 * is added between two requests, since the order is newest first — the newer
 * copy wins in the older row's place.
 */
export function appendPage(existing: UiTask[], incoming: UiTask[]): UiTask[] {
    const byId = new Map(
        incoming.map((row) => [
            row.id,
            row,
        ]),
    );
    const updated = existing.map((row) => byId.get(row.id) ?? row);
    const seen = new Set(existing.map((row) => row.id));

    return [
        ...updated,
        ...incoming.filter((row) => !seen.has(row.id)),
    ];
}

/**
 * New copies of rows, with the segment strip the held copies had.
 *
 * Segments ride progress frames only: the task row that the REST list and
 * the `task` event return has no `segments` field at all. Without this, every
 * refresh would blank the strip, and the bars would flicker in and out for
 * the whole download.
 */
/** Where one file of a download was left in the player (#96). */
export type PositionView = {
    /** A torrent's file; 0 for a download's one file. */
    fileIndex: number;
    positionSeconds: number;
    durationSeconds: number;
    watched: boolean;
};

/** One file's playback, as the API answers a save or carries it on a file. */
export function normalizePlayback(raw: any, fileIndex: number): PositionView {
    return {
        fileIndex,
        positionSeconds: raw?.position_seconds ?? 0,
        durationSeconds: raw?.duration_seconds ?? 0,
        watched: raw?.watched ?? false,
    };
}

/** The row's played files, or `undefined` when it carries no file list (a frame). */
export function normalizePositions(raw: any): PositionView[] | undefined {
    if (!Array.isArray(raw?.files)) return undefined;
    return raw.files
        .filter((file: any) => file.playback)
        .map((file: any) => normalizePlayback(file.playback, file.index ?? 0));
}

/**
 * Positions from what's on screen, for rows that came without them.
 *
 * A progress frame has no positions: only a row with its files does. Like
 * `keepSegments`, this stops every frame blanking the card's watched bar.
 */
export function keepPositions(rows: UiTask[], held: UiTask[]): UiTask[] {
    const prior = new Map(
        held.map((row) => [
            row.id,
            row,
        ]),
    );
    return rows.map((row) => {
        if (row.positions !== undefined) return row;
        const positions = prior.get(row.id)?.positions;
        return positions ? { ...row, positions } : row;
    });
}

/** Where to start a file: where it was left, or 0 (a watched file starts over). */
export function resumeAt(
    positions: PositionView[] | undefined,
    fileIndex: number | null,
): number {
    const index = fileIndex ?? 0;
    return (
        (positions ?? []).find((position) => position.fileIndex === index)
            ?.positionSeconds ?? 0
    );
}

/**
 * Which file Play on the card opens (#97): the one you were partway through,
 * else the first not yet watched, in natural order. The first file when
 * everything has been watched, or there's nothing to go on yet.
 */
export function pickFileToOpen(
    task: Pick<UiTask, "files">,
    positions: PositionView[] | undefined,
): number | null {
    const files = mediaFiles(task.files ?? []);
    if (files.length === 0) return null;
    const byIndex = new Map(
        (positions ?? []).map((p) => [
            p.fileIndex,
            p,
        ]),
    );
    const partway = files.find((file) => {
        const position = byIndex.get(file.index);
        return position && !position.watched && position.positionSeconds > 0;
    });
    if (partway) return partway.index;
    const unwatched = files.find((file) => !byIndex.get(file.index)?.watched);
    return (unwatched ?? files[0]).index;
}

/**
 * What the card shows of it (#96): a single download's bar (watched is full),
 * or a torrent of several media files as "N of M watched". Nothing until
 * something has been played.
 */
export function watchedProgress(
    task: Pick<UiTask, "kind" | "files" | "positions">,
): { fraction: number | null; label: string | null } {
    const positions = task.positions ?? [];
    if (task.kind === "torrent") {
        const media = (task.files ?? []).filter(
            (file) => file.selected && hasMediaExtension(file.path),
        );
        if (media.length > 1) {
            if (positions.length === 0) return { fraction: null, label: null };
            const watched = media.filter((file) =>
                positions.some((p) => p.fileIndex === file.index && p.watched),
            ).length;
            return {
                fraction: null,
                label: `${watched} of ${media.length} watched`,
            };
        }
    }
    const position = positions[0];
    if (!position) return { fraction: null, label: null };
    if (position.watched && position.positionSeconds === 0) {
        return { fraction: 1, label: null };
    }
    return {
        fraction:
            position.durationSeconds > 0
                ? Math.min(
                      1,
                      position.positionSeconds / position.durationSeconds,
                  )
                : null,
        label: null,
    };
}

/** `task` with `position` in place of that file's old one, after a save. */
export function withPosition(task: UiTask, position: PositionView): UiTask {
    const others = (task.positions ?? []).filter(
        (p) => p.fileIndex !== position.fileIndex,
    );
    return {
        ...task,
        positions: [
            ...others,
            position,
        ].sort((a, b) => a.fileIndex - b.fileIndex),
    };
}

export function keepSegments(rows: UiTask[], held: UiTask[]): UiTask[] {
    const prior = new Map(
        held.map((row) => [
            row.id,
            row,
        ]),
    );
    return rows.map((row) => {
        const segments = prior.get(row.id)?.segments;
        return segments ? { ...row, segments } : row;
    });
}

/**
 * A fetched page, minus anything the stream has said since it was requested.
 *
 * The server reads a page before the answer arrives, and the stream keeps
 * talking in between. A row it wrote in that gap is newer than the page's copy
 * — a download that finished there would otherwise go back to "downloading",
 * and a finished task sends nothing more to put it right. So for each id in
 * `touched`, what is on screen wins: its current copy, or its absence if the
 * stream removed it, or a row the page was read too early to include.
 * Everything else is the page's, which is how a fallback poll still corrects
 * the list while the stream is down and touching nothing.
 */
export function settlePage(
    fetched: UiTask[],
    held: UiTask[],
    touched: ReadonlySet<string>,
): UiTask[] {
    const live = new Map(
        held.map((row) => [
            row.id,
            row,
        ]),
    );
    const listed = new Set(fetched.map((row) => row.id));
    const settled = fetched.flatMap((row) => {
        if (!touched.has(row.id))
            return [
                row,
            ];
        const current = live.get(row.id);
        return current
            ? [
                  current,
              ]
            : [];
    });

    return [
        ...held.filter((row) => touched.has(row.id) && !listed.has(row.id)),
        ...settled,
    ];
}
