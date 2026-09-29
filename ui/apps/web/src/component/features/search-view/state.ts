import { ApiError } from "@/lib/api/envelope";
import type {
    IndexerError,
    SearchAnswer,
    SearchCategory,
} from "@/lib/api/search";

import { defaultSort, type SearchMode, type SortState } from "./present";

export type SearchState = {
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
