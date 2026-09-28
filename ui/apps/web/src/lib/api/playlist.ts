/**
 * A playlist's videos as the API lists them (v0.5): where the stream is, the
 * frames it sends, and the list they add up to. The browser holds the list;
 * the API keeps nothing.
 */

import { apiUrl } from "./client";

/** The most one listing holds; the API stops there too. */
export const LISTING_LIMIT = 10_000;

/** What some task already does with a video. */
export type Have = "complete" | "queued" | "failed";

export interface PlaylistEntry {
    /** Its place in the list, from 1. */
    index: number;
    id: string;
    url: string;
    title: string | null;
    /** Seconds. */
    duration: number | null;
    thumbnail: string | null;
    /** Seconds since the epoch; approximate on YouTube. */
    timestamp: number | null;
    /** False for a video the site lists but won't serve. */
    available: boolean;
    have: Have | null;
}

export type ListingStatus = "listing" | "done" | "stopped" | "failed";

export interface ListingState {
    entries: PlaylistEntry[];
    status: ListingStatus;
    /** The API's message, once it failed. */
    error: string;
}

export const EMPTY_LISTING: ListingState = {
    entries: [],
    status: "listing",
    error: "",
};

/** The list the picker opens on: a playlist, or one of a channel's tabs. */
export interface PickerTarget {
    url: string;
    title: string;
    count: number | null;
    /** yt-dlp's name for the list's site, which the group keeps. */
    extractor: string;
    /** The playlist's id, or the channel's for one of its tabs. */
    playlistId: string;
    /** A channel's own uploads: their files aren't numbered. */
    channelTab: boolean;
}

/** `POST /download/playlist`'s body. */
export interface PlaylistRequest {
    url: string;
    extractor: string;
    playlist_id: string;
    title: string;
    channel_tab: boolean;
    preset: string;
    entries: {
        index: number;
        id: string;
        url: string;
        title: string | null;
        duration: number | null;
    }[];
}

/** The ticked videos, in listing order, as one group to add. */
export function playlistRequest(
    target: PickerTarget,
    preset: string,
    entries: readonly PlaylistEntry[],
    selected: ReadonlySet<number>,
): PlaylistRequest {
    return {
        url: target.url,
        extractor: target.extractor,
        playlist_id: target.playlistId,
        title: target.title,
        channel_tab: target.channelTab,
        preset,
        entries: entries
            .filter((entry) => selected.has(entry.index))
            .map((entry) => ({
                index: entry.index,
                id: entry.id,
                url: entry.url,
                title: entry.title,
                // The API takes whole seconds.
                duration:
                    entry.duration === null ? null : Math.round(entry.duration),
            })),
    };
}

export function entriesUrl(url: string, limit: number = LISTING_LIMIT): string {
    const query = `url=${encodeURIComponent(url)}&limit=${limit}`;
    // An EventSource can't send the key as a header.
    return apiUrl(`/extract/entries?${query}`, { withKey: true });
}

const HAVES: readonly string[] = [
    "complete",
    "queued",
    "failed",
];

function numberOrNull(value: unknown): number | null {
    return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function stringOrNull(value: unknown): string | null {
    return typeof value === "string" && value ? value : null;
}

export function toEntry(raw: any): PlaylistEntry {
    return {
        index: Number(raw?.index ?? 0) || 0,
        id: String(raw?.id ?? ""),
        url: String(raw?.url ?? ""),
        title: stringOrNull(raw?.title),
        duration: numberOrNull(raw?.duration),
        thumbnail: stringOrNull(raw?.thumbnail),
        timestamp: numberOrNull(raw?.timestamp),
        available: raw?.available !== false,
        have: HAVES.includes(raw?.have) ? (raw.have as Have) : null,
    };
}

/** What a row calls a video: its title, else the last part of its URL. */
export function entryLabel(entry: PlaylistEntry): string {
    if (entry.title) return entry.title;
    try {
        const parts = new URL(entry.url).pathname.split("/").filter(Boolean);
        const last = parts.at(-1);
        return last ? decodeURIComponent(last) : entry.id;
    } catch {
        return entry.id;
    }
}

/** The list after one frame of the stream. Frames after the end change nothing. */
export function applyFrame(
    state: ListingState,
    event: string,
    data: unknown,
): ListingState {
    if (state.status !== "listing") return state;
    if (event === "entries") {
        const batch = Array.isArray(data) ? data.map(toEntry) : [];
        return {
            ...state,
            entries: [
                ...state.entries,
                ...batch,
            ],
        };
    }
    if (event === "done") return { ...state, status: "done" };
    if (event === "failed") {
        const message = (data as { message?: unknown } | null)?.message;
        return {
            ...state,
            status: "failed",
            error:
                typeof message === "string" && message
                    ? message
                    : "The listing failed",
        };
    }
    return state;
}

/** The list once it stops short: the person's Stop, or a dropped connection. */
export function stopListing(state: ListingState): ListingState {
    return state.status === "listing" ? { ...state, status: "stopped" } : state;
}

/** The picker's header line. */
export function listingSummary(state: ListingState): string {
    const count = state.entries.length;
    const n = count.toLocaleString("en-US");
    switch (state.status) {
        case "listing":
            return `Listing… ${n} so far`;
        case "done":
            return `${n} ${count === 1 ? "video" : "videos"}`;
        case "stopped":
            return `Listing stopped at ${n}`;
        case "failed":
            return state.error;
    }
}
