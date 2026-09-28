/**
 * The picker's ticks (v0.5): which of a listing's videos become the group.
 *
 * A `Set` of entry indices, always replaced rather than changed in place, so
 * the signal holding it re-renders.
 */

import { entryLabel, type PlaylistEntry } from "./api/playlist";
import { PRESET_OPTIONS } from "./prefs";

/** Ticked unless a task already has it, or the site won't serve it. */
export function isSelectable(entry: PlaylistEntry): boolean {
    return entry.available && entry.have === null;
}

/** The ticks after a batch arrives: its selectable videos, ticked. */
export function tickArrivals(
    selected: ReadonlySet<number>,
    batch: readonly PlaylistEntry[],
): Set<number> {
    const next = new Set(selected);
    for (const entry of batch) {
        if (isSelectable(entry)) next.add(entry.index);
    }
    return next;
}

export function toggle(
    selected: ReadonlySet<number>,
    entry: PlaylistEntry,
): Set<number> {
    const next = new Set(selected);
    if (!isSelectable(entry)) return next;
    if (next.has(entry.index)) next.delete(entry.index);
    else next.add(entry.index);
    return next;
}

/**
 * Shift-click: every selectable row shown between the last one clicked and
 * this one takes this one's new state.
 */
export function toggleRange(
    selected: ReadonlySet<number>,
    shown: readonly PlaylistEntry[],
    anchor: number,
    entry: PlaylistEntry,
): Set<number> {
    const on = !selected.has(entry.index);
    const low = Math.min(anchor, entry.index);
    const high = Math.max(anchor, entry.index);
    const next = new Set(selected);
    for (const row of shown) {
        if (row.index < low || row.index > high || !isSelectable(row)) continue;
        if (on) next.add(row.index);
        else next.delete(row.index);
    }
    return next;
}

/** All or None, over the rows the filter shows. Hidden rows keep their ticks. */
export function setAll(
    selected: ReadonlySet<number>,
    shown: readonly PlaylistEntry[],
    on: boolean,
): Set<number> {
    const next = new Set(selected);
    for (const row of shown) {
        if (!isSelectable(row)) continue;
        if (on) next.add(row.index);
        else next.delete(row.index);
    }
    return next;
}

/** The rows whose title holds the query, in any case. */
export function filterEntries(
    entries: PlaylistEntry[],
    query: string,
): PlaylistEntry[] {
    const needle = query.trim().toLowerCase();
    if (!needle) return entries;
    return entries.filter((entry) =>
        entryLabel(entry).toLowerCase().includes(needle),
    );
}

function roughDuration(seconds: number): string {
    if (seconds >= 3600) return `${Math.round(seconds / 3600)} h`;
    return `${Math.max(1, Math.round(seconds / 60))} min`;
}

/** "88 of 96 selected · about 71 h". */
export function selectionSummary(
    entries: readonly PlaylistEntry[],
    selected: ReadonlySet<number>,
): string {
    let ticked = 0;
    let seconds = 0;
    for (const entry of entries) {
        if (!selected.has(entry.index)) continue;
        ticked += 1;
        seconds += entry.duration ?? 0;
    }
    const line = `${ticked.toLocaleString("en-US")} of ${entries.length.toLocaleString("en-US")} selected`;
    return seconds > 0 ? `${line} · about ${roughDuration(seconds)}` : line;
}

/** What a height preset does to a video without that height. The API treats it as a ceiling. */
export function presetHint(preset: string): string {
    if (!/^\d+$/.test(preset)) return "";
    const label = PRESET_OPTIONS.find(
        (option) => option.value === preset,
    )?.label;
    return label ? `Videos without ${label} get the closest below` : "";
}

/** "Tick newest N": a tab lists newest first, so the first N not held (part 3). */
export function tickNewest(
    entries: readonly PlaylistEntry[],
    n: number,
): Set<number> {
    const next = new Set<number>();
    for (const entry of entries) {
        if (next.size >= n) break;
        if (isSelectable(entry)) next.add(entry.index);
    }
    return next;
}

/** "Uploaded after": everything selectable from `since` (seconds) on. Undated videos aren't. */
export function tickUploadedAfter(
    entries: readonly PlaylistEntry[],
    since: number,
): Set<number> {
    const next = new Set<number>();
    for (const entry of entries) {
        if (
            isSelectable(entry) &&
            entry.timestamp !== null &&
            entry.timestamp >= since
        ) {
            next.add(entry.index);
        }
    }
    return next;
}

/** The date filter shows only for a listing that has dates to filter by. */
export function hasDates(entries: readonly PlaylistEntry[]): boolean {
    return entries.some((entry) => entry.timestamp !== null);
}

const plural = (n: number, unit: string) =>
    `~${n} ${unit}${n === 1 ? "" : "s"} ago`;

/**
 * "~3 weeks ago". YouTube gives only "3 weeks ago", which yt-dlp turns into a
 * timestamp, so the tilde says it's no more precise than that.
 */
export function approxAge(timestamp: number, now: number): string {
    const days = Math.floor((now / 1000 - timestamp) / 86_400);
    if (days < 1) return "~today";
    if (days < 14) return plural(days, "day");
    if (days < 60) return plural(Math.round(days / 7), "week");
    if (days < 365) return plural(Math.round(days / 30), "month");
    return plural(Math.floor(days / 365), "year");
}
