/**
 * Searching the API's Torznab indexers (the magnet hub), and fetching a result to add.
 *
 * The API leaves null fields out of its answers, so every optional field is
 * read as `null` here: the view never has to tell "missing" from "none".
 */

import { getApi, postApi } from "./client";
import { ApiError } from "./envelope";

export type SearchCategory =
    "all" | "movies" | "tv" | "music" | "software" | "books" | "other";

export type FoundTorrent = {
    title: string;
    sizeBytes: number | null;
    seeders: number | null;
    leechers: number | null;
    /** ISO 8601, as the indexer dated it. */
    published: string | null;
    category: string;
    infoHash: string | null;
    magnet: string | null;
    /** A .torrent on the indexer, fetched through `POST /search/torrent`. */
    link: string | null;
    indexers: string[];
};

export type IndexerError = { indexer: string; message: string };

export type SearchAnswer = {
    results: FoundTorrent[];
    errors: IndexerError[];
    tookMs: number;
};

export type SearchSources = { enabled: boolean; indexers: string[] };

/** What the add-torrent dialog starts from: a magnet, or a .torrent in base64. */
export type AddInitial = { type: "magnet" | "file"; value: string };

const numberOrNull = (value: unknown): number | null =>
    typeof value === "number" ? value : null;
const textOrNull = (value: unknown): string | null =>
    typeof value === "string" && value !== "" ? value : null;

export function normalizeFound(raw: any): FoundTorrent {
    return {
        title: raw?.title ?? "",
        sizeBytes: numberOrNull(raw?.size),
        seeders: numberOrNull(raw?.seeders),
        leechers: numberOrNull(raw?.leechers),
        published: textOrNull(raw?.published),
        category: raw?.category ?? "other",
        infoHash: textOrNull(raw?.info_hash),
        magnet: textOrNull(raw?.magnet),
        link: textOrNull(raw?.link),
        indexers: Array.isArray(raw?.indexers)
            ? raw.indexers.filter((n: unknown) => typeof n === "string")
            : [],
    };
}

export function normalizeSearch(raw: any): SearchAnswer {
    return {
        results: Array.isArray(raw?.results)
            ? raw.results.map(normalizeFound)
            : [],
        errors: Array.isArray(raw?.errors)
            ? raw.errors.map((e: any) => ({
                  indexer: e?.indexer ?? "",
                  message: e?.message ?? "",
              }))
            : [],
        tookMs: numberOrNull(raw?.took_ms) ?? 0,
    };
}

export function normalizeFetched(raw: any): AddInitial {
    const magnet = textOrNull(raw?.magnet);
    if (magnet) return { type: "magnet", value: magnet };
    const torrent = textOrNull(raw?.torrent);
    if (torrent) return { type: "file", value: torrent };
    throw new ApiError("The indexer sent nothing to add");
}

/** Whether the API has indexers; the sidebar shows Search only then. */
export async function searchSources(): Promise<SearchSources> {
    const raw = await getApi<any>("/search/sources");
    return {
        enabled: raw?.enabled === true,
        indexers: Array.isArray(raw?.indexers) ? raw.indexers : [],
    };
}

export async function searchIndexers(
    q: string,
    category: SearchCategory,
): Promise<SearchAnswer> {
    const query = new URLSearchParams({ q, category });
    return normalizeSearch(await getApi<any>(`/search?${query.toString()}`));
}

/** A result that has only a .torrent link: the API fetches it from the indexer. */
export async function fetchFoundTorrent(link: string): Promise<AddInitial> {
    return normalizeFetched(await postApi<any>("/search/torrent", { link }));
}
