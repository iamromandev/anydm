import type { BrowserCookie } from "./request";

/** The part of the browser's cookies API this uses. */
export interface CookieApi {
    getAllCookieStores(): Promise<
        { id: string; tabIds: number[]; incognito?: boolean }[]
    >;
    getAll(details: {
        url: string;
        storeId?: string;
    }): Promise<{ name: string; value: string; sameSite?: string }[]>;
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
    return cookies.map(({ name, value, sameSite }) =>
        sameSite === undefined ? { name, value } : { name, value, sameSite },
    );
}

const hostOf = (url: string | undefined): string | null => {
    if (url === undefined) return null;
    try {
        return new URL(url).hostname.toLowerCase().replace(/\.$/, "") || null;
    } catch {
        return null;
    }
};

/**
 * The cookies for a download that nobody started from a page of its own site. `cookies.getAll` returns every cookie that
 * matches the address, but the browser leaves out a `SameSite=Strict` one when the request comes from another site, so those
 * are dropped unless the page the download came from ([referrer]) is on the same host as the download. No referrer is
 * another site. (`Lax`, `None` and an unset value are sent on a navigation, which a download link is.)
 */
export function withoutStrictCrossSite(
    cookies: BrowserCookie[],
    url: string,
    referrer?: string,
): BrowserCookie[] {
    const host = hostOf(url);
    const same = host !== null && host === hostOf(referrer);
    if (same) return cookies;
    return cookies.filter((c) => c.sameSite?.toLowerCase() !== "strict");
}

const isPrivateStore = (store: { id: string; incognito?: boolean }) =>
    store.incognito === true || /private/i.test(store.id);

/**
 * Whether the browser has more than one cookie store that is not a private window's, so a download (which has no tab, and
 * so no store) can't be told which one its cookies are in. Only Firefox has such stores (containers); Chrome's second
 * store is the private window's, which is left alone anyway, so for [firefox] false this is always false. It throws when the
 * browser can't list its stores, and a caller takes that as "several".
 */
export async function hasSeveralStores(
    api: CookieApi,
    firefox: boolean,
): Promise<boolean> {
    if (!firefox) return false;
    const stores = await api.getAllCookieStores();
    return stores.filter((store) => !isPrivateStore(store)).length > 1;
}
