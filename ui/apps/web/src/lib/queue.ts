/**
 * What plays after what (v0.5 part 4): a picker's ticked videos, or a group's.
 * Pure, so the rules about skipping and where to start are tested here and the
 * route only swaps the player's source.
 */

import { entryLabel, type PlaylistEntry } from "./api/playlist";
import type { PositionView, UiTask } from "./api/task";

/** A finished download plays from its file; anything else from its page. */
export type QueueSource = { taskId: string } | { url: string };

export type QueueItem = {
    key: string;
    title: string;
    source: QueueSource;
    /** False for what can't play: failed, removed, or not served. */
    playable: boolean;
    /** Where a download's file was left; streams keep none. */
    positions?: PositionView[];
};

/** The next or previous playable item, or null at either end. */
export function adjacentItem(
    items: readonly QueueItem[],
    index: number,
    dir: 1 | -1,
): number | null {
    for (let at = index + dir; at >= 0 && at < items.length; at += dir) {
        if (items[at].playable) return at;
    }
    return null;
}

const isPartway = (item: QueueItem) =>
    (item.positions ?? []).some((p) => !p.watched && p.positionSeconds > 0);
const isWatched = (item: QueueItem) =>
    (item.positions ?? []).some((p) => p.watched);

/**
 * `pickFileToOpen`'s rule, over videos: the one left partway, else the first
 * not yet watched. Null when everything playable has been watched.
 */
export function resumeItem(items: readonly QueueItem[]): number | null {
    const partway = items.findIndex((i) => i.playable && isPartway(i));
    if (partway !== -1) return partway;
    const unwatched = items.findIndex((i) => i.playable && !isWatched(i));
    return unwatched === -1 ? null : unwatched;
}

/** Where Play all starts: `resumeItem`, else the first that plays. */
export function startItem(items: readonly QueueItem[]): number | null {
    const resume = resumeItem(items);
    if (resume !== null) return resume;
    const first = items.findIndex((i) => i.playable);
    return first === -1 ? null : first;
}

/** The picker's ticked entries, in listing order, each streamed from its page. */
export function queueFromEntries(
    entries: readonly PlaylistEntry[],
    selected: ReadonlySet<number>,
): QueueItem[] {
    return entries
        .filter((entry) => selected.has(entry.index))
        .map((entry) => ({
            key: entry.url,
            title: entryLabel(entry),
            source: { url: entry.url },
            playable: entry.available,
        }));
}

/** A group's videos: finished ones from their files, the rest from their page. */
export function queueFromVideos(videos: readonly UiTask[]): QueueItem[] {
    return videos.map((video) => ({
        key: video.id,
        title: video.title,
        source:
            video.status === "complete"
                ? { taskId: video.id }
                : { url: video.url },
        playable: video.status !== "failed" && video.status !== "canceled",
        positions: video.positions,
    }));
}
