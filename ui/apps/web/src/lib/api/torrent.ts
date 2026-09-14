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
        totalBytes: raw?.total_bytes ?? 0,
        files: Array.isArray(raw?.files)
            ? raw.files.map((file: any) => ({
                  index: file.index ?? 0,
                  path: file.path ?? "",
                  sizeBytes: file.size_bytes ?? 0,
              }))
            : [],
    };
}

/** Inspect a magnet or .torrent. Talks to peers, so it can take seconds. */
export async function resolveTorrent(torrent: string): Promise<ResolvedTorrent> {
    return normalizeResolvedTorrent(await postApi<any>("/download/torrent/resolve", { torrent }));
}

/** Start downloading the chosen files. An empty list means every file. */
export async function addTorrent(torrent: string, files: number[]): Promise<void> {
    await postApi<unknown>("/download/torrent", { torrent, files });
}
