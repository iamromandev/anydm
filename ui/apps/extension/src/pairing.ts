import { findBridges, postText, type Fetch } from "./bridge";
import {
    bytesOfHex,
    hmacHex,
    hmacMatches,
    randomHex,
    sha256Hex,
    utf8,
} from "./crypto";

// The wire format is written down in docs/conf/tasks/plexo-parity/status.md ("Phase 5a wire format, FINAL") and pinned by
// `contract/pairing-vectors.json`, which the app's `PairingVectorsTest.kt` reads too. A message to sign is its parts joined
// with `|`; a nonce, an id and a hash are their lower-case hex text; the counter and the port are decimal.

/** What a pairing code is made of (Crockford base32, 80 bits in sixteen characters). */
export const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_LENGTH = 16;

/**
 * The canonical code for what the user typed, or null if it is not one. Drops ASCII space, tab, CR, LF and `-`; makes ASCII
 * letters upper case (ASCII only: no Unicode case mapping, so a dotless i or a long s is no I or S); reads `O` as `0` and `I`
 * and `L` as `1`; then wants exactly sixteen characters of the alphabet. Anything not ASCII is not a code.
 */
export function normaliseCode(input: string): string | null {
    let code = "";
    for (const char of input) {
        if (char === " " || char === "\t" || char === "\r" || char === "\n") {
            continue;
        }
        if (char === "-") continue;
        const point = char.codePointAt(0)!;
        if (point > 0x7f) return null;
        let upper = char;
        if (point >= 0x61 && point <= 0x7a) upper = char.toUpperCase();
        if (upper === "O") upper = "0";
        else if (upper === "I" || upper === "L") upper = "1";
        code += upper;
    }
    if (code.length !== CODE_LENGTH) return null;
    for (const char of code) if (!CODE_ALPHABET.includes(char)) return null;
    return code;
}

function portText(port: number): string {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new RangeError("the port is a TCP port, 1 to 65535");
    }
    return String(port);
}

function counterText(counter: number): string {
    if (!Number.isSafeInteger(counter) || counter < 1) {
        throw new RangeError("the counter is 1 to 2^53-1");
    }
    return String(counter);
}

function secretKey(secret: string): Uint8Array<ArrayBuffer> {
    const key = bytesOfHex(secret);
    if (key === null || key.length !== 32) {
        throw new RangeError("the secret is 32 bytes of hex");
    }
    return key;
}

const pairKey = (code: string) => utf8(code);

const appMessage = (nonceE: string, nonceA: string, port: number) =>
    `anydm-app-1|${nonceE}|${nonceA}|${portText(port)}`;

const helloMessage = (nonceE: string, nonceA: string, port: number) =>
    `anydm-hello-1|${nonceE}|${nonceA}|${portText(port)}`;

/** `proofE`, which the extension sends to `/pair`: it proves the code without sending it. */
export const pairProof = async (code: string, nonceE: string, port: number) =>
    hmacHex(pairKey(code), `anydm-pair-1|${nonceE}|${portText(port)}`);

/** `proofA`, which the app answers `/pair` with: what the extension checks to know the app has the code. */
export const appProof = async (
    code: string,
    nonceE: string,
    nonceA: string,
    port: number,
) =>
    hmacHex(pairKey(code), `anydm-app-1|${nonceE}|${nonceA}|${portText(port)}`);

/** The 32-byte secret both sides keep (hex). It is no proof to anyone, so it has no port in it. */
export const deriveSecret = async (
    code: string,
    nonceE: string,
    nonceA: string,
) => hmacHex(pairKey(code), `anydm-secret-1|${nonceE}|${nonceA}`);

/** `proof` of the `/hello` request: the extension shows the app it holds the secret. */
export const helloRequestProof = async (
    secret: string,
    id: string,
    nonceE: string,
    port: number,
) =>
    hmacHex(
        secretKey(secret),
        `anydm-hello-req-1|${id}|${nonceE}|${portText(port)}`,
    );

/** `proof` of the `/hello` reply: what the extension checks to know the app holds the secret. */
export const helloProof = async (
    secret: string,
    nonceE: string,
    nonceA: string,
    port: number,
) => hmacHex(secretKey(secret), helloMessage(nonceE, nonceA, port));

/** `X-Anydm-Mac` of a signed `/add` or `/confirm`, over the exact bytes of the body as they are sent. */
export async function requestMac(
    secret: string,
    nonceA: string,
    counter: number,
    path: string,
    body: Uint8Array,
    port: number,
): Promise<string> {
    if (path !== "/add" && path !== "/confirm") {
        throw new RangeError("only /add and /confirm are signed");
    }
    return hmacHex(
        secretKey(secret),
        `anydm-req-1|${nonceA}|${counterText(counter)}|${path}|${await sha256Hex(body)}|${portText(port)}`,
    );
}

