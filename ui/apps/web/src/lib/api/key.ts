/**
 * The API key, kept in this browser.
 *
 * Only needed when the API was started with `API_KEY`. Stored on its own
 * rather than among the preferences: it is a credential, and nothing that
 * merges or rewrites preferences should ever be in a position to drop it.
 */
export const API_KEY_STORAGE = "anydm.apiKey";

export function loadApiKey(
    storage: Storage | undefined = globalThis.localStorage,
): string {
    try {
        return storage?.getItem(API_KEY_STORAGE)?.trim() ?? "";
    } catch {
        return "";
    }
}

/** Blank removes it, so "no key" is never stored as a key that is empty. */
export function saveApiKey(
    key: string,
    storage: Storage | undefined = globalThis.localStorage,
): void {
    const value = key.trim();
    try {
        if (value) storage?.setItem(API_KEY_STORAGE, value);
        else storage?.removeItem(API_KEY_STORAGE);
    } catch {
        // Storage that refuses writes. The next request will 401 and say so.
    }
}
