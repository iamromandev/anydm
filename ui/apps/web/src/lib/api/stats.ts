/**
 * The API's traffic statistics: what was downloaded and uploaded, added up by
 * day, kind of source or provider, and the hourly speed beside it.
 */

import { getApi } from "./client";

export type StatGroup = "day" | "kind" | "provider";

export type StatRow = {
    /** The day (`2026-10-11`), the kind (`direct`, `site`, `torrent`) or the provider's slug. */
    key: string;
    /** The provider's name; null for a day, a kind, and traffic whose provider was deleted. */
    name: string | null;
    downloadedSize: number;
    uploadedSize: number;
    finished: number;
    failed: number;
    removed: number;
};

export type SpeedPoint = {
    /** The start of the UTC hour, as an ISO timestamp. */
    hour: string;
    averageBps: number;
    maximumBps: number;
};

export type Stats = {
    group: StatGroup;
    start: string;
    end: string;
    groups: StatRow[];
    speeds: SpeedPoint[];
};

const GROUPS: StatGroup[] = [
    "day",
    "kind",
    "provider",
];

const isCount = (value: unknown): value is number =>
    typeof value === "number" && Number.isFinite(value) && value >= 0;

function parseRow(raw: any): StatRow | null {
    if (typeof raw?.key !== "string") return null;
    const counts = [
        raw.downloaded_size,
        raw.uploaded_size,
        raw.finished,
        raw.failed,
        raw.removed,
    ];
    if (!counts.every(isCount)) return null;
    const [
        downloadedSize,
        uploadedSize,
        finished,
        failed,
        removed,
    ] = counts as number[];
    return {
        key: raw.key,
        name: typeof raw.name === "string" && raw.name !== "" ? raw.name : null,
        downloadedSize,
        uploadedSize,
        finished,
        failed,
        removed,
    };
}

function parseSpeed(raw: any): SpeedPoint | null {
    if (typeof raw?.hour !== "string" || Number.isNaN(Date.parse(raw.hour))) {
        return null;
    }
    if (!isCount(raw.average_bps) || !isCount(raw.maximum_bps)) return null;
    return {
        hour: raw.hour,
        averageBps: raw.average_bps,
        maximumBps: raw.maximum_bps,
    };
}

/** A `/stats` answer, or `null` when any part of it is not one: a wrong number is worse than none. */
export function parseStats(raw: unknown): Stats | null {
    const data = raw as any;
    if (typeof data !== "object" || data === null) return null;
    if (!GROUPS.includes(data.group)) return null;
    if (typeof data.start !== "string" || typeof data.end !== "string") {
        return null;
    }
    if (!Array.isArray(data.groups) || !Array.isArray(data.speeds)) return null;
    const groups = data.groups.map(parseRow);
    const speeds = data.speeds.map(parseSpeed);
    if (groups.includes(null) || speeds.includes(null)) return null;
    return {
        group: data.group,
        start: data.start,
        end: data.end,
        groups: groups as StatRow[],
        speeds: speeds as SpeedPoint[],
    };
}

/** The traffic from `start` to `end` (UTC days, both included), added up by `group`. */
export async function getStats(
    start: string,
    end: string,
    group: StatGroup,
): Promise<Stats> {
    const query = new URLSearchParams({ from: start, to: end, group });
    const stats = parseStats(await getApi<unknown>(`/stats?${query}`));
    if (stats === null) throw new Error("The API's statistics were malformed");
    return stats;
}
