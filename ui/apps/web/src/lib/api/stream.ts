/** Start, and stop, an ephemeral on-demand HLS session for a direct media URL. */

import { deleteApi, postApi } from "./client";

export type StreamSession = {
    sessionId: string;
    playlistUrl: string;
    durationSeconds: number;
    hasVideo: boolean;
};

export function normalizeStreamSession(raw: any): StreamSession {
    return {
        sessionId: raw?.session_id ?? "",
        playlistUrl: raw?.playlist_url ?? "",
        durationSeconds: raw?.duration_seconds ?? 0,
        hasVideo: raw?.has_video ?? false,
    };
}

export async function startStream(url: string): Promise<StreamSession> {
    return normalizeStreamSession(await postApi<any>("/stream/start", { url }));
}

export function stopStream(sessionId: string): Promise<void> {
    return deleteApi(`/stream/${sessionId}`);
}
