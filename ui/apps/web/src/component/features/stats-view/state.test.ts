import { describe, expect, it } from "bun:test";
import type { StatRow } from "@/lib/api/stats";
import {
    dayLabel,
    emptyStatsView,
    fillDays,
    groupLabel,
    hourLabel,
    rangeDates,
    speedIsPartial,
    speedSeries,
    topGroups,
    totalsOf,
} from "./state";

const row = (key: string, down: number, up = 0, name: string | null = null) =>
    ({
        key,
        name,
        downloadedSize: down,
        uploadedSize: up,
        finished: 1,
        failed: 0,
        removed: 0,
    }) satisfies StatRow;

describe("rangeDates", () => {
    const now = new Date("2026-10-11T23:30:00Z");

    it("ends on today's UTC day and counts it", () => {
        expect(rangeDates("7d", now)).toEqual({
            start: "2026-10-05",
            end: "2026-10-11",
        });
        expect(rangeDates("30d", now)).toEqual({
            start: "2026-09-12",
            end: "2026-10-11",
        });
    });

    it("takes the UTC day, not the local one", () => {
        const late = new Date("2026-10-11T00:10:00+06:00");
        expect(rangeDates("7d", late).end).toBe("2026-10-10");
    });

    it("reaches across a year end", () => {
        expect(rangeDates("7d", new Date("2027-01-02T10:00:00Z"))).toEqual({
            start: "2026-12-27",
            end: "2027-01-02",
        });
    });
});

describe("fillDays", () => {
    it("gives a day with no traffic as zeros, in order", () => {
        const days = fillDays("2026-10-09", "2026-10-12", [
            row("2026-10-12", 5, 1),
            row("2026-10-10", 7),
        ]);
        expect(days).toEqual([
            { day: "2026-10-09", downloadedSize: 0, uploadedSize: 0 },
            { day: "2026-10-10", downloadedSize: 7, uploadedSize: 0 },
            { day: "2026-10-11", downloadedSize: 0, uploadedSize: 0 },
            { day: "2026-10-12", downloadedSize: 5, uploadedSize: 1 },
        ]);
    });

    it("is one day for a one-day range", () => {
        expect(fillDays("2026-10-09", "2026-10-09", [])).toHaveLength(1);
    });
});

describe("totalsOf", () => {
    it("adds every count", () => {
        const a = { ...row("a", 10, 2), failed: 1, removed: 3 };
        expect(
            totalsOf([
                a,
                row("b", 5, 1),
            ]),
        ).toEqual({
            downloadedSize: 15,
            uploadedSize: 3,
            finished: 2,
            failed: 1,
            removed: 3,
        });
        expect(totalsOf([]).downloadedSize).toBe(0);
    });
});

describe("groupLabel", () => {
    it("spells a kind out and names a provider", () => {
        expect(groupLabel(row("site", 1), "kind")).toBe("Sites");
        expect(groupLabel(row("direct", 1), "kind")).toBe("Direct links");
        expect(groupLabel(row("youtube", 1, 0, "Youtube"), "provider")).toBe(
            "Youtube",
        );
        expect(groupLabel(row("unknown", 1), "provider")).toBe(
            "Deleted provider",
        );
        expect(groupLabel(row("nyaa", 1), "provider")).toBe("nyaa");
    });
});

describe("topGroups", () => {
    it("orders by what moved, down and up together, with each one's share", () => {
        const top = topGroups(
            [
                row("a", 10, 0, "A"),
                row("b", 60, 10, "B"),
                row("c", 20, 0, "C"),
            ],
            "provider",
            5,
        );
        expect(top.map((t) => t.label)).toEqual([
            "B",
            "C",
            "A",
        ]);
        expect(top[0].share).toBeCloseTo(0.7);
        expect(top.reduce((sum, t) => sum + t.share, 0)).toBeCloseTo(1);
    });

    it("folds what is past the limit into Other, and leaves out what moved nothing", () => {
        const top = topGroups(
            [
                row("a", 40, 0, "A"),
                row("b", 30, 0, "B"),
                row("c", 20, 0, "C"),
                row("d", 10, 0, "D"),
                row("e", 0, 0, "E"),
            ],
            "provider",
            2,
        );
        expect(
            top.map((t) => [
                t.label,
                t.size,
            ]),
        ).toEqual([
            [
                "A",
                40,
            ],
            [
                "B",
                30,
            ],
            [
                "Other",
                30,
            ],
        ]);
    });

    it("is empty when nothing moved", () => {
        expect(
            topGroups(
                [
                    row("a", 0),
                ],
                "kind",
                3,
            ),
        ).toEqual([]);
        expect(topGroups([], "kind", 3)).toEqual([]);
    });
});

describe("speedSeries", () => {
    it("is oldest first", () => {
        const series = speedSeries([
            {
                hour: "2026-10-11T10:00:00Z",
                averageBps: 2,
                maximumBps: 4,
            },
            {
                hour: "2026-10-11T09:00:00Z",
                averageBps: 1,
                maximumBps: 3,
            },
        ]);
        expect(series.hours).toEqual([
            "2026-10-11T09:00:00Z",
            "2026-10-11T10:00:00Z",
        ]);
        expect(series.average).toEqual([
            1,
            2,
        ]);
        expect(series.maximum).toEqual([
            3,
            4,
        ]);
    });
});

describe("labels", () => {
    it("writes a day and an hour in UTC", () => {
        expect(dayLabel("2026-10-05")).toBe("Oct 5");
        expect(hourLabel("2026-10-11T09:00:00Z")).toBe("Oct 11 09:00");
    });

    it("knows a range longer than the speed that is kept", () => {
        expect(speedIsPartial("7d")).toBe(false);
        expect(speedIsPartial("30d")).toBe(false);
        expect(speedIsPartial("90d")).toBe(true);
    });
});

describe("emptyStatsView", () => {
    it("starts on 30 days with nothing loaded", () => {
        const state = emptyStatsView();
        expect(state.range).toBe("30d");
        expect(state.days).toBeNull();
        expect(state.loading).toBe(false);
    });
});
