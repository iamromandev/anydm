import { ApiError, unwrap } from "./envelope";

/** The API. One service: extract, downloads, and — once ported — torrents. */
export function apiUrl(path: string): string {
    const base =
        import.meta.env.PUBLIC_API_URL ||
        (import.meta.env.DEV ? "http://localhost:8030" : "");
    return `${base}${path}`;
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
        response = await fetch(url, init);
    } catch {
        // A refused connection, DNS, CORS, an offline laptop. The browser's own
        // "Failed to fetch" says nothing a person can act on.
        throw new ApiError("Could not reach the API");
    }

    if (response.status === 204) {
        return undefined;
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
