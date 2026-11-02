import {
    addLink,
    confirmLink,
    findBridges,
    type AddOutcome,
    type ConfirmOutcome,
    type Fetch,
    type Refused,
    type Signer,
} from "./bridge";
import { utf8 } from "./crypto";
import {
    loadPairing,
    requestMac,
    sayHello,
    type HelloResult,
    type Pairing,
    type PairingStorage,
} from "./pairing";
import type { AddBody } from "./request";

/** What finding anydm came to. */
export type Connected =
    /** Nothing answers like anydm. */
    | { kind: "none" }
    /** anydm is there but does not answer this browser. */
    | { kind: "forbidden" }
    /**
     * anydm answers on [port]. [verified]: this browser is paired and the app proved it holds the secret, so cookies and
     * signed requests may go there. Not verified: this browser is not paired, so nothing private goes (no cookies).
     */
    | { kind: "ready"; port: number; verified: boolean }
    /** This browser is paired, but nothing that answered could be verified, so nothing is sent. */
    | { kind: "unverified"; reason: UnverifiedReason }
    /** The extension's background part did not answer (only seen from a page that asks it). */
    | { kind: "no-background" };

/**
 * Why a paired browser could not verify anydm: it is told `unpaired` (the app does not know this browser; the secret is
 * kept), the app can't read its paired browsers just now (transient), or what answered could not prove it holds the secret.
 */
export type UnverifiedReason = "unpaired" | "unavailable" | "no-proof";

/** What the flow needs from the bridge: the same whether it is the session's owner or asks the owner. */
export interface BridgeApi {
    connect(preferred?: number): Promise<Connected>;
    add(port: number, body: AddBody): Promise<AddOutcome>;
    confirm(port: number, token: string): Promise<ConfirmOutcome>;
}

export interface ClientDeps {
    fetch: Fetch;
    storage: PairingStorage;
    /** [bytes] fresh random bytes as hex; the browser's random source unless a test says otherwise. */
    random?: (bytes: number) => string;
    /** Reads the clocks: a monotonic one (`performance.now()`) and the wall clock (`Date.now()`), in milliseconds. */
    clock?: () => Reading;
}

/** What the clocks said at one moment. */
export interface Reading {
    mono: number;
    wall: number;
}

const readClocks = (): Reading => ({
    mono: performance.now(),
    wall: Date.now(),
});

/**
 * How old a verified session may be before cookies are sent in it. The app never tells the extension that it has quit, and a
 * program that takes its port answers the next signed request as it pleases, so a session is trusted for cookies only for a
 * moment after the hello that proved the app; after that the app is proved again first.
 */
export const COOKIES_TRUST_MS = 5_000;

// How long ago [since] was: the longer of what the two clocks say. The monotonic clock does not run while a computer sleeps
// (it can't be set, though); the wall clock runs on but can be set. A wall clock that went back says nothing, so it is "long ago".
function ageSince(since: Reading, now: Reading): number {
    const wall = now.wall - since.wall;
    if (wall < 0) return Number.POSITIVE_INFINITY;
    return Math.max(now.mono - since.mono, wall);
}

interface Proved {
    port: number;
    result: HelloResult;
    at: Reading;
}

interface Session {
    port: number;
    id: string;
    nonceA: string;
    /** The counter of the last request signed; the next is one more. */
    counter: number;
    /** When the app last proved it holds the secret on this port. */
    verifiedAt: Reading;
}

/** The answers that mean the session ended or was cut, which one more hello puts right. Nothing was done for the request. */
const SESSION_LOST = new Set([
    "session_expired",
    "unauthorized",
    "replay",
]);

