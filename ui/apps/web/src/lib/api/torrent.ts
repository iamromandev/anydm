/**
 * The two torrent calls, and the shape they return.
 *
 * Adding a torrent is two round trips on purpose: the first says what is
 * inside it, the second commits to downloading part of it. Nothing reaches the
 * task list until the second, so an abandoned magnet leaves nothing behind.
 */

import { postApi } from "./client";

/** One file inside a torrent, before anything has been downloaded. */
export type ResolvedFile = {
    index: number;
    path: string;
    sizeBytes: number;
};

export type ResolvedTorrent = {
    infoHash: string;
    title: string;
    totalBytes: number;
    files: ResolvedFile[];
};

export function normalizeResolvedTorrent(raw: any): ResolvedTorrent {
    return {
        infoHash: raw?.info_hash ?? "",
        title: raw?.title ?? "",
        totalBytes: raw?.total_size ?? 0,
        files: Array.isArray(raw?.files)
            ? raw.files.map((file: any) => ({
                  index: file.index ?? 0,
                  path: file.path ?? "",
                  sizeBytes: file.size ?? 0,
              }))
            : [],
    };
}

/** Inspect a magnet or .torrent. Talks to peers, so it can take seconds. */
export async function resolveTorrent(
    torrent: string,
): Promise<ResolvedTorrent> {
    return normalizeResolvedTorrent(
        await postApi<any>("/download/torrent/resolve", { torrent }),
    );
}

/**
 * Start downloading the chosen files. An empty list means every file.
 *
 * Resolves to the download's id. A torrent already held is not added again:
 * the API answers with the download that has it.
 */
export async function addTorrent(
    torrent: string,
    files: number[],
    categoryId?: string,
): Promise<string> {
    const download = await postApi<{ id: string }>("/download/torrent", {
        torrent,
        files,
        ...(categoryId ? { category_id: categoryId } : {}),
    });
    return download.id;
}

/**
 * What to tell someone whose torrent was already in the list.
 *
 * `known` is the list's ids from before the request: a new download's own
 * event can arrive before the answer, so checking the list afterwards would
 * call every new torrent a repeat.
 */
export function heldTorrentNotice(
    id: string,
    known: ReadonlySet<string>,
): string | null {
    return known.has(id) ? "Already in your downloads" : null;
}
