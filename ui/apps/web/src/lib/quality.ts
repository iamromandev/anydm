/**
 * The player's quality menu (#103).
 *
 * A site page offers each height it has and plays 1080p by default
 * ("Auto"); a file plays as it is ("Original"), or scaled down to a height
 * below its own. The API says which, for a session; a file the browser
 * plays itself only has its height, so its menu is worked out here, the
 * same way.
 */

export type QualityDefault = "auto" | "original";

export type QualityMenu = {
    /** The heights besides the default, tallest first. Empty: no menu. */
    heights: number[];
    /** The one picked; `null` for the default. */
    chosen: number | null;
    /** What's actually playing. */
    playing: number | null;
    default: QualityDefault;
    /** The default's own height: the site's pick, or the file's. */
    defaultHeight: number | null;
};

export const NO_QUALITY_MENU: QualityMenu = {
    heights: [],
    chosen: null,
    playing: null,
    default: "original",
    defaultHeight: null,
};

/** What a file can be scaled down to, as the API's `FILE_HEIGHTS`. */
export const FILE_HEIGHTS = [
    1080,
    720,
    480,
];

export function normalizeQualityMenu(raw: any): QualityMenu {
    if (!Array.isArray(raw?.qualities) || raw.qualities.length === 0) {
        return NO_QUALITY_MENU;
    }
    return {
        heights: raw.qualities.map(Number),
        // The envelope leaves out what is null.
        chosen: raw?.quality ?? null,
        playing: raw?.playing_height ?? null,
        default: raw?.quality_default === "auto" ? "auto" : "original",
        defaultHeight: raw?.default_height ?? null,
    };
}

/** A file's menu, from its height: Original, and each of `FILE_HEIGHTS` below it. */
export function fileQualityMenu(height: number | null): QualityMenu {
    if (!height) return NO_QUALITY_MENU;
    const heights = FILE_HEIGHTS.filter((candidate) => candidate < height);
    if (heights.length === 0) return NO_QUALITY_MENU;
    return {
        heights,
        chosen: null,
        playing: height,
        default: "original",
        defaultHeight: height,
    };
}

/** How the menu names its default: "Auto (1080p)", "Original (2160p)". */
export function defaultQualityLabel(menu: QualityMenu): string {
    const name = menu.default === "auto" ? "Auto" : "Original";
    return menu.defaultHeight ? `${name} (${menu.defaultHeight}p)` : name;
}
