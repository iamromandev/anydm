import { unwrap } from "./envelope";

/** The API. One service: extract, downloads, and — once ported — torrents. */
export function apiUrl(path: string): string {
    const base =
        import.meta.env.PUBLIC_API_URL ||
        (import.meta.env.DEV ? "http://localhost:8030" : "");
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
