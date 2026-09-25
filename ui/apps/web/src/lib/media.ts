/**
 * What counts as a media file, by its name: the add box's Play on a link,
 * and Play on a finished download's card (#94). The same list the API's
 * `MEDIA_EXTENSIONS` holds.
 */
export const MEDIA_EXTENSIONS = [
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

export function hasMediaExtension(path: string): boolean {
    const lower = path.toLowerCase();
    return MEDIA_EXTENSIONS.some((extension) => lower.endsWith(extension));
}
