/** Start, and stop, an ephemeral on-demand HLS session for a direct media URL. */

import { deleteApi, postApi } from "./client";

export type StreamSession = {
    sessionId: string;
    playlistUrl: string;
    status: string;
    durationSeconds: number | null;
    hasVideo: boolean | null;
};

/** One `stream_status` SSE payload — peer/speed/progress fields are only
 * present on torrent-backed sessions, and keep arriving through playback. */
export type StreamStatusEvent = {
    id: string;
    status: string;
    message?: string;
    peersConnected?: number;
    downloadBps?: number;
    progressBytes?: number;
    totalBytes?: number;
};

export function normalizeStreamStatusEvent(raw: any): StreamStatusEvent {
    return {
        id: raw?.id ?? "",
        status: raw?.status ?? "",
        message: raw?.message,
        peersConnected: raw?.peers_connected,
        downloadBps: raw?.download_bps,
        progressBytes: raw?.progress_bytes,
        totalBytes: raw?.total_bytes,
    };
}

export function normalizeStreamSession(raw: any): StreamSession {
    return {
        sessionId: raw?.session_id ?? "",
        playlistUrl: raw?.playlist_url ?? "",
        status: raw?.status ?? "ready",
        durationSeconds:
            raw?.duration_seconds ?? (raw?.status === "connecting" ? null : 0),
        hasVideo:
            raw?.has_video ?? (raw?.status === "connecting" ? null : false),
    };
}

export function isTorrentKind(kind: string): boolean {
    return kind === "magnet" || kind === "torrent";
}

export function buildStreamStartBody(
    value: string,
    kind: string,
): { url: string } | { torrent: string } {
    return isTorrentKind(kind) ? { torrent: value } : { url: value };
}

export async function startStream(
    value: string,
    kind: string,
): Promise<StreamSession> {
    return normalizeStreamSession(
        await postApi<any>("/stream/start", buildStreamStartBody(value, kind)),
    );
}

export function stopStream(sessionId: string): Promise<void> {
    return deleteApi(`/stream/${sessionId}`);
}