/**
 * The one owner of the session with the app (the background worker owns it; a page asks it, see `rpc.ts`): the app has one
 * session per paired browser, and a new hello ends the last, so a second owner would cut the first off. It
 *
 * - says hello and checks the app's proof before anything private is sent: cookies only ever go to a port whose hello
 *   verified, and a program that can't prove it is skipped and the next port that answers is tried;
 * - signs `/add` and `/confirm` with a counter that goes up, one request at a time, in the order asked;
 * - sends cookies only in a session verified within [COOKIES_TRUST_MS]; for an older one it says hello again first, in the
 *   same turn of the queue, so cookies never go to a program that took the port since;
 * - says hello again, once for a request, when the app answers that the session ended (`session_expired`, `unauthorized`,
 *   `replay`), and never in a loop;
 * - keeps the pairing on `unpaired` and `store_unavailable` answers: they are shown, not acted on (a program that only
 *   says "unpaired" must not be able to make the extension forget its secret).
 */
export class BridgeClient implements BridgeApi {
    private session: Session | null = null;
    private tail: Promise<unknown> = Promise.resolve();

    constructor(private readonly deps: ClientDeps) {}

    /** Finds anydm and, if this browser is paired, proves it is the app this browser paired with. */
    async connect(preferred?: number): Promise<Connected> {
        const found = await findBridges(this.deps.fetch, preferred);
        if (found.length === 0) return { kind: "none" };
        const ports = found.filter((f) => f.allowed);
        if (ports.length === 0) return { kind: "forbidden" };
        const pairing = await loadPairing(this.deps.storage);
        if (pairing === null) {
            this.session = null;
            return { kind: "ready", port: ports[0]!.port, verified: false };
        }
        return this.queued(async () => {
            // All at once, so a port that answers /ping and then stalls costs one deadline, however many there are. A hello
            // is made on each port in any case: only the app that holds the secret gives a proof, and no other port learns
            // anything from it (the proofs are bound to the port).
            const asked = await Promise.all(
                ports.map(({ port }) => this.proveOn(port, pairing)),
            );
            for (const one of asked) {
                if (one.result.kind !== "verified") this.settle(one, pairing);
            }
            const verified = asked.find(
                (one) => one.result.kind === "verified",
            );
            if (verified) {
                this.settle(verified, pairing);
                return {
                    kind: "ready",
                    port: verified.port,
                    verified: true,
                } as const;
            }
            let reason: UnverifiedReason = "no-proof";
            for (const { result } of asked) reason = worse(reason, result);
            return { kind: "unverified", reason } as const;
        });
    }

    /** Reads a link. Without a verified pairing no cookies go with it, whatever [body] holds. */
    add(port: number, body: AddBody): Promise<AddOutcome> {
        return this.queued(() =>
            this.send(
                port,
                () => addLink(this.deps.fetch, port, withoutCookies(body)),
                (sign) => addLink(this.deps.fetch, port, body, sign),
                body.cookies !== undefined && body.cookies !== "",
            ),
        );
    }

    /** Adds what [add] read. */
    confirm(port: number, token: string): Promise<ConfirmOutcome> {
        return this.queued(() =>
            this.send(
                port,
                () => confirmLink(this.deps.fetch, port, token),
                (sign) => confirmLink(this.deps.fetch, port, token, sign),
                false,
            ),
        );
    }

    /** Whether the program on [port] proves it holds the secret right now (a fresh hello). False when not paired. */
    verify(port: number): Promise<boolean> {
        return this.queued(async () => {
            const pairing = await loadPairing(this.deps.storage);
            if (pairing === null) return false;
            return (await this.hello(port, pairing)).kind === "verified";
        });
    }

    // One at a time, in the order asked, whatever happens to the one before.
    private queued<T>(job: () => Promise<T>): Promise<T> {
        const run = this.tail.then(job, job);
        this.tail = run.catch(() => undefined);
        return run;
    }

    private async hello(port: number, pairing: Pairing): Promise<HelloResult> {
        const one = await this.proveOn(port, pairing);
        this.settle(one, pairing);
        return one.result;
    }

    // A hello, and when its answer came: that is the moment the app was proved, whatever else is waited for after it.
    private async proveOn(port: number, pairing: Pairing): Promise<Proved> {
        const result = await sayHello(
            this.deps.fetch,
            port,
            pairing,
            this.deps.random,
        );
        return { port, result, at: this.reading() };
    }

