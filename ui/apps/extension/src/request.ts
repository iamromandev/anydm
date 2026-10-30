/** The cap the bridge puts on a request body (OSI-01), in bytes. */
export const MAX_BODY = 64 * 1024;

/** The longest address the extension hands over. */
const MAX_URL = 8192;

const SCHEMES = new Set([
    "http:",
    "https:",
    "ftp:",
    "sftp:",
    "magnet:",
]);

export interface BrowserCookie {
    name: string;
    value: string;
}

/** What is known about a link when it is added: everything but the token. */
export interface AddInput {
    url: string;
    pageUrl?: string;
    userAgent: string;
    cookies: BrowserCookie[];
}

/** The body of `POST /add` (the bridge's contract, `contract/bridge.json`). */
export interface AddBody {
    url: string;
    cookies?: string;
    headers?: Record<string, string>;
    referrer?: string;
    pageUrl?: string;
}

export interface Built {
    body: AddBody;
    /** The cookies were left out because the body would have passed the cap. */
    droppedCookies: boolean;
}

export type UrlCheck = "ok" | "unsupported" | "too-long";

export function checkUrl(url: string): UrlCheck {
    if (url.length > MAX_URL) return "too-long";
    let scheme: string;
    try {
        scheme = new URL(url).protocol;
    } catch {
        return "unsupported";
    }
    return SCHEMES.has(scheme) ? "ok" : "unsupported";
}

const bytes = (text: string) => new TextEncoder().encode(text).length;

export function buildAddBody(input: AddInput): Built {
    const body: AddBody = { url: input.url };
    const cookies = input.cookies.map((c) => `${c.name}=${c.value}`).join("; ");
    if (cookies !== "") body.cookies = cookies;
    body.headers = { "User-Agent": input.userAgent };
    if (input.pageUrl !== undefined) {
        body.referrer = input.pageUrl;
        body.pageUrl = input.pageUrl;
    }
    if (bytes(JSON.stringify(body)) <= MAX_BODY) {
        return { body, droppedCookies: false };
    }
    delete body.cookies;
    return { body, droppedCookies: cookies !== "" };
}
