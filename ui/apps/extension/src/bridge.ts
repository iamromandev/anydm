/** The ports the bridge tries, in order (the app's `BridgeServer.PORTS`). */
export const PORTS: readonly number[] = Array.from(
    { length: 10 },
    (_, i) => 18400 + i,
);

export type Fetch = (url: string, init?: RequestInit) => Promise<Response>;

export interface Found {
    port: number;
    /** False when anydm is there but does not answer this browser yet. */
    allowed: boolean;
}

export interface Refused {
    kind: "refused";
    status: number;
    error: string;
    message: string;
}

export interface Unreachable {
    kind: "unreachable";
}

export interface Probed {
    kind: "probed";
    token: string;
    name: string | null;
    size: number | null;
    /** What kind of download it will be: DIRECT, MEDIA, TORRENT... */
    type: string;
    rangesSupported: boolean;
    duplicate: boolean;
}

export interface Added {
    kind: "added";
    id: number;
}

export type AddOutcome = Probed | Refused | Unreachable;
export type ConfirmOutcome = Added | Refused | Unreachable;

/** How long a port gets to answer a ping; all ports are asked at once, so this is also the most a search takes. */
const PING_MS = 1500;

/** The app gives up on a link after 30 s. */
export const CALL_MS = 35_000;

/**
 * What `/pair` and `/hello` get. The app answers them from memory and a small file, so a program that takes longer is not worth
 * waiting for: the queue of requests (`client.ts`) is one at a time, and a program that answered `/ping` and then said nothing
 * would hold everything else for as long as it is waited for. Only `/add` and `/confirm`, where the app reads a link, wait long.
 */
export const HANDSHAKE_MS = 4_000;

const deadlineOf = (path: string) =>
    path === "/add" || path === "/confirm" ? CALL_MS : HANDSHAKE_MS;

export const base = (port: number) => `http://127.0.0.1:${port}`;

export async function readJson(response: Response): Promise<unknown> {
    try {
        return await response.json();
    } catch {
        return undefined;
    }
}

async function ping(
    fetchFn: Fetch,
    port: number,
    timeoutMs: number,
): Promise<Found | null> {
    try {
        const response = await fetchFn(`${base(port)}/ping`, {
            credentials: "omit",
            cache: "no-store",
            // Nothing that answers a ping is sent anywhere else: a redirect would send the request to another address.
            redirect: "error",
            signal: AbortSignal.timeout(timeoutMs),
        });
        const body = (await readJson(response)) as
            { app?: unknown; error?: unknown } | undefined;
        if (response.status === 200 && body?.app === "anydm") {
            return { port, allowed: true };
        }
        // The bridge answers a browser it does not know with this and nothing else.
        if (response.status === 403 && body?.error === "forbidden") {
            return { port, allowed: false };
        }
    } catch {
        // Nothing there, or too slow.
    }
    return null;
}

/**
 * Every port that answers like anydm, all asked at once, so a search takes as long as the slowest port is allowed to, not the
 * sum. A bridge that answers this browser comes before one that refuses it, and [preferred] (the port that worked last)
 * before the rest. Answering like anydm proves nothing: any program can say `{"app":"anydm"}`.
 */
export async function findBridges(
    fetchFn: Fetch,
    preferred?: number,
    options: { timeoutMs?: number } = {},
): Promise<Found[]> {
    const timeoutMs = options.timeoutMs ?? PING_MS;
    const ports = [
        ...PORTS,
    ].sort(
        (a, b) => Number(b === preferred) - Number(a === preferred) || a - b,
    );
    const found = (
        await Promise.all(ports.map((port) => ping(fetchFn, port, timeoutMs)))
    ).filter((f): f is Found => f !== null);
    return [
        ...found.filter((f) => f.allowed),
        ...found.filter((f) => !f.allowed),
    ];
}

/** The first of [findBridges]: one that answers this browser, else one that refuses it. */
export async function findBridge(
    fetchFn: Fetch,
    preferred?: number,
    options: { timeoutMs?: number } = {},
): Promise<Found | null> {
    return (await findBridges(fetchFn, preferred, options))[0] ?? null;
}

/** What answered a POST: its status and its JSON, which is undefined when it was not JSON. */
export interface Reply {
    status: number;
    body: unknown;
}

/** Headers for a signed request, made from its path and the exact text that is sent. */
export type Signer = (
    path: string,
    text: string,
) => Promise<Record<string, string>>;

/** POSTs [text] as JSON; null when nothing answered. [headers] are added to the JSON content type. */
export async function postText(
    fetchFn: Fetch,
    port: number,
    path: string,
    text: string,
    headers: Record<string, string> = {},
): Promise<Reply | null> {
    try {
        const response = await fetchFn(`${base(port)}${path}`, {
            method: "POST",
            headers: { "Content-Type": "application/json", ...headers },
            body: text,
            credentials: "omit",
            cache: "no-store",
            // The signature and the cookies are for this address and port only: a redirect (a program on the port can send
            // one) would carry the body to wherever it names.
            redirect: "error",
            signal: AbortSignal.timeout(deadlineOf(path)),
        });
        return { status: response.status, body: await readJson(response) };
    } catch {
        return null;
    }
}

// The text is made once, so what is signed is what is sent. A signer that fails sends nothing.
async function post(
    fetchFn: Fetch,
    port: number,
    path: string,
    body: unknown,
    sign?: Signer,
): Promise<Reply | null> {
    const text = JSON.stringify(body);
    let headers: Record<string, string> = {};
    if (sign) {
        try {
            headers = await sign(path, text);
        } catch {
            return null;
        }
    }
    return postText(fetchFn, port, path, text, headers);
}

export function refused(status: number, body: unknown): Refused {
    const parsed = (body ?? {}) as { error?: unknown; message?: unknown };
    return {
        kind: "refused",
        status,
        error: typeof parsed.error === "string" ? parsed.error : "unknown",
        message: typeof parsed.message === "string" ? parsed.message : "",
    };
}

/** Reads a link without adding it; the answer holds a token for [confirmLink], good for 15 seconds. */
export async function addLink(
    fetchFn: Fetch,
    port: number,
    body: unknown,
    sign?: Signer,
): Promise<AddOutcome> {
    const answer = await post(fetchFn, port, "/add", body, sign);
    if (answer === null) return { kind: "unreachable" };
    const reply = answer.body as Record<string, unknown> | undefined;
    if (answer.status === 200 && typeof reply?.token === "string") {
        return {
            kind: "probed",
            token: reply.token,
            name: typeof reply.name === "string" ? reply.name : null,
            size: typeof reply.size === "number" ? reply.size : null,
            type: typeof reply.kind === "string" ? reply.kind : "DIRECT",
            rangesSupported: reply.rangesSupported === true,
            duplicate: reply.duplicate === true,
        };
    }
    return refused(answer.status, answer.body);
}

/** Adds what [addLink] read, once. */
export async function confirmLink(
    fetchFn: Fetch,
    port: number,
    token: string,
    sign?: Signer,
): Promise<ConfirmOutcome> {
    const answer = await post(fetchFn, port, "/confirm", { token }, sign);
    if (answer === null) return { kind: "unreachable" };
    const reply = answer.body as Record<string, unknown> | undefined;
    if (answer.status === 200 && typeof reply?.id === "number") {
        return { kind: "added", id: reply.id };
    }
    return refused(answer.status, answer.body);
}