// ---- what is kept ----

/** Where the pairing is kept: `storage.local`, which only this extension can read. Nothing else holds the secret. */
export const PAIRING_KEY = "anydm.pairing";

/** What pairing leaves: the app's id for this browser, and the 32-byte secret (hex). The secret is never shown or logged. */
export interface Pairing {
    id: string;
    secret: string;
}

/** The part of `storage.local` this uses; Chrome and Firefox both answer with promises in Manifest V3. */
export interface PairingStorage {
    get(key: string): Promise<Record<string, unknown>>;
    set(items: Record<string, unknown>): Promise<void>;
    remove(key: string): Promise<void>;
}

const HEX32 = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;

/** The pairing that is kept, or null when there is none, it is damaged, or the store can't be read. */
export async function loadPairing(
    storage: PairingStorage,
): Promise<Pairing | null> {
    try {
        const raw = (await storage.get(PAIRING_KEY))[PAIRING_KEY];
        if (typeof raw !== "object" || raw === null) return null;
        const { id, secret } = raw as Record<string, unknown>;
        if (typeof id !== "string" || !HEX32.test(id)) return null;
        if (typeof secret !== "string" || !HEX64.test(secret)) return null;
        return { id, secret };
    } catch {
        return null;
    }
}

export async function savePairing(
    storage: PairingStorage,
    pairing: Pairing,
): Promise<void> {
    await storage.set({
        [PAIRING_KEY]: { id: pairing.id, secret: pairing.secret },
    });
}

/** Forget: the secret leaves this browser. (The app keeps its side until the person revokes the browser in anydm.) */
export async function forgetPairing(storage: PairingStorage): Promise<void> {
    await storage.remove(PAIRING_KEY);
}

// ---- pairing ----

export type PairOutcome =
    | { kind: "paired" }
    /** What was typed is not a code: nothing was sent. */
    | { kind: "bad-code" }
    /** Nothing answered. */
    | { kind: "none" }
    /** anydm is there but does not answer this browser. */
    | { kind: "forbidden" }
    /** No program that answered took the code, or could prove it knew it: a wrong code, or not the anydm app. */
    | { kind: "wrong-code" }
    /** anydm is not waiting for a code. */
    | { kind: "no-code" }
    | { kind: "throttled"; retryAfterSeconds: number }
    /** anydm had the right code but could not save the pairing; the code still works, so trying again is fine. */
    | { kind: "not-saved" }
    /**
     * anydm took the code and proved it, but this browser could not keep the pairing. The code is spent, so a new one is
     * needed, and anydm holds a pairing nobody has the secret of, which can be revoked in its Browsers list.
     */
    | { kind: "not-kept" }
    /** anydm can't read its paired browsers just now; transient. */
    | { kind: "unavailable" }
    /** The extension and anydm do not speak the same version. */
    | { kind: "mismatch" }
    | { kind: "failed" };

export interface PairDeps {
    fetch: Fetch;
    storage: PairingStorage;
    /** [bytes] fresh random bytes as hex; the browser's random source unless a test says otherwise. */
    random?: (bytes: number) => string;
}

type PortResult = PairOutcome | { kind: "unreachable" };

// Which answer is told to the person when no port paired: the one that says the most about what to do next. None of the
// refusals carries a proof (before the browser is paired there is nothing the app could prove itself with), so a program in
// front can make "wrong code" the one shown and hide the real app's "no code". It can't pair the browser, and it never has the
// code, only a proof of it bound to its own port; the message is only a hint. (Documented in the README.)
const RANK: PairOutcome["kind"][] = [
    "not-kept",
    "not-saved",
    "throttled",
    "unavailable",
    "wrong-code",
    "no-code",
    "mismatch",
    "failed",
];

const rankOf = (outcome: PairOutcome) => {
    const rank = RANK.indexOf(outcome.kind);
    return rank === -1 ? RANK.length : rank;
};

/**
 * Pairs this browser with the anydm that is showing [codeText]. The code never goes over the wire: only a proof of it does,
 * bound to the port, and the extension keeps `{id, secret}` only once the app's own proof checks out. A program that
 * answers like anydm but can't produce that proof is skipped and the next port that answers is tried.
 */
export async function pair(
    deps: PairDeps,
    codeText: string,
    preferred?: number,
): Promise<PairOutcome> {
    const code = normaliseCode(codeText);
    if (code === null) return { kind: "bad-code" };
    const found = await findBridges(deps.fetch, preferred);
    if (found.length === 0) return { kind: "none" };
    const ports = found.filter((f) => f.allowed);
    if (ports.length === 0) return { kind: "forbidden" };

    let best: PairOutcome | null = null;
    for (const { port } of ports) {
        const result = await pairOn(deps, code, port);
        if (result.kind === "paired") return result;
        // The app that proved it had the code is the app, and the code is spent: no other port is asked with it.
        if (result.kind === "not-kept") return result;
        if (result.kind === "unreachable") continue;
        if (best === null || rankOf(result) < rankOf(best)) best = result;
    }
    return best ?? { kind: "none" };
}

