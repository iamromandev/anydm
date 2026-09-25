/** Start, and stop, an ephemeral on-demand HLS session for a site page, media URL or torrent. */

import { deleteApi, getApi, postApi } from "./client";

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
    fileIndex: number | null = null,
): { url: string } | { torrent: string; file_index?: number } {
    if (!isTorrentKind(kind)) return { url: value };
    // A torrent's file, when one is named (#98); its largest otherwise.
    return fileIndex === null
        ? { torrent: value }
        : { torrent: value, file_index: fileIndex };
}

export async function startStream(
    value: string,
    kind: string,
    fileIndex: number | null = null,
): Promise<StreamSession> {
    return normalizeStreamSession(
        await postApi<any>(
            "/stream/start",
            buildStreamStartBody(value, kind, fileIndex),
        ),
    );
}

export function stopStream(sessionId: string): Promise<void> {
    return deleteApi(`/stream/${sessionId}`);
}

/** A finished download's file, as the player needs it to choose how to play it (#94). */
export type MediaInfo = {
    /** A torrent's file; `null` for a download's one file. */
    fileIndex: number | null;
    filename: string;
    durationSeconds: number;
    hasVideo: boolean;
    /** What `canPlayType` is asked about; `null` when no browser plays it from a file. */
    mediaType: string | null;
    /** The file itself, served with Range. */
    fileUrl: string;
};

export function normalizeMediaInfo(raw: any): MediaInfo {
    return {
        // The envelope leaves out what is null.
        fileIndex: raw?.file_index ?? null,
        filename: raw?.filename ?? "",
        durationSeconds: raw?.duration_seconds ?? 0,
        hasVideo: raw?.has_video ?? true,
        mediaType: raw?.media_type ?? null,
        fileUrl: raw?.file_url ?? "",
    };
}

export async function fetchMediaInfo(
    taskId: string,
    fileIndex: number | null,
): Promise<MediaInfo> {
    const query = fileIndex === null ? "" : `?file_index=${fileIndex}`;
    return normalizeMediaInfo(
        await getApi<any>(`/download/${taskId}/media${query}`),
    );
}

/**
 * The file itself when the browser says it can play it, else a session. Any
 * answer but "" counts: #93 found Chrome's exact and WebKit's too eager, and
 * `nativeFailed` catches the second.
 */
export function choosePlayback(
    media: Pick<MediaInfo, "mediaType">,
    canPlayType: (type: string) => string,
): "native" | "session" {
    return media.mediaType && canPlayType(media.mediaType) !== ""
        ? "native"
        : "session";
}

/**
 * How long a file gets to load its first frame before a session takes over.
 * A file on the same server loads in well under a second; this only catches
 * one that never will.
 */
export const NATIVE_LOAD_TIMEOUT_MS = 8000;

/**
 * Whether the file failed to play itself, so a session should take over:
 * the element errored, it loaded and shows no picture it should have, or it
 * never loaded at all (`stalled`, after `NATIVE_LOAD_TIMEOUT_MS`). WebKit
 * answers "probably" to VP9, then draws nothing (#93), or, for VP9 in WebM,
 * fires neither `loadeddata` nor `error` (#94).
 */
export function nativeFailed(state: {
    errored: boolean;
    hasVideo: boolean;
    videoWidth: number;
    stalled?: boolean;
}): boolean {
    return (
        state.errored ||
        Boolean(state.stalled) ||
        (state.hasVideo && state.videoWidth === 0)
    );
}

export function buildTaskStreamBody(
    taskId: string,
    fileIndex: number | null,
): { task_id: string; file_index?: number } {
    return fileIndex === null
        ? { task_id: taskId }
        : { task_id: taskId, file_index: fileIndex };
}

/** A session that reads a finished download's file from disk. */
export async function startTaskStream(
    taskId: string,
    fileIndex: number | null,
): Promise<StreamSession> {
    return normalizeStreamSession(
        await postApi<any>(
            "/stream/start",
            buildTaskStreamBody(taskId, fileIndex),
        ),
    );
}
