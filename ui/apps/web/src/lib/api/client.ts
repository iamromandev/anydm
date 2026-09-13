import { unwrap } from "./envelope";

/** The FastAPI service: extract, YouTube and direct downloads. */
export function apiUrl(path: string): string {
    const base =
        import.meta.env.PUBLIC_API_URL ||
        (import.meta.env.DEV ? "http://localhost:8003" : "");
    return `${base}${path}`;
}

/** The Bun service: torrents only, until that port lands. */
export function bunUrl(path: string): string {
    const base =
        import.meta.env.PUBLIC_BASE_URL ||
        (import.meta.env.DEV ? "http://localhost:3000" : "");
    return `${base}${path}`;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
    const response = await fetch(url, init);
    if (response.status === 204) {
        return undefined as T;
    }
    return unwrap<T>(await response.json());
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

export function getBun<T>(path: string): Promise<T> {
    return request<T>(bunUrl(path));
}

export function postBun<T>(path: string, body?: unknown): Promise<T> {
    return request<T>(bunUrl(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
}

export function deleteBun(path: string): Promise<void> {
    return request<void>(bunUrl(path), { method: "DELETE" });
}
