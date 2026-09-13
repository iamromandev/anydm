export type UiTask = {
    id: string;
    title: string;
    kind: string;
    status: string;
    progress: number;
    eta: number;
    error?: string;
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
        progressDetails: {
            downloadedBytes: raw.downloaded_bytes ?? 0,
            totalBytes: raw.total_bytes ?? 0,
            downloadSpeed: raw.speed_bps ?? 0,
            // Nothing uploads or has peers yet; these exist so the torrent rows
            // the port will add render through the same component.
            uploadSpeed: 0,
            eta: raw.eta_seconds ?? 0,
            peersConnected: 0,
        },
    };
}
