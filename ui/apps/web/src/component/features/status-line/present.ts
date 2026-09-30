/**
 * What the downloads' status line says, decided without the DOM so it is tested with plain values.
 */

import { formatSpeed } from "@/component/core/utils";
import type { SidebarCounts, SidebarFilter } from "@/component/layouts/sidebar";

export type StatusPart = { id: SidebarFilter; text: string };

/**
 * The line's parts, All first: "All 7", "3 active", "2 seeding", "2 completed".
 * A group with nothing in it is left out, except the one being looked at, so
 * the filter you chose never vanishes from under you.
 */
export function statusParts(
    counts: SidebarCounts,
    current: SidebarFilter,
): StatusPart[] {
    const parts: StatusPart[] = [
        { id: "all", text: `All ${counts.all}` },
    ];
    const groups: Array<
        [
            SidebarFilter,
            number,
            string,
        ]
    > = [
        [
            "downloading",
            counts.downloading,
            "active",
        ],
        [
            "seeding",
            counts.seeding,
            "seeding",
        ],
        [
            "completed",
            counts.completed,
            "completed",
        ],
    ];
    for (const [
        id,
        count,
        label,
    ] of groups) {
        if (count > 0 || id === current) {
            parts.push({ id, text: `${count} ${label}` });
        }
    }
    return parts;
}

/** "↓ 12.4 MB/s · ↑ 512.0 KB/s"; empty when nothing moves. */
export function speedText(down: number, up: number): string {
    if (down <= 0 && up <= 0) return "";
    return `↓ ${formatSpeed(down)} · ↑ ${formatSpeed(up)}`;
}

/** The list's name for a screen reader; the line itself is what a sighted person reads. */
export function titleFor(filter: string): string {
    switch (filter) {
        case "downloading":
            return "Active downloads";
        case "seeding":
            return "Seeding";
        case "completed":
            return "Completed";
        default:
            return "Recent downloads";
    }
}
