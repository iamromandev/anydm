export type InputKind =
    "auto" | "magnet" | "youtube" | "url" | "torrent" | "media";

const YOUTUBE_HOSTS = new Set([
    "youtube.com",
    "www.youtube.com",
    "youtu.be",
    "m.youtube.com",
    "music.youtube.com",
]);

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

const PLAYABLE_KINDS = new Set<InputKind>([
    "magnet",
    "torrent",
    "youtube",
    "media",
]);

export function isPlayableKind(kind: InputKind): boolean {
    return PLAYABLE_KINDS.has(kind);
}

export function detectKind(value: string): Exclude<InputKind, "auto"> {
    const trimmed = value.trim();
    if (trimmed.startsWith("magnet:?")) return "magnet";

    try {
        const url = new URL(trimmed);
        if (YOUTUBE_HOSTS.has(url.hostname)) return "youtube";
        if (url.pathname.endsWith(".torrent")) return "torrent";
        const lowerPath = url.pathname.toLowerCase();
        if (MEDIA_EXTENSIONS.some((ext) => lowerPath.endsWith(ext))) {
            return "media";
        }
        return "url";
    } catch {
        return "url";
    }
}
