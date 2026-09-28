/**
 * Playlists as groups (v0.5): where a stream frame's rows go, a group's live
 * speed, what its card says, and the one toast it raises.
 *
 * A group is a task row that owns tasks. Its videos never enter the list:
 * their frames go to the group's open Entries list and to its speed.
 */

import { formatBytes } from "@/component/core/utils";
import { PRESET_OPTIONS } from "@/lib/prefs";
import type { ToastTone } from "@/lib/toast";
import { siteName } from "./site";
import { statusView, type EntryCounts, type UiTask } from "./task";

const count = (n: number) => n.toLocaleString("en-US");

/**
 * The list after a `task` or `tasks` frame.
 *
 * A row already held is updated where it is, so a group's every recount
 * doesn't jump it to the top; only rows new to the list go on top. A group's
 * videos never enter it, and a canceled row leaves it: cancelling publishes
 * the row it just removed.
 */
export function placeRows(held: UiTask[], rows: UiTask[]): UiTask[] {
    const top = rows.filter((row) => row.parentId === undefined);
    const removed = new Set(
        top.filter((row) => row.status === "canceled").map((row) => row.id),
    );
    const live = new Map(
        top
            .filter((row) => row.status !== "canceled")
            .map((row) => [
                row.id,
                row,
            ]),
    );
    const heldIds = new Set(held.map((row) => row.id));
    const fresh = [
        ...live.values(),
    ].filter((row) => !heldIds.has(row.id));
    const updated = held
        .filter((row) => !removed.has(row.id))
        .map((row) => live.get(row.id) ?? row);
    return [
        ...fresh,
        ...updated,
    ];
}

/** Each running video's speed, by video id, with the group it's in. */
export type VideoSpeeds = Record<string, { groupId: string; speed: number }>;

/** The speeds after one of a group's videos reported; 0 forgets it. */
export function trackVideoSpeed(
    speeds: VideoSpeeds,
    groupId: string,
    videoId: string,
    speed: number,
): VideoSpeeds {
    const next = { ...speeds };
    if (speed > 0) next[videoId] = { groupId, speed };
    else delete next[videoId];
    return next;
}

export function groupSpeed(speeds: VideoSpeeds, groupId: string): number {
    let sum = 0;
    for (const video of Object.values(speeds)) {
        if (video.groupId === groupId) sum += video.speed;
    }
    return sum;
}

/**
 * The list with each group's speed summed from its videos' progress frames.
 * The row's own `speed_bps` is only as fresh as its last recount. Unchanged
 * rows are returned as they were.
 */
export function withGroupSpeeds(
    tasks: UiTask[],
    speeds: VideoSpeeds,
): UiTask[] {
    return tasks.map((task) => {
        if (task.kind !== "playlist") return task;
        const speed = groupSpeed(speeds, task.id);
        return task.downloadSpeed === speed
            ? task
            : { ...task, downloadSpeed: speed };
    });
}

/** One group's open Entries list: the videos fetched so far, by position. */
export type EntriesView = {
    rows: UiTask[];
    /** The last page fetched; 0 before the first. */
    page: number;
    totalPages: number;
    loading: boolean;
};

export const EMPTY_ENTRIES: EntriesView = {
    rows: [],
    page: 0,
    totalPages: 1,
    loading: true,
};

/** A fetched page after what's held. A row already held keeps the stream's copy. */
export function addEntriesPage(
    view: EntriesView,
    rows: UiTask[],
    page: number,
    totalPages: number,
): EntriesView {
    const held = new Set(view.rows.map((row) => row.id));
    return {
        rows: [
            ...view.rows,
            ...rows.filter((row) => !held.has(row.id)),
        ],
        page,
        totalPages,
        loading: false,
    };
}

/** Open lists with these videos' rows swapped in place; a canceled video leaves. */
export function applyVideoRows(
    open: Record<string, EntriesView>,
    videos: UiTask[],
): Record<string, EntriesView> {
    let next = open;
    for (const video of videos) {
        const groupId = video.parentId;
        const view = groupId === undefined ? undefined : next[groupId];
        if (groupId === undefined || !view) continue;
        const rows =
            video.status === "canceled"
                ? view.rows.filter((row) => row.id !== video.id)
                : view.rows.map((row) => (row.id === video.id ? video : row));
        next = { ...next, [groupId]: { ...view, rows } };
    }
    return next;
}

