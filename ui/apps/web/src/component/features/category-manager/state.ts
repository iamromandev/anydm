import type { CategoryItem } from "@/lib/api/category";

/** The name and folder a row is being edited to, before Save. */
export type Draft = { name: string; folder: string };

export function draftOf(category: CategoryItem): Draft {
    return { name: category.name, folder: category.folder };
}

export function isDirty(category: CategoryItem, draft: Draft): boolean {
    return draft.name !== category.name || draft.folder !== category.folder;
}

export function canSave(draft: Draft): boolean {
    return draft.name.trim() !== "";
}

/** `ids` with the one at `index` moved one step; null when it is already at that end. */
export function swapped(
    ids: string[],
    index: number,
    delta: -1 | 1,
): string[] | null {
    const other = index + delta;
    if (other < 0 || other >= ids.length) return null;
    const next = [
        ...ids,
    ];
    [
        next[index],
        next[other],
    ] = [
        next[other],
        next[index],
    ];
    return next;
}
