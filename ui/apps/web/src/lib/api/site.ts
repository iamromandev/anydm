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
}

export type LinkLookup =
    { kind: "site"; preview: SitePreview } | { kind: "file" };

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
    };
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
        return {
            kind: "site",
            preview: toPreview(await post<any>("/extract", { url })),
        };
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
    const preset = choosePreset(found.preview.presets, preferred);
    if (preset === null) {
        throw new ApiError("Nothing on this page can be downloaded yet");
    }
    await post("/download/media", { url, preset });
}
