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
    /** Torrent-only, and absent until torrents are ported. */
    seeders?: number;
    leechers?: number;
    ratio?: number;
    infoHash?: string;
};

/** A FastAPI task row, flattened into the shape the components read. */
export function normalizeApiTask(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title || raw.filename || raw.source_url,
        url: raw.source_url ?? "",
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta_seconds ?? 0,
        error: raw.error ?? undefined,
        downloadedBytes: raw.downloaded_bytes ?? 0,
        totalBytes: raw.total_bytes ?? 0,
        downloadSpeed: raw.speed_bps ?? 0,
        // Nothing uploads or has peers yet; these exist so the torrent rows the
        // port will add render through the same component.
        uploadSpeed: 0,
        peersConnected: 0,
    };
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
    return status === "pending" || status === "downloading";
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
