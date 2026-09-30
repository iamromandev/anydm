import { ApiError } from "@/lib/api/envelope";
import type {
    IndexerError,
    SearchAnswer,
    SearchCategory,
    VideoAnswer,
} from "@/lib/api/search";

import { defaultSort, type SearchMode, type SortState } from "./present";

export type VideoState = {
    q: string;
    busy: boolean;
    /** The query the answer is for; the box may have changed since. */
    searched: string;
    answer: VideoAnswer | null;
    failure: string | null;
    /** The video URL being added, or "". */
    adding: string;
};

export type SearchState = {
    /** Which tab is showing. */
    source: "torrents" | "youtube";
    video: VideoState;
    q: string;
    category: SearchCategory;
    busy: boolean;
    /** What the answer is: the latest releases, or a search. */
    mode: SearchMode;
    /** The query the answer is for ("" when browsing); the box may have changed since. */
    searched: string;
    answer: SearchAnswer | null;
    failure: { message: string; causes: IndexerError[] } | null;
    sort: SortState;
    /** The link being fetched for Add, or "". */
    fetching: string;
};

export function emptySearch(): SearchState {
    return {
        source: "torrents",
        video: {
            q: "",
            busy: false,
            searched: "",
            answer: null,
            failure: null,
            adding: "",
        },
        q: "",
        category: "all",
        busy: false,
        mode: "browse",
        searched: "",
        answer: null,
        failure: null,
        sort: defaultSort("browse"),
        fetching: "",
    };
}

export function failureOf(error: unknown): {
    message: string;
    causes: IndexerError[];
} {
    if (error instanceof ApiError) {
        return {
            message: error.message,
            causes: (error.details ?? []).map((d) => ({
                indexer: d.subject ?? "",
                message: d.description ?? "",
            })),
        };
    }
    return { message: "Search failed", causes: [] };
}

/** A YouTube search's failure, in the API's own words when it gave some. */
export function videoFailureOf(error: unknown): string {
    return error instanceof ApiError
        ? error.message
        : "Couldn't search YouTube";
}
