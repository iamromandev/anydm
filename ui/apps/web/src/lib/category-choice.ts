import { type CategoryItem, DOWNLOADS_CATEGORY_ID } from "./api/category";

const STORAGE_KEY = "anydm.category";

/** The category the add forms start on: the last one picked, while it still exists. */
export function loadCategoryChoice(
    categories: CategoryItem[],
    storage: Storage | undefined = globalThis.localStorage,
): string {
    try {
        const stored = storage?.getItem(STORAGE_KEY);
        if (stored && categories.some((c) => c.id === stored)) return stored;
    } catch {
        // Private windows and blocked site data both throw on access.
    }
    return DOWNLOADS_CATEGORY_ID;
}

export function saveCategoryChoice(
    id: string,
    storage: Storage | undefined = globalThis.localStorage,
): void {
    try {
        storage?.setItem(STORAGE_KEY, id);
    } catch {
        // Remembering is a convenience; failing to is not worth an error.
    }
}