    // What a hello comes to for the session: a verified one begins it, a failed one on the session's port ends it.
    private settle({ port, result, at }: Proved, pairing: Pairing) {
        if (result.kind === "verified") {
            this.session = {
                port,
                id: pairing.id,
                nonceA: result.nonceA,
                counter: 0,
                verifiedAt: at,
            };
        } else if (this.session?.port === port) {
            this.session = null;
        }
    }

    // Sends one request. [plain] is for a browser that is not paired (nothing signed, no cookies); [signed] for one that is.
    // [withCookies] is whether [signed] carries cookies: then the session must have been verified a moment ago, or the app is
    // proved again first.
    private async send<T extends AddOutcome | ConfirmOutcome>(
        port: number,
        plain: () => Promise<T>,
        signed: (sign: Signer) => Promise<T>,
        withCookies: boolean,
    ): Promise<T> {
        const pairing = await loadPairing(this.deps.storage);
        if (pairing === null) {
            this.session = null;
            return plain();
        }
        let session = this.sessionFor(port, pairing);
        if (session !== null && withCookies && this.tooOld(session)) {
            session = null;
        }
        if (session === null) {
            const result = await this.hello(port, pairing);
            if (result.kind !== "verified") return unverified(result) as T;
            session = this.sessionFor(port, pairing)!;
        }
        let outcome = await signed(signerOf(session, pairing));
        if (sessionLost(outcome)) {
            // Once: a second refusal after a fresh, verified hello is the answer.
            const result = await this.hello(port, pairing);
            if (result.kind !== "verified") return unverified(result) as T;
            outcome = await signed(
                signerOf(this.sessionFor(port, pairing)!, pairing),
            );
        }
        return outcome;
    }

    private reading(): Reading {
        return (this.deps.clock ?? readClocks)();
    }

    private tooOld(session: Session): boolean {
        return ageSince(session.verifiedAt, this.reading()) >= COOKIES_TRUST_MS;
    }

    private sessionFor(port: number, pairing: Pairing): Session | null {
        const session = this.session;
        return session && session.port === port && session.id === pairing.id
            ? session
            : null;
    }
}

// What an unpaired browser sends: no cookies, whatever the caller put in the body.
function withoutCookies(body: AddBody): AddBody {
    const { cookies: _cookies, ...rest } = body;
    return rest;
}

function signerOf(session: Session, pairing: Pairing): Signer {
    return async (path, text) => {
        // Spent when it is signed, so a request that never gets an answer can't be sent again with the same number.
        session.counter += 1;
        const counter = session.counter;
        return {
            "X-Anydm-Id": pairing.id,
            "X-Anydm-Counter": String(counter),
            "X-Anydm-Mac": await requestMac(
                pairing.secret,
                session.nonceA,
                counter,
                path,
                utf8(text),
                session.port,
            ),
        };
    };
}

function sessionLost(outcome: AddOutcome | ConfirmOutcome): boolean {
    return (
        outcome.kind === "refused" &&
        outcome.status === 401 &&
        SESSION_LOST.has(outcome.error)
    );
}

// The order of what is told: a transient answer, then 'the app does not know you', then 'could not prove itself'.
function worse(
    reason: UnverifiedReason,
    result: HelloResult,
): UnverifiedReason {
    const next: UnverifiedReason =
        result.kind === "unavailable"
            ? "unavailable"
            : result.kind === "unpaired"
              ? "unpaired"
              : "no-proof";
    const order: UnverifiedReason[] = [
        "unavailable",
        "unpaired",
        "no-proof",
    ];
    return order.indexOf(next) < order.indexOf(reason) ? next : reason;
}

// What a request comes to when its hello did not verify: the request is not sent.
function unverified(
    result: Exclude<HelloResult, { kind: "verified" }>,
): AddOutcome | ConfirmOutcome {
    if (result.kind === "unreachable") return { kind: "unreachable" };
    const refusal = (status: number, error: string): Refused => ({
        kind: "refused",
        status,
        error,
        message: "",
    });
    switch (result.kind) {
        case "unpaired":
            return refusal(401, "unpaired");
        case "unavailable":
            return refusal(503, "store_unavailable");
        default:
            return refusal(401, "unverified");
    }
}
