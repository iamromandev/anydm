/**
 * Links to pages on media sites: what the API says a page offers, and where a
 * link goes from there.
 *
 * Every page link is asked about first. A link no site supports is a file,
 * and becomes a direct download, so nothing typed into the add box needs the
 * person to know which kind of link it is.
 */

import { PRESETS, type Preset } from "@/lib/prefs";
import { postApi } from "./client";
import { ApiError } from "./envelope";

export interface SitePreview {
    /** yt-dlp's name for the site, as the API reports it. */
    extractor: string;
    /** The same, as a person would write it. */
    site: string;
    id: string;
    title: string;
    uploader: string;
    /** Seconds; 0 when the site did not say. */
    duration: number;
    thumbnail: string;
    /** What can be downloaded from this page today, in the order to offer it. */
    presets: Preset[];
    /** The playlist a YouTube watch link also names, for "see all"; null otherwise. */
    playlistUrl: string | null;
}

export interface PlaylistTab {
    name: string;
    url: string;
}

/** A list of videos: a playlist, or a channel's own uploads. */
export interface PlaylistPreview {
    extractor: string;
    site: string;
    id: string;
    title: string;
    uploader: string;
    thumbnail: string;
    /** The page to list. */
    url: string;
    /** How many videos, when the site says; null until listed otherwise. */
    count: number | null;
    /** A channel's own uploads rather than a playlist someone made. */
    channelTab: boolean;
}

export type LinkLookup =
    | { kind: "site"; preview: SitePreview }
    | { kind: "playlist"; preview: PlaylistPreview }
    | { kind: "channel"; preview: PlaylistPreview; tabs: PlaylistTab[] }
    | { kind: "file" };

/**
 * What the add box and the add modal hand the page to queue.
 *
 * - ``site``: a page the add box previewed; ``preset`` is one it offers.
 * - ``url``: a direct download, by choice or because no site supports it.
 * - ``link``: a link nobody has looked at yet, routed by ``addLink``.
 * - ``magnet`` / ``file``: torrents, as a magnet or a base64 ``.torrent``.
 */
export type AddType = "site" | "url" | "link" | "magnet" | "file";

type Post = <T>(path: string, body: unknown) => Promise<T>;

/** Sites whose yt-dlp name is not how they write their own. */
const SITE_NAMES: Record<string, string> = {
    Youtube: "YouTube",
    Soundcloud: "SoundCloud",
    Twitter: "X",
    TwitchVod: "Twitch",
    // yt-dlp names YouTube's playlists and channels by their own extractor.
    YoutubeTab: "YouTube",
};

export function siteName(extractor: string | null | undefined): string {
    if (!extractor) return "";
    return SITE_NAMES[extractor] ?? extractor;
}

/** The preferred preset if the page offers it, else the first it does. */
export function choosePreset(
    presets: readonly Preset[],
    preferred: string,
): Preset | null {
    const wanted = presets.find((preset) => preset === preferred);
    return wanted ?? presets[0] ?? null;
}

function isPreset(value: unknown): value is Preset {
    return (PRESETS as readonly unknown[]).includes(value);
}

export function toPreview(raw: any): SitePreview {
    const extractor = String(raw?.extractor ?? "");
    return {
        extractor,
        site: siteName(extractor),
        id: String(raw?.id ?? ""),
        title: String(raw?.title ?? ""),
        uploader: String(raw?.uploader ?? ""),
        duration: Number(raw?.duration ?? 0) || 0,
        thumbnail: String(raw?.thumbnail ?? ""),
        presets: (Array.isArray(raw?.presets) ? raw.presets : []).filter(
            isPreset,
        ),
        playlistUrl:
            typeof raw?.playlist_url === "string" && raw.playlist_url
                ? raw.playlist_url
                : null,
    };
}

export function toPlaylistPreview(
    raw: any,
    requested: string,
): PlaylistPreview {
    const extractor = String(raw?.extractor ?? "");
    const count = raw?.count;
    return {
        extractor,
        site: siteName(extractor),
        id: String(raw?.id ?? ""),
        title: String(raw?.title ?? ""),
        uploader: String(raw?.uploader ?? ""),
        thumbnail: String(raw?.thumbnail ?? ""),
        url: String(raw?.webpage_url || requested),
        count:
            typeof count === "number" && Number.isFinite(count) ? count : null,
        channelTab: raw?.channel_tab === true,
    };
}

function toTabs(raw: unknown): PlaylistTab[] {
    if (!Array.isArray(raw)) return [];
    return raw
        .map((tab) => ({
            name: String(tab?.name ?? ""),
            url: String(tab?.url ?? ""),
        }))
        .filter((tab) => tab.name && tab.url);
}

/** "Playlist · 96 videos · Christiaan008", or "Channel · 3Blue1Brown" for a tab. */
export function playlistMeta(preview: PlaylistPreview): string {
    const count =
        preview.count === null
            ? ""
            : `${preview.count.toLocaleString("en-US")} ${preview.count === 1 ? "video" : "videos"}`;
    return [
        preview.channelTab ? "Channel" : "Playlist",
        count,
        preview.uploader,
    ]
        .filter(Boolean)
        .join(" · ");
}

/**
 * What the API says ``url`` is: a page it can preview, or a file.
 *
 * Only ``unsupported_url`` means "a file": every other refusal (a live stream,
 * a playlist, a site that only streams) is meant for the person, so it is
 * thrown for the caller to show.
 */
export async function lookupLink(
    url: string,
    post: Post = postApi,
): Promise<LinkLookup> {
    try {
        const raw = await post<any>("/extract", { url });
        if (raw?.type === "playlist") {
            return { kind: "playlist", preview: toPlaylistPreview(raw, url) };
        }
        if (raw?.type === "channel") {
            return {
                kind: "channel",
                preview: toPlaylistPreview(raw, url),
                tabs: toTabs(raw?.tabs),
            };
        }
        return { kind: "site", preview: toPreview(raw) };
    } catch (err) {
        if (err instanceof ApiError && err.type === "unsupported_url") {
            return { kind: "file" };
        }
        throw err;
    }
}

/**
 * Queue ``url`` from its site at the preset closest to ``preferred``, or as a
 * direct download when no site supports it. For callers that show no preview.
 */
export async function addLink(
    url: string,
    preferred: string,
    post: Post = postApi,
): Promise<void> {
    const found = await lookupLink(url, post);
    if (found.kind === "file") {
        await post("/download/url", { url });
        return;
    }
    if (found.kind === "playlist" || found.kind === "channel") {
        throw new ApiError(
            "This link is a playlist: paste it in the add box to choose its videos",
        );
    }
    const preset = choosePreset(found.preview.presets, preferred);
    if (preset === null) {
        throw new ApiError("Nothing on this page can be downloaded yet");
    }
    await post("/download/media", { url, preset });
}
