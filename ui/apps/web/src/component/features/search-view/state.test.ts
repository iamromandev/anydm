import { describe, expect, it } from "bun:test";

import { ApiError } from "@/lib/api/envelope";

import { emptySearch, failureOf } from "./state";

describe("the Search view's state", () => {
    it("starts empty, sorted by seeders", () => {
        const state = emptySearch();
        expect(state.q).toBe("");
        expect(state.category).toBe("all");
        expect(state.answer).toBeNull();
        expect(state.sort).toEqual({ key: "seeders", descending: true });
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