async function pairOn(
    deps: PairDeps,
    code: string,
    port: number,
): Promise<PortResult> {
    const random = deps.random ?? randomHex;
    // The app has not saved a pairing it answered 500 for, and its code still works: once more is safe.
    for (let attempt = 0; ; attempt += 1) {
        const nonceE = random(16);
        const proofE = await pairProof(code, nonceE, port);
        const reply = await postText(
            deps.fetch,
            port,
            "/pair",
            JSON.stringify({ nonceE, proofE }),
        );
        if (reply === null) return { kind: "unreachable" };
        const body = (reply.body ?? {}) as Record<string, unknown>;
        switch (reply.status) {
            case 200:
                return keep(deps, code, nonceE, port, body);
            case 401:
                return { kind: "wrong-code" };
            case 409:
                return { kind: "no-code" };
            case 429:
                return {
                    kind: "throttled",
                    retryAfterSeconds:
                        typeof body.retryAfterSeconds === "number"
                            ? body.retryAfterSeconds
                            : 60,
                };
            case 500:
                if (attempt === 0 && body.error === "not_saved") continue;
                return { kind: "not-saved" };
            case 503:
                return { kind: "unavailable" };
            case 400:
                return { kind: "mismatch" };
            default:
                return { kind: "failed" };
        }
    }
}

// The app answered 200. Keep nothing unless its proof checks out for this nonce, this code and this port.
async function keep(
    deps: PairDeps,
    code: string,
    nonceE: string,
    port: number,
    body: Record<string, unknown>,
): Promise<PortResult> {
    const { id, nonceA, proofA } = body;
    if (
        typeof id !== "string" ||
        !HEX32.test(id) ||
        typeof nonceA !== "string" ||
        !HEX32.test(nonceA) ||
        typeof proofA !== "string" ||
        !(await hmacMatches(
            pairKey(code),
            proofA,
            appMessage(nonceE, nonceA, port),
        ))
    ) {
        // Whatever answered is not the app that has the code.
        return { kind: "wrong-code" };
    }
    try {
        await savePairing(deps.storage, {
            id,
            secret: await deriveSecret(code, nonceE, nonceA),
        });
    } catch {
        return { kind: "not-kept" };
    }
    return { kind: "paired" };
}

// ---- saying hello ----

export type HelloResult =
    /** The app proved it holds the secret, and a session with [nonceA] has begun (the counter starts at 1). */
    | { kind: "verified"; nonceA: string }
    /** The app does not know this browser (an unknown id and a wrong proof are answered alike). */
    | { kind: "unpaired" }
    /** The app can't read its paired browsers just now; transient, and the pairing is kept. */
    | { kind: "unavailable" }
    /** Something answered, but could not prove it holds the secret. Nothing may be sent to it. */
    | { kind: "no-proof" }
    | { kind: "unreachable" }
    | { kind: "failed" };

/**
 * Says hello to the program on [port]: proves this browser knows the secret and checks that the answer proves the same. Only
 * `verified` lets anything private (cookies, a signed request) go there, and a hello ends the last session of this id, so
 * only the one owner of the session (the background worker) says it.
 */
export async function sayHello(
    fetchFn: Fetch,
    port: number,
    pairing: Pairing,
    random: (bytes: number) => string = randomHex,
): Promise<HelloResult> {
    const nonceE = random(16);
    const proof = await helloRequestProof(
        pairing.secret,
        pairing.id,
        nonceE,
        port,
    );
    const reply = await postText(
        fetchFn,
        port,
        "/hello",
        JSON.stringify({ id: pairing.id, nonceE, proof }),
    );
    if (reply === null) return { kind: "unreachable" };
    const body = (reply.body ?? {}) as Record<string, unknown>;
    switch (reply.status) {
        case 200: {
            const { nonceA, proof: given } = body;
            if (
                typeof nonceA === "string" &&
                HEX32.test(nonceA) &&
                typeof given === "string" &&
                (await hmacMatches(
                    secretKey(pairing.secret),
                    given,
                    helloMessage(nonceE, nonceA, port),
                ))
            ) {
                return { kind: "verified", nonceA };
            }
            return { kind: "no-proof" };
        }
        case 401:
            return body.error === "unpaired"
                ? { kind: "unpaired" }
                : { kind: "failed" };
        case 503:
            return { kind: "unavailable" };
        default:
            return { kind: "failed" };
    }
}