/** A progress frame's numbers on its video's row. Absent fields are unchanged. */
export function applyVideoProgress(
    open: Record<string, EntriesView>,
    groupId: string,
    data: any,
): Record<string, EntriesView> {
    const view = open[groupId];
    if (!view) return open;
    const rows = view.rows.map((row) =>
        row.id === data.id
            ? {
                  ...row,
                  progress: data.progress ?? row.progress,
                  eta: data.eta_seconds ?? row.eta,
                  downloadedBytes: data.downloaded_bytes ?? row.downloadedBytes,
                  totalBytes: data.total_bytes ?? row.totalBytes,
                  downloadSpeed: data.speed_bps ?? row.downloadSpeed,
              }
            : row,
    );
    return { ...open, [groupId]: { ...view, rows } };
}

/** Open lists without a removed video. */
export function dropVideo(
    open: Record<string, EntriesView>,
    id: string,
): Record<string, EntriesView> {
    let changed = false;
    const next: Record<string, EntriesView> = {};
    for (const [
        groupId,
        view,
    ] of Object.entries(open)) {
        const rows = view.rows.filter((row) => row.id !== id);
        if (rows.length !== view.rows.length) changed = true;
        next[groupId] =
            rows.length === view.rows.length ? view : { ...view, rows };
    }
    return changed ? next : open;
}

/** "YouTube · 96 videos · 1080p". */
export function groupMeta(
    task: Pick<UiTask, "extractor" | "preset" | "entryCounts">,
): string {
    const total = task.entryCounts?.total ?? 0;
    const preset =
        PRESET_OPTIONS.find((option) => option.value === task.preset)?.label ??
        "";
    return [
        siteName(task.extractor),
        `${count(total)} ${total === 1 ? "video" : "videos"}`,
        preset,
    ]
        .filter(Boolean)
        .join(" · ");
}

/** "38 of 96 · 12.4 GB": videos finished, then bytes so far. */
export function groupProgressLine(
    task: Pick<UiTask, "entryCounts" | "downloadedBytes">,
): string {
    const done = task.entryCounts?.complete ?? 0;
    const total = task.entryCounts?.total ?? 0;
    const bytes =
        task.downloadedBytes > 0
            ? ` · ${formatBytes(task.downloadedBytes)}`
            : "";
    const watched = task.entryCounts?.watched ?? 0;
    const seen = watched > 0 ? ` · ${count(watched)} watched` : "";
    return `${count(done)} of ${count(total)}${bytes}${seen}`;
}

/** Frames leave the watched count out; the list's last value stands (spec: The list). */
export function keepWatched(rows: UiTask[], held: UiTask[]): UiTask[] {
    const prior = new Map(
        held.map((row) => [
            row.id,
            row,
        ]),
    );
    return rows.map((row) => {
        const counts = row.entryCounts;
        const last = prior.get(row.id)?.entryCounts?.watched;
        if (!counts || counts.watched !== undefined || last === undefined) {
            return row;
        }
        return { ...row, entryCounts: { ...counts, watched: last } };
    });
}

/** "2 downloading · 55 queued · 1 failed", leaving out what's zero. */
export function countsLine(counts: EntryCounts | undefined): string {
    if (!counts) return "";
    const parts: [
        number,
        string,
    ][] = [
        [
            counts.downloading,
            "downloading",
        ],
        [
            Math.max(0, counts.active - counts.downloading),
            "queued",
        ],
        [
            counts.paused,
            "paused",
        ],
        [
            counts.failed,
            "failed",
        ],
    ];
    return parts
        .filter(
            ([
                n,
            ]) => n > 0,
        )
        .map(
            ([
                n,
                word,
            ]) => `${count(n)} ${word}`,
        )
        .join(" · ");
}

/** An Entries row's status: its percentage while it downloads, else its status. */
export function videoStatus(
    video: Pick<UiTask, "status" | "progress">,
): string {
    return video.status === "downloading"
        ? `${Math.floor(video.progress)}%`
        : statusView(video.status).label;
}

/**
 * The one toast a group raises: when it ends. "Finished: 29C3, 95 of 96, 1
 * failed". Pausing isn't an end, and its videos raise nothing.
 */
export function groupToast(
    previous: string | undefined,
    task: Pick<UiTask, "status" | "title" | "entryCounts">,
): { tone: ToastTone; message: string } | null {
    if (previous === undefined || previous === task.status) return null;
    if (task.status !== "complete" && task.status !== "failed") return null;
    const counts = task.entryCounts;
    if (!counts) {
        return {
            tone: task.status === "complete" ? "success" : "error",
            message: `Finished: ${task.title}`,
        };
    }
    const failed = counts.failed ? `, ${count(counts.failed)} failed` : "";
    return {
        tone: counts.failed ? "error" : "success",
        message: `Finished: ${task.title}, ${count(counts.complete)} of ${count(counts.total)}${failed}`,
    };
}
