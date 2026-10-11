/**
 * What the Stats view shows, decided without the DOM so it is tested with plain values.
 */

import type { SpeedPoint, StatGroup, StatRow, Stats } from "@/lib/api/stats";

export type RangeId = "7d" | "30d" | "90d";

export const RANGES: Array<{ id: RangeId; label: string; days: number }> = [
    { id: "7d", label: "7 days", days: 7 },
    { id: "30d", label: "30 days", days: 30 },
    { id: "90d", label: "90 days", days: 90 },
];

export const DEFAULT_RANGE: RangeId = "30d";

/** The API keeps hourly speed for this many days; a longer range has none before that. */
export const SPEED_DAYS = 30;

const DAY_MS = 86_400_000;

export type StatsViewState = {
    range: RangeId;
    loading: boolean;
    failure: string | null;
    /** The same range added up three ways; null until the first answer. */
    days: Stats | null;
    providers: Stats | null;
    kinds: Stats | null;
};

export function emptyStatsView(): StatsViewState {
    return {
        range: DEFAULT_RANGE,
        loading: false,
        failure: null,
        days: null,
        providers: null,
        kinds: null,
    };
}

/** A date as the API reads it: its UTC day, `2026-10-11`. */
export function isoDay(date: Date): string {
    return date.toISOString().slice(0, 10);
}

/** The days a range covers, ending on `now`'s UTC day, both ends included. */
export function rangeDates(
    id: RangeId,
    now: Date,
): { start: string; end: string } {
    const days = RANGES.find((r) => r.id === id)?.days ?? 30;
    const end = Date.UTC(
        now.getUTCFullYear(),
        now.getUTCMonth(),
        now.getUTCDate(),
    );
    return {
        start: isoDay(new Date(end - (days - 1) * DAY_MS)),
        end: isoDay(new Date(end)),
    };
}

export type DayPoint = {
    day: string;
    downloadedSize: number;
    uploadedSize: number;
};

/** Every day from `start` to `end`, a day with no traffic as zeros, so the bars have no gaps. */
export function fillDays(
    start: string,
    end: string,
    rows: StatRow[],
): DayPoint[] {
    const byDay = new Map(
        rows.map((row) => [
            row.key,
            row,
        ]),
    );
    const points: DayPoint[] = [];
    const last = Date.parse(end);
    for (let at = Date.parse(start); at <= last; at += DAY_MS) {
        const day = isoDay(new Date(at));
        const row = byDay.get(day);
        points.push({
            day,
            downloadedSize: row?.downloadedSize ?? 0,
            uploadedSize: row?.uploadedSize ?? 0,
        });
    }
    return points;
}

export type Totals = {
    downloadedSize: number;
    uploadedSize: number;
    finished: number;
    failed: number;
    removed: number;
};

export function totalsOf(rows: StatRow[]): Totals {
    return rows.reduce<Totals>(
        (sum, row) => ({
            downloadedSize: sum.downloadedSize + row.downloadedSize,
            uploadedSize: sum.uploadedSize + row.uploadedSize,
            finished: sum.finished + row.finished,
            failed: sum.failed + row.failed,
            removed: sum.removed + row.removed,
        }),
        {
            downloadedSize: 0,
            uploadedSize: 0,
            finished: 0,
            failed: 0,
            removed: 0,
        },
    );
}

const KIND_LABELS: Record<string, string> = {
    direct: "Direct links",
    site: "Sites",
    torrent: "Torrents",
};

/** A group's name in words: a provider's own name, a kind spelled out. */
export function groupLabel(row: StatRow, group: StatGroup): string {
    if (group === "kind") return KIND_LABELS[row.key] ?? row.key;
    if (row.name) return row.name;
    return row.key === "unknown" ? "Deleted provider" : row.key;
}

export type TopItem = {
    key: string;
    label: string;
    /** Bytes moved, down and up together. */
    size: number;
    /** Its part of everything moved, 0 to 1. */
    share: number;
};

/** The groups that moved the most, biggest first; the rest are folded into one "Other". */
export function topGroups(
    rows: StatRow[],
    group: StatGroup,
    limit: number,
): TopItem[] {
    const items = rows
        .map((row) => ({
            key: row.key,
            label: groupLabel(row, group),
            size: row.downloadedSize + row.uploadedSize,
        }))
        .filter((item) => item.size > 0)
        .sort((a, b) => b.size - a.size);
    const total = items.reduce((sum, item) => sum + item.size, 0);
    const shown = items.slice(0, limit);
    const rest = items.slice(limit).reduce((sum, item) => sum + item.size, 0);
    if (rest > 0) shown.push({ key: "other", label: "Other", size: rest });
    return shown.map((item) => ({ ...item, share: item.size / total }));
}

export type SpeedSeries = {
    /** The start of each hour, as an ISO timestamp. */
    hours: string[];
    average: number[];
    maximum: number[];
};

/** Hourly speed, oldest first. */
export function speedSeries(points: SpeedPoint[]): SpeedSeries {
    const sorted = [
        ...points,
    ].sort((a, b) => a.hour.localeCompare(b.hour));
    return {
        hours: sorted.map((p) => p.hour),
        average: sorted.map((p) => p.averageBps),
        maximum: sorted.map((p) => p.maximumBps),
    };
}

const MONTHS = [
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
];

/** `2026-10-11` as `Oct 11`. */
export function dayLabel(day: string): string {
    const [
        ,
        month,
        date,
    ] = day.split("-").map(Number);
    return `${MONTHS[month - 1]} ${date}`;
}

/** `2026-10-11T09:00:00Z` as `Oct 11 09:00` (UTC, as the hours are cut). */
export function hourLabel(hour: string): string {
    const at = new Date(hour);
    const time = at.toISOString().slice(11, 16);
    return `${dayLabel(isoDay(at))} ${time}`;
}

/** Whether a range reaches back past what speed is kept for. */
export function speedIsPartial(id: RangeId): boolean {
    return (RANGES.find((r) => r.id === id)?.days ?? 0) > SPEED_DAYS;
}
