export type TaskSource = "api" | "bun";

export type UiTask = {
    id: string;
    title: string;
    kind: string;
    status: string;
    progress: number;
    eta: number;
    error?: string;
    source: TaskSource;
    progressDetails: {
        downloadedBytes: number;
        totalBytes: number;
        downloadSpeed: number;
        uploadSpeed: number;
        eta: number;
        peersConnected: number;
    };
};

/** A FastAPI task row, in the shape the components already render. */
export function normalizeApiTask(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title || raw.filename || raw.source_url,
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta_seconds ?? 0,
        error: raw.error ?? undefined,
        source: "api",
        progressDetails: {
            downloadedBytes: raw.downloaded_bytes ?? 0,
            totalBytes: raw.total_bytes ?? 0,
            downloadSpeed: raw.speed_bps ?? 0,
            // The FastAPI service never uploads and has no peers; these exist so
            // torrent and non-torrent rows render through one component.
            uploadSpeed: 0,
            eta: raw.eta_seconds ?? 0,
            peersConnected: 0,
        },
    };
}

/** A Bun torrent task, already in the UI's shape — only tagged. */
export function normalizeBunTask(raw: any): UiTask {
    return {
        id: raw.id,
        title: raw.title,
        kind: raw.kind,
        status: raw.status,
        progress: raw.progress ?? 0,
        eta: raw.eta ?? 0,
        error: raw.error,
        source: "bun",
        progressDetails: {
            downloadedBytes: raw.progressDetails?.downloadedBytes ?? 0,
            totalBytes: raw.progressDetails?.totalBytes ?? 0,
            downloadSpeed: raw.progressDetails?.downloadSpeed ?? 0,
            uploadSpeed: raw.progressDetails?.uploadSpeed ?? 0,
            eta: raw.progressDetails?.eta ?? 0,
            peersConnected: raw.progressDetails?.peersConnected ?? 0,
        },
    };
}
