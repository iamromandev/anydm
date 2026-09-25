/** Where a download was left in the player, kept by the API so it resumes on any device (#96). */

import { putApi } from "./client";
import { normalizePosition, type PositionView } from "./task";

/** How often the player saves while it plays, as well as on pause and on close. */
export const POSITION_SAVE_INTERVAL_MS = 10_000;

export async function savePosition(
    taskId: string,
    fileIndex: number | null,
    positionSeconds: number,
    durationSeconds: number,
): Promise<PositionView> {
    return normalizePosition(
        await putApi<any>(`/download/${taskId}/position`, {
            ...(fileIndex === null ? {} : { file_index: fileIndex }),
            position_seconds: positionSeconds,
            duration_seconds: durationSeconds,
        }),
    );
}
