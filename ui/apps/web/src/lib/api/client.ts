import { ApiError, unwrap } from "./envelope";

/** The API. One service: extract, downloads, and — once ported — torrents. */
export function apiUrl(path: string): string {
    const base =
        import.meta.env.PUBLIC_API_URL ||
        (import.meta.env.DEV ? "http://localhost:8030" : "");
    return `${base}${path}`;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
    let response: Response;
    try {
        response = await fetch(url, init);
    } catch {
        // A refused connection, DNS, CORS, an offline laptop. The browser's own
        // "Failed to fetch" says nothing a person can act on.
        throw new ApiError("Could not reach the API");
    }

    if (response.status === 204) {
        return undefined as T;
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

    return unwrap<T>(payload);
}

export function getApi<T>(path: string): Promise<T> {
    return request<T>(apiUrl(path));
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
