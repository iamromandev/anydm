import { describe, expect, it } from "bun:test";

import {
    EMPTY_LISTING,
    applyFrame,
    entriesUrl,
    entryLabel,
    listingSummary,
    playlistRequest,
    stopListing,
    toEntry,
    type ListingState,
} from "./playlist";

const RAW = {
    index: 1,
    id: "v1",
    url: "https://www.youtube.com/watch?v=v1",
    title: "Video 1",
    duration: 60,
    thumbnail: "https://img.test/v1.jpg",
    timestamp: 1789862400,
    available: true,
    have: null,
};

describe("entriesUrl", () => {
    it("asks for the list's page, capped at the limit", () => {
        const url = entriesUrl("https://www.youtube.com/playlist?list=PL1");
        expect(url).toContain(
            "/extract/entries?url=https%3A%2F%2Fwww.youtube.com%2Fplaylist%3Flist%3DPL1&limit=10000",
        );
    });
});

describe("toEntry", () => {
    it("keeps what the API sent", () => {
        expect(toEntry(RAW)).toEqual(RAW);
    });

    it("reads what's missing as unknown, and anything odd as nothing held", () => {
        expect(
            toEntry({
                index: 2,
                id: "t",
                url: "https://s.test/a/world-on-fire-1",
                have: "maybe",
            }),
        ).toEqual({
            index: 2,
            id: "t",
            url: "https://s.test/a/world-on-fire-1",
            title: null,
            duration: null,
            thumbnail: null,
            timestamp: null,
            available: true,
            have: null,
        });
    });
});

describe("entryLabel", () => {
    it("is the title, else the last part of the URL", () => {
        expect(entryLabel(toEntry(RAW))).toBe("Video 1");
        expect(
            entryLabel(
                toEntry({
                    ...RAW,
                    title: null,
                    url: "https://soundcloud.com/band/world-on-fire-1",
                }),
            ),
        ).toBe("world-on-fire-1");
        expect(
            entryLabel(toEntry({ ...RAW, title: null, url: "not a url" })),
        ).toBe("v1");
    });
});

describe("applyFrame", () => {
    it("adds each batch of videos in order", () => {
        let state = applyFrame(EMPTY_LISTING, "entries", [
            RAW,
        ]);
        state = applyFrame(state, "entries", [
            { ...RAW, index: 2, id: "v2" },
        ]);

        expect(state.entries.map((e) => e.id)).toEqual([
            "v1",
            "v2",
        ]);
        expect(state.status).toBe("listing");
    });

    it("ends on done", () => {
        expect(applyFrame(EMPTY_LISTING, "done", { count: 0 }).status).toBe(
            "done",
        );
    });

    it("ends on failed, keeping the videos and the API's message", () => {
        const listed = applyFrame(EMPTY_LISTING, "entries", [
            RAW,
        ]);
        const state = applyFrame(listed, "failed", {
            message: "Media unavailable: private",
        });

        expect(state).toEqual({
            entries: listed.entries,
            status: "failed",
            error: "Media unavailable: private",
        });
    });

    it("ignores anything after the end", () => {
        const done = applyFrame(EMPTY_LISTING, "done", { count: 0 });
        expect(
            applyFrame(done, "entries", [
                RAW,
            ]),
        ).toBe(done);
    });
});

describe("stopListing", () => {
    it("stops a listing short, keeping what came", () => {
        const listed = applyFrame(EMPTY_LISTING, "entries", [
            RAW,
        ]);
        expect(stopListing(listed)).toEqual({ ...listed, status: "stopped" });
    });

    it("leaves a finished one alone", () => {
        const done: ListingState = { ...EMPTY_LISTING, status: "done" };
        expect(stopListing(done)).toBe(done);
    });
});

describe("listingSummary", () => {
    const many = Array.from({ length: 1240 }, (_, i) =>
        toEntry({ ...RAW, index: i + 1, id: `v${i}` }),
    );

    it("counts as it goes, then says how many", () => {
        expect(
            listingSummary({ entries: many, status: "listing", error: "" }),
        ).toBe("Listing… 1,240 so far");
        expect(
            listingSummary({ entries: many, status: "done", error: "" }),
        ).toBe("1,240 videos");
        expect(
            listingSummary({
                entries: many.slice(0, 1),
                status: "done",
                error: "",
            }),
        ).toBe("1 video");
        expect(
            listingSummary({ entries: many, status: "stopped", error: "" }),
        ).toBe("Listing stopped at 1,240");
        expect(
            listingSummary({ entries: [], status: "failed", error: "Nope" }),
        ).toBe("Nope");
    });
});

describe("playlistRequest", () => {
    it("sends the target and the ticked videos, in order", () => {
        const target = {
            url: "https://www.youtube.com/playlist?list=PL1",
            title: "29C3",
            count: 3,
            extractor: "YoutubeTab",
            playlistId: "PL1",
            channelTab: false,
        };
        const entries = [
            1,
            2,
            3,
        ].map((index) =>
            toEntry({ ...RAW, index, id: `v${index}`, duration: 60.4 }),
        );

        expect(
            playlistRequest(
                target,
                "1080",
                entries,
                new Set([
                    3,
                    1,
                ]),
            ),
        ).toEqual({
            url: target.url,
            extractor: "YoutubeTab",
            playlist_id: "PL1",
            title: "29C3",
            channel_tab: false,
            preset: "1080",
            entries: [
                1,
                3,
            ].map((index) => ({
                index,
                id: `v${index}`,
                url: RAW.url,
                title: "Video 1",
                duration: 60,
            })),
        });
    });
});
