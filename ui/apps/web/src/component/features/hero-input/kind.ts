export type InputKind =
    "auto" | "magnet" | "site" | "url" | "torrent" | "media";

const MEDIA_EXTENSIONS = [
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".wmv",
    ".flv",
    ".m4v",
    ".mp3",
    ".flac",
    ".wav",
    ".m4a",
    ".aac",
    ".ogg",
];

/**
 * Extensions a page is served under. A link ending in anything else names a
 * file, and goes straight to a direct download rather than costing a lookup.
 */
const PAGE_EXTENSIONS = new Set([
    "html",
    "htm",
    "php",
    "asp",
    "aspx",
    "jsp",
]);

// A site page is resolved by the API first, and a link no site claims falls
// back to playing directly, so "site" is playable too.
const PLAYABLE_KINDS = new Set<InputKind>([
    "magnet",
    "torrent",
    "media",
    "site",
]);

export function isPlayableKind(kind: InputKind): boolean {
    return PLAYABLE_KINDS.has(kind);
}

function fileExtension(pathname: string): string | null {
    const last = pathname.split("/").pop() ?? "";
    const dot = last.lastIndexOf(".");
    return dot > 0 ? last.slice(dot + 1).toLowerCase() : null;
}

/**
 * What a typed link most likely is.
 *
 * A page on any site is a ``site``: the API is asked what it offers, and a page
 * no site supports falls back to a direct download there. Only the cases that
 * need no asking are settled here.
 */
export function detectKind(value: string): Exclude<InputKind, "auto"> {
    const trimmed = value.trim();
    if (trimmed.startsWith("magnet:?")) return "magnet";

    try {
        const url = new URL(trimmed);
        const lowerPath = url.pathname.toLowerCase();
        if (lowerPath.endsWith(".torrent")) return "torrent";
        if (MEDIA_EXTENSIONS.some((ext) => lowerPath.endsWith(ext))) {
            return "media";
        }
        const extension = fileExtension(lowerPath);
        if (extension && !PAGE_EXTENSIONS.has(extension)) return "url";
        return "site";
    } catch {
        return "url";
    }
}
