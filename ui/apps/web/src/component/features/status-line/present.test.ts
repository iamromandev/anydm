import { describe, expect, it } from "bun:test";

import { speedText, statusParts, titleFor } from "./present";

const counts = { all: 7, downloading: 3, seeding: 2, completed: 2 };

describe("statusParts", () => {
    it("reads All first, then each group by what it is", () => {
        expect(statusParts(counts, "all")).toEqual([
            { id: "all", text: "All 7" },
            { id: "downloading", text: "3 active" },
            { id: "seeding", text: "2 seeding" },
            { id: "completed", text: "2 completed" },
        ]);
    });

    it("leaves out a group with nothing in it", () => {
        expect(
            statusParts(
                { all: 4, downloading: 0, seeding: 0, completed: 4 },
                "all",
            ).map((p) => p.id),
        ).toEqual([
            "all",
            "completed",
        ]);
    });

    it("keeps the group you are looking at, even when it is empty", () => {
        expect(
            statusParts(
                { all: 4, downloading: 0, seeding: 0, completed: 4 },
                "seeding",
            ).map((p) => p.id),
        ).toEqual([
            "all",
            "seeding",
            "completed",
        ]);
    });
});

describe("speedText", () => {
    it("shows both directions when either is moving", () => {
        expect(speedText(12.4 * 1024 * 1024, 0.5 * 1024 * 1024)).toBe(
            "↓ 12.4 MB/s · ↑ 512.0 KB/s",
        );
    });

    it("is empty when nothing moves", () => {
        expect(speedText(0, 0)).toBe("");
    });
});

describe("titleFor", () => {
    it("names the list for the screen reader by the filter", () => {
        expect(titleFor("all")).toBe("Recent downloads");
        expect(titleFor("downloading")).toBe("Active downloads");
        expect(titleFor("seeding")).toBe("Seeding");
        expect(titleFor("completed")).toBe("Completed");
    });
});
