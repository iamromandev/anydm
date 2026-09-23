import { ApiError, unwrap } from "./envelope";
import { loadApiKey } from "./key";

export type ApiUrlOptions = {
    /**
     * Put the key in the query. Only for URLs the browser opens by itself,
     * which cannot carry a header: an `EventSource`, a download started by
     * navigation, and what a `<video>` fetches. Everything else sends the
     * header, so the key stays out of history and logs.
     */
    withKey?: boolean;
};

/** The API. One service: extract, downloads, and — once ported — torrents. */
export function apiUrl(path: string, options: ApiUrlOptions = {}): string {
    const base =
        import.meta.env.PUBLIC_API_URL ||
        (import.meta.env.DEV ? "http://localhost:8030" : "");
    const key = options.withKey ? loadApiKey() : "";
    if (!key) return `${base}${path}`;

    const join = path.includes("?") ? "&" : "?";
    return `${base}${path}${join}api_key=${encodeURIComponent(key)}`;
}

/** The header every `fetch` carries, when a key is stored. */
export function authHeaders(): Record<string, string> {
    const key = loadApiKey();
    return key ? { "X-API-Key": key } : {};
}

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/**
 * Hear about any 401, from any request. Returns the way to stop listening.
 *
 * A registry rather than a thrown error type because a 401 is the same news
 * wherever it happens, and the page wants it once, not at every call site.
 */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
    unauthorizedListeners.add(listener);
    return () => unauthorizedListeners.delete(listener);
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
    const payload = await requestEnvelope(url, init);
    return payload === undefined ? (undefined as T) : unwrap<T>(payload);
}

/**
 * The envelope, unopened.
 *
 * `undefined` for a 204, which carries no body at all.
 */
async function requestEnvelope(
    url: string,
    init?: RequestInit,
): Promise<unknown> {
    let response: Response;
    try {
        response = await fetch(url, {
            ...init,
            headers: { ...authHeaders(), ...init?.headers },
        });
    } catch {
        // A refused connection, DNS, CORS, an offline laptop. The browser's own
        // "Failed to fetch" says nothing a person can act on.
        throw new ApiError("Could not reach the API");
    }

    if (response.status === 204) {
        return undefined;
    }

    if (response.status === 401) {
        for (const listener of unauthorizedListeners) listener();
    }

    let payload: unknown;
    try {
        payload = await response.json();
    } catch {
        // A proxy's HTML error page, or a crash that never reached the
        // envelope. The status is the only thing worth repeating.
        throw new ApiError(
            `The API answered ${response.status} ${response.statusText}`.trim(),
            response.status,
        );
    }

    return payload;
}

export function getApi<T>(path: string): Promise<T> {
    return request<T>(apiUrl(path));
}

/** Where a list is in its pages, in the shape the UI names things. */
export type PageMeta = {
    page: number;
    pageSize: number;
    total: number;
    totalPages: number;
};

/**
 * A page of a list, with the pagination the envelope carries alongside it.
 *
 * `getApi` deliberately returns only `data`, which is what almost every caller
 * wants. A list that can be asked for more of itself needs the rest, so it
 * gets its own function rather than a second return value nobody else uses.
 */
export async function getPageApi<T>(
    path: string,
): Promise<{ data: T; meta: PageMeta }> {
    const payload = await requestEnvelope(apiUrl(path));
    const raw = (payload as { meta?: Record<string, unknown> }).meta;

    return {
        data: unwrap<T>(payload),
        meta: {
            page: Number(raw?.page ?? 1),
            pageSize: Number(raw?.page_size ?? 0),
            total: Number(raw?.total ?? 0),
            // One page, not zero: "there is nothing more to load" is the safe
            // reading when the service did not say.
            totalPages: Number(raw?.total_pages ?? 1),
        },
    };
}

export function postApi<T>(path: string, body: unknown): Promise<T> {
    return request<T>(apiUrl(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

export function deleteApi(path: string): Promise<void> {
    return request<void>(apiUrl(path), { method: "DELETE" });
}
