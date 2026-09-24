import { formatBytes, formatSpeed } from "@/component/core/utils";

/** Rates arrive as bytes per second, named with this suffix. */
const RATE_SUFFIX = "_bps";

/** Sizes arrive as plain bytes, named with this suffix. */
const SIZE_SUFFIX = "_bytes";

const UNIT = new RegExp(`(${RATE_SUFFIX}|${SIZE_SUFFIX})$`);

/** Keys whose words, split at the underscores, would misspell a name. */
const LABELS: Record<string, string> = {
    yt_dlp_version: "yt-dlp version",
};

/** Turn `download_workers` into something worth reading. */
export function serverLabel(key: string): string {
    const named = LABELS[key];
    if (named) return named;
    const words = key.replace(UNIT, "").replace(/_/g, " ");
    return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * A rate of `0` is the API's word for no cap, and a size of `0` for no floor;
 * a bare 0 would contradict both.
 */
export function serverValue(
    key: string,
    value: string | number | boolean,
): string {
    if (key.endsWith(RATE_SUFFIX) && typeof value === "number") {
        return value === 0 ? "Unlimited" : formatSpeed(value);
    }
    if (key.endsWith(SIZE_SUFFIX) && typeof value === "number") {
        return value === 0 ? "Off" : formatBytes(value);
    }
    return String(value);
}
