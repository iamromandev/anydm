/**
 * How the list is ordered, and remembering the choice.
 *
 * The values are the API's own `sort` parameter, so nothing translates between
 * what is stored, what is shown and what is asked for.
 */

export type SortValue =
    | "-created_at"
    | "created_at"
    | "title"
    | "-total_bytes"
    | "-progress"
    | "-speed_bps";

export const DEFAULT_SORT: SortValue = "-created_at";

/** The key the choice is remembered under, per browser. */
const STORAGE_KEY = "anydm.sort";

export const SORT_OPTIONS: { value: SortValue; label: string }[] = [
    { value: "-created_at", label: "Newest first" },
    { value: "created_at", label: "Oldest first" },
    { value: "title", label: "Name" },
    { value: "-total_bytes", label: "Largest first" },
    { value: "-progress", label: "Most complete" },
    { value: "-speed_bps", label: "Fastest first" },
];

export function sortLabel(value: string): string {
    return (
        SORT_OPTIONS.find((option) => option.value === value)?.label ??
        SORT_OPTIONS[0].label
    );
}

function isSortValue(value: unknown): value is SortValue {
    return SORT_OPTIONS.some((option) => option.value === value);
}

/**
 * The remembered choice, or the default.
 *
 * A stored value this build no longer offers is discarded rather than sent:
 * the API answers an unknown sort with a 422, and a stale key in one browser
 * should not break that browser's list.
 */
export function loadSort(
    storage: Storage | undefined = globalThis.localStorage,
): SortValue {
    try {
        const stored = storage?.getItem(STORAGE_KEY);
        return isSortValue(stored) ? stored : DEFAULT_SORT;
    } catch {
        // Private windows and blocked site data both throw on access.
        return DEFAULT_SORT;
    }
}

export function saveSort(
    value: SortValue,
    storage: Storage | undefined = globalThis.localStorage,
): void {
    try {
        storage?.setItem(STORAGE_KEY, value);
    } catch {
        // Remembering is a convenience; failing to is not worth an error.
    }
}
