import { formatSpeed } from "@/component/core/utils";

/** Rates arrive as bytes per second, named with this suffix. */
const RATE_SUFFIX = "_bps";

/** Turn `download_workers` into something worth reading. */
export function serverLabel(key: string): string {
    const words = key
        .replace(new RegExp(`${RATE_SUFFIX}$`), "")
        .replace(/_/g, " ");
    return words.charAt(0).toUpperCase() + words.slice(1);
}

/** A rate of `0` is the API's word for no cap, which a bare 0 would contradict. */
export function serverValue(
    key: string,
    value: string | number | boolean,
): string {
    if (key.endsWith(RATE_SUFFIX) && typeof value === "number") {
        return value === 0 ? "Unlimited" : formatSpeed(value);
    }
    return String(value);
}
