/**
 * What a card shows when it is opened up.
 *
 * Everything here comes from the task row the list already holds; opening a
 * card fetches nothing. The rows are built as data rather than markup so the
 * rules about what appears when are testable, and so the card stays a list of
 * label-and-value pairs rather than a thicket of conditionals.
 */

import type { UiTask } from "./task";

export type DetailRow = {
    label: string;
    value: string;
    /** The full text behind a shortened value, offered for copying. */
    copy?: string;
    /** A more precise reading of the value, for a tooltip. */
    title?: string;
};

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** How long ago, in the largest unit that is not a fraction. */
export function relativeTime(at: number, now: number): string {
    const ago = Math.max(0, now - at);
    if (ago < 1000) return "just now";
    if (ago < MINUTE) return `${Math.floor(ago / 1000)}s ago`;
    if (ago < HOUR) return `${Math.floor(ago / MINUTE)}m ago`;
    if (ago < DAY) return `${Math.floor(ago / HOUR)}h ago`;
    return `${Math.floor(ago / DAY)}d ago`;
}

function formatBytes(bytes: number): string {
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit += 1;
    }
    return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function time(
    rows: DetailRow[],
    label: string,
    at: number | undefined,
    now: number,
) {
    if (at === undefined) return;
    rows.push({
        label,
        value: relativeTime(at, now),
        title: new Date(at).toISOString(),
    });
}

export function detailRows(task: UiTask, now: number): DetailRow[] {
    const rows: DetailRow[] = [
        { label: "Source", value: task.url, copy: task.url },
    ];

    const type = [
        task.platform,
        task.kind,
        task.preset,
    ].filter(Boolean);
    if (type.length) rows.push({ label: "Type", value: type.join(" · ") });

    time(rows, "Added", task.createdAt, now);
    time(rows, "Started", task.startedAt, now);
    time(rows, "Finished", task.completedAt, now);

    if (task.filename) {
        const size = task.fileSize ? ` · ${formatBytes(task.fileSize)}` : "";
        rows.push({ label: "File", value: `${task.filename}${size}` });
    }

    if (task.infoHash) {
        rows.push({
            label: "Info hash",
            value: task.infoHash,
            copy: task.infoHash,
        });
    }

    // One attempt is just "it ran"; two or more is the story.
    if (task.attempts > 1) {
        const budget = task.maxAttempts ? ` of ${task.maxAttempts}` : "";
        rows.push({ label: "Attempts", value: `${task.attempts}${budget}` });
    }

    if (task.error) {
        rows.push({
            label: "Error",
            value: task.errorCode
                ? `${task.errorCode}: ${task.error}`
                : task.error,
        });
    }

    return rows;
}
