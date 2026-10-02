/** Where a file was left in the player, kept by the API so it resumes on any device (#96). */

import { putApi } from "./client";
import { normalizePlayback, type PositionView } from "./download";

/** How often the player saves while it plays, as well as on pause and on close. */
export const POSITION_SAVE_INTERVAL_MS = 10_000;

export async function savePosition(
    taskId: string,
    fileIndex: number | null,
    positionSeconds: number,
    durationSeconds: number,
): Promise<PositionView> {
    // Every download's files are indexed; a single download's one file is 0.
    const index = fileIndex ?? 0;
    return normalizePlayback(
        await putApi<any>(`/download/${taskId}/file/${index}/playback`, {
            position_seconds: positionSeconds,
            duration_seconds: durationSeconds,
        }),
        index,
    );
}
