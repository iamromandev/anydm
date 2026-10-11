import { describe, expect, it } from "bun:test";
import { parseStats } from "./stats";

const answer = () => ({
    group: "provider",
    start: "2026-10-01",
    end: "2026-10-11",
    groups: [
        {
            key: "youtube",
            name: "Youtube",
            downloaded_size: 100,
            uploaded_size: 5,
            finished: 1,
            failed: 0,
            removed: 2,
        },
    ],
    speeds: [
        {
            hour: "2026-10-11T09:00:00Z",
            average_bps: 350,
            maximum_bps: 400,
        },
    ],
});

describe("parseStats", () => {
    it("reads the API's answer", () => {
        expect(parseStats(answer())).toEqual({
            group: "provider",
            start: "2026-10-01",
            end: "2026-10-11",
            groups: [
                {
                    key: "youtube",
                    name: "Youtube",
                    downloadedSize: 100,
                    uploadedSize: 5,
                    finished: 1,
                    failed: 0,
                    removed: 2,
                },
            ],
            speeds: [
                {
                    hour: "2026-10-11T09:00:00Z",
                    averageBps: 350,
                    maximumBps: 400,
                },
            ],
        });
    });

    it("leaves a missing name null, as the API leaves null fields out", () => {
        const raw = answer();
        delete (raw.groups[0] as any).name;
        expect(parseStats(raw)?.groups[0].name).toBeNull();
    });

    it("refuses an answer with a wrong or missing part rather than showing a wrong number", () => {
        expect(parseStats(null)).toBeNull();
        expect(parseStats({ ...answer(), group: "host" })).toBeNull();
        expect(parseStats({ ...answer(), groups: "none" })).toBeNull();
        const text = answer();
        (text.groups[0] as any).downloaded_size = "lots";
        expect(parseStats(text)).toBeNull();
        const negative = answer();
        negative.speeds[0].average_bps = -1;
        expect(parseStats(negative)).toBeNull();
        const hour = answer();
        hour.speeds[0].hour = "later";
        expect(parseStats(hour)).toBeNull();
    });
});
