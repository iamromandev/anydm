/** The pages of the player's settings menu, in the order its rows show. */
export type SettingsPage =
    "speed" | "quality" | "audio" | "subtitles" | "files";

export const SETTINGS_PAGE_TITLES: Record<SettingsPage, string> = {
    speed: "Speed",
    quality: "Quality",
    audio: "Audio",
    subtitles: "Subtitles",
    files: "Files",
};

/**
 * Which rows the menu has. Speed always; the others only when the media gives
 * a choice, the same conditions the dropdowns they replace were shown under.
 */
export function availablePages(counts: {
    files: number;
    audio: number;
    subtitles: number;
    quality: number;
}): SettingsPage[] {
    const pages: SettingsPage[] = [
        "speed",
    ];
    if (counts.quality > 0) pages.push("quality");
    if (counts.audio > 1) pages.push("audio");
    if (counts.subtitles > 0) pages.push("subtitles");
    if (counts.files > 1) pages.push("files");
    return pages;
}

export function formatRate(rate: number): string {
    return `${rate}x`;
}
