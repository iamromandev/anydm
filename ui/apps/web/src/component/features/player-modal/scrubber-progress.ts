import { getBufferedPercent, type BufferedRange } from "./buffered-progress";

export interface ScrubberSegments {
    swarmPercent: number;
    bufferedPercent: number;
    playedPercent: number;
}

export interface ScrubberSegmentsInput {
    isTorrent: boolean;
    progressBytes: number;
    totalBytes: number;
    bufferedRanges: BufferedRange[];
    currentTime: number;
    duration: number;
}

/** Widths (0-100) for the three stacked seekbar segments: how much the swarm
 * has downloaded overall, how much is buffered/playable ahead of the
 * playhead, and how much has already played. */
export function scrubberSegments(
    input: ScrubberSegmentsInput,
): ScrubberSegments {
    const {
        isTorrent,
        progressBytes,
        totalBytes,
        bufferedRanges,
        currentTime,
        duration,
    } = input;

    const swarmPercent =
        isTorrent && totalBytes > 0
            ? Math.min(100, (progressBytes / totalBytes) * 100)
            : 0;

    const bufferedPercent = getBufferedPercent({
        ranges: bufferedRanges,
        currentTime,
        duration,
    });

    const playedPercent =
        duration > 0 ? Math.min(100, (currentTime / duration) * 100) : 0;

    return { swarmPercent, bufferedPercent, playedPercent };
}

export interface SeekRatioInput {
    clientX: number;
    rectLeft: number;
    rectWidth: number;
}

/** Converts a pointer x-position into a clamped 0-1 ratio along the track. */
export function seekRatioFromPointerX(input: SeekRatioInput): number {
    const { clientX, rectLeft, rectWidth } = input;
    if (rectWidth <= 0) {
        return 0;
    }
    const ratio = (clientX - rectLeft) / rectWidth;
    return Math.min(1, Math.max(0, ratio));
}

/** "m:ss", or "h:mm:ss" once past an hour — the clock-readout shape the time
 * display and hover tooltip need, distinct from core/utils.tsx's word-form
 * `formatTime` (used for the swarm ETA, e.g. "1m 0s"). */
export function formatClockTime(seconds: number): string {
    const safeSeconds =
        Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
    const h = Math.floor(safeSeconds / 3600);
    const m = Math.floor((safeSeconds % 3600) / 60);
    const s = safeSeconds % 60;
    const pad = (n: number) => String(n).padStart(2, "0");
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}
