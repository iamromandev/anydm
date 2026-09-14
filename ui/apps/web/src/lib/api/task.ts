/**
 * The task contract, in one place.
 *
 * These types mirror `src/data/type/download/task.py` on the API side. They are
 * written as unions rather than `string` so that a status the API gains — and
 * the UI has not been taught to draw — is a compile error here instead of an
 * exception in the middle of a render.
 */

export type TaskStatus =
    | "pending"
    | "downloading"
    | "muxing"
    | "paused"
    | "seeding"
    | "complete"
    | "failed"
    | "canceled";

/** `torrent` has no API counterpart yet; the card already draws it. */
export type TaskKind = "video" | "audio" | "file" | "torrent";

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
    /** Present only while a segmented transfer is running. */
    segments?: SegmentView[];
    /** Torrent-only. Absent for every other task. */
    files?: TorrentFileView[];
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
export type TorrentFileView = {
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

/** A FastAPI task row, flattened into the shape the components read. */
export function normalizeApiTask(raw: any): UiTask {
    const downloadedBytes = raw.downloaded_bytes ?? 0;
    const uploadedBytes = raw.uploaded_bytes ?? 0;

    return {
        id: raw.id,
        title: raw.title || raw.filename || raw.source_url,
        url: raw.source_url ?? "",
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta_seconds ?? 0,
        error: raw.error ?? undefined,
        downloadedBytes,
        totalBytes: raw.total_bytes ?? 0,
        downloadSpeed: raw.speed_bps ?? 0,
        // The API tracks cumulative uploaded bytes, not a live upload rate, so
        // this stays 0 unless a future API version adds one under this key.
        uploadSpeed: raw.upload_speed_bps ?? 0,
        peersConnected: raw.peers_connected ?? 0,
        infoHash: raw.info_hash ?? undefined,
        // Undefined rather than 0 when nothing has downloaded: the card hides
        // a ratio it has no value for instead of claiming a ratio of zero.
        ratio: downloadedBytes > 0 ? uploadedBytes / downloadedBytes : undefined,
        files: normalizeTorrentFiles(raw),
    };
}

/**
 * The torrent's files, or `undefined` when there are none.
 *
 * `undefined` rather than `[]`, following the same rule `normalizeSegments`
 * uses: the API omits the key for anything that is not a torrent, and a
 * missing key means "not applicable" rather than "an empty torrent".
 */
export function normalizeTorrentFiles(raw: any): TorrentFileView[] | undefined {
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
    return status === "pending" || status === "downloading" || status === "seeding";
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
