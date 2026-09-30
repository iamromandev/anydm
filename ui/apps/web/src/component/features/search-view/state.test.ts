import { describe, expect, it } from "bun:test";

import { ApiError } from "@/lib/api/envelope";

import { emptySearch, failureOf, videoFailureOf } from "./state";

describe("the Search view's state", () => {
    it("starts empty, browsing, newest first", () => {
        const state = emptySearch();
        expect(state.q).toBe("");
        expect(state.category).toBe("all");
        expect(state.mode).toBe("browse");
        expect(state.answer).toBeNull();
        expect(state.sort).toEqual({ key: "published", descending: true });
    });

    it("lists each indexer's reason when every one failed", () => {
        const error = new ApiError(
            "No indexer answered the search",
            502,
            "search_failed",
            [
                { subject: "prowlarr-1", description: "timed out after 15 s" },
            ],
        );
        expect(failureOf(error)).toEqual({
            message: "No indexer answered the search",
            causes: [
                { indexer: "prowlarr-1", message: "timed out after 15 s" },
            ],
        });
    });

    it("keeps any other failure's own words", () => {
        expect(failureOf(new ApiError("Could not reach the API"))).toEqual({
            message: "Could not reach the API",
            causes: [],
        });
        expect(failureOf("boom")).toEqual({
            message: "Search failed",
            causes: [],
        });
    });
});

describe("the YouTube part of the state", () => {
    it("starts on torrents, with nothing asked of YouTube", () => {
        const state = emptySearch();
        expect(state.source).toBe("torrents");
        expect(state.video).toEqual({
            q: "",
            busy: false,
            searched: "",
            answer: null,
            failure: null,
            adding: "",
        });
    });

    it("gives an API error's own message, and a plain one for anything else", () => {
        expect(
            videoFailureOf(
                new ApiError("Extraction failed: HTTP Error 429", 502),
            ),
        ).toBe("Extraction failed: HTTP Error 429");
        expect(videoFailureOf(new Error("boom"))).toBe(
            "Couldn't search YouTube",
        );
    });
});
