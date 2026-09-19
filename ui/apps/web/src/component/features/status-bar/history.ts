/**
 * The rolling speed samples behind the footer's two sparklines.
 *
 * A sparkline is a time series, so samples are taken on a clock rather than on
 * every change: the x axis assumes evenly spaced points, and an event-driven
 * sample would space them by however often the API happened to report.
 */

/** Samples kept, and so the width of the window the sparkline draws. */
export const HISTORY_LIMIT = 48;

export type SpeedHistory = {
    download: number[];
    upload: number[];
    /** The tallest sample in either series, and never below 1. */
    max: number;
};

export function emptyHistory(): SpeedHistory {
    return { download: [], upload: [], max: 1 };
}

/**
 * One sample of both series.
 *
 * `max` stays at least 1 so an idle meter draws a flat line along the bottom
 * instead of dividing by zero.
 */
export function appendSample(
    history: SpeedHistory,
    download: number,
    upload: number,
): SpeedHistory {
    const nextDownload = [
        ...history.download,
        download,
    ].slice(-HISTORY_LIMIT);
    const nextUpload = [
        ...history.upload,
        upload,
    ].slice(-HISTORY_LIMIT);

    return {
        download: nextDownload,
        upload: nextUpload,
        max: Math.max(1, ...nextDownload, ...nextUpload),
    };
}
