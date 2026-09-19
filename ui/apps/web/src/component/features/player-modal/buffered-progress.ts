export interface BufferedRange {
    start: number;
    end: number;
}

export interface BufferedProgress {
    ranges: BufferedRange[];
    currentTime: number;
    duration: number;
}

/** Percent of the video buffered ahead of the playhead, from the buffered
 * range that actually contains it (ranges before/after a gap don't count —
 * the player can't play through a gap without stalling). */
export function getBufferedPercent(progress: BufferedProgress): number {
    const { ranges, currentTime, duration } = progress;
    if (!(duration > 0)) {
        return 0;
    }

    const activeRange = ranges.find(
        (range) => range.start <= currentTime && currentTime <= range.end,
    );
    if (!activeRange) {
        return 0;
    }

    return Math.min(100, (activeRange.end / duration) * 100);
}
