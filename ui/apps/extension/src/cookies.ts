import type { BrowserCookie } from "./request";

/** The part of the browser's cookies API this uses. */
export interface CookieApi {
    getAllCookieStores(): Promise<{ id: string; tabIds: number[] }[]>;
    getAll(details: {
        url: string;
        storeId?: string;
    }): Promise<{ name: string; value: string }[]>;
}

/**
 * The cookies the browser would send to [url] from the tab [tabId]: the tab's own store, so a private window or a
 * container gets its own cookies, and the browser decides which cookies apply and in what order.
 */
export async function cookiesFor(
    api: CookieApi,
    url: string,
    tabId?: number,
): Promise<BrowserCookie[]> {
    let storeId: string | undefined;
    if (tabId !== undefined) {
        const stores = await api.getAllCookieStores();
        storeId = stores.find((s) => s.tabIds.includes(tabId))?.id;
    }
    const cookies = await api.getAll({ url, storeId });
    return cookies.map(({ name, value }) => ({ name, value }));
}
