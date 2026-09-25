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

const COLLATOR = new Intl.Collator(undefined, {
    numeric: true,
    sensitivity: "base",
});

/** Path order a person expects: `E2` before `E10`, case ignored. */
export function naturalCompare(a: string, b: string): number {
    return COLLATOR.compare(a, b);
}

/** One of a torrent's files, as the player's file menu lists it (#98). */
export type PlayableFile = {
    index: number;
    path: string;
    sizeBytes: number;
};

/**
 * The files the player can switch between: media files that are being
 * downloaded (a resolved torrent's files carry no `selected`, and all count),
 * in natural order.
 */
export function mediaFiles(
    files: (PlayableFile & { selected?: boolean })[],
): PlayableFile[] {
    return files
        .filter(
            (file) => file.selected !== false && hasMediaExtension(file.path),
        )
        .map(({ index, path, sizeBytes }) => ({ index, path, sizeBytes }))
        .sort((a, b) => naturalCompare(a.path, b.path));
}

/** The file played when none is named: the largest, as the API picks it. */
export function defaultFileIndex(files: PlayableFile[]): number | null {
    if (files.length === 0) return null;
    return files.reduce((largest, file) =>
        file.sizeBytes > largest.sizeBytes ? file : largest,
    ).index;
}
