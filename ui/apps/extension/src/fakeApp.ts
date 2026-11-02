// A stand-in for the anydm app's side of the bridge, for the tests only (nothing builds it into the extension). It follows
// `BridgeServer` and `BridgePairing` in `cross/shared` as the status notes describe them: the same formulas, answers and
// error codes, with the proofs bound to the port the request came to. The formulas themselves are pinned against
// `contract/pairing-vectors.json` in `pairing.test.ts`, so this and the extension can't both be wrong the same way.
import contract from "../contract/bridge.json";
import http from "../contract/pairing-http.json";
import type { Fetch } from "./bridge";
import { randomHex, utf8 } from "./crypto";
import {
    appProof,
    deriveSecret,
    helloProof,
    helloRequestProof,
    pairProof,
    requestMac,
} from "./pairing";

/** What a program on a port was sent. */
export interface Seen {
    port: number;
    path: string;
    method: string;
    headers: Record<string, string>;
    text: string;
}

export interface Machine {
    respond(url: string, init?: RequestInit): Promise<Response> | Response;
}

export const json = (status: number, body: unknown, headers = {}) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json", ...headers },
    });

const error = (status: number, code: string, message = "") =>
    json(status, { error: code, message });

interface AnswerSpec {
    status: number;
    body?: Record<string, unknown>;
    shape?: Record<string, string>;
}

/**
 * An answer of the contract (`contract/pairing-http.json`), by name: its status and the exact fields, plus [values] for the
 * fields whose value varies. The statuses, codes and messages are the contract's, so this can't say them differently from the
 * app, which the contract's other reader (`PairingHttpContractTest.kt`) holds to the same file.
 */
function answer(
    name: string,
    values: Record<string, unknown> = {},
    headers: Record<string, string> = {},
): Response {
    const spec = (http.answers as Record<string, AnswerSpec | undefined>)[name];
    if (!spec) throw new Error(`the contract has no answer ${name}`);
    return json(spec.status, { ...spec.body, ...values }, headers);
}

/** A machine where each port in [machines] is a program, and every other port refuses the connection. */
export function machineOf(machines: Record<number, Machine>): Fetch {
    return async (url, init) => {
        const machine = machines[Number(new URL(url).port)];
        if (!machine) throw new TypeError("connection refused");
        return machine.respond(url, init);
    };
}

const headersOf = (init?: RequestInit): Record<string, string> =>
    Object.fromEntries(
        Object.entries((init?.headers ?? {}) as Record<string, string>).map(
            ([
                name,
                value,
            ]) => [
                name.toLowerCase(),
                value,
            ],
        ),
    );

const HEX32 = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;

/**
 * The real app: a code to pair with, paired browsers, one session per id, and `/add` and `/confirm` that take a link. Its
 * switches say what is wrong with it (store down, save failing, the browser not allowed, ...).
 */
export class FakeApp implements Machine {
    readonly seen: Seen[] = [];
    readonly secrets = new Map<string, string>();
    readonly sessions = new Map<
        string,
        { nonceA: string; counter: number; startedAt: number }
    >();
    /** `/add` bodies and `/confirm` tokens that were taken, and whether each was signed. */
    readonly added: { signed: boolean; body: Record<string, unknown> }[] = [];
    readonly confirmed: { signed: boolean; token: string }[] = [];
    private readonly tokens = new Map<string, boolean>();
    private nextId = 1;

    code: string | null = null;
    wrongTries = 0;
    burnAfter = 5;
    /** After this many `/pair` calls answer 429. */
    throttleAfter = Number.POSITIVE_INFINITY;
    private pairCalls = 0;
    /** The file of paired browsers can't be read. */
    storeDown = false;
    /** The next this many `/pair` calls with the right code fail to save. */
    saveFailures = 0;
    /** The app does not answer this browser's origin. */
    allowed = true;
    sessionLife = 60_000;
    clock = () => Date.now();
    size: number | null = 5000;
    duplicate = false;
    /** Called before `/add` and `/confirm` answer; hold it to keep a call open. */
    beforeAdd?: () => Promise<void>;
    beforeConfirm?: () => Promise<void>;
    /** Replaces the answer to `/add` or `/confirm` (when it returns a response). */
    addReply?: () => Response | undefined | Promise<Response | undefined>;
    confirmReply?: () => Response | undefined | Promise<Response | undefined>;
    /** Called for each `/add` and `/confirm` that was taken, with the number of calls open at that moment. */
    open = 0;
    peakOpen = 0;

    constructor(readonly port: number) {}

    /** What a person does in the app: start pairing, which shows this code. */
    startPairing(code: string) {
        this.code = code;
        this.wrongTries = 0;
    }

    /** What revoking a browser does in the app. */
    revoke(id: string) {
        this.secrets.delete(id);
        this.sessions.delete(id);
    }

    /** The one browser this app has paired. */
    get id(): string {
        return [
            ...this.secrets.keys(),
        ][0]!;
    }

    get secret(): string {
        return this.secrets.get(this.id)!;
    }

    /** Ends the session of [id], as a newer hello would. */
    cutSession(id: string) {
        this.sessions.delete(id);
    }

    async respond(url: string, init?: RequestInit): Promise<Response> {
        const u = new URL(url);
        const text = typeof init?.body === "string" ? init.body : "";
        const headers = headersOf(init);
        this.seen.push({
            port: this.port,
            path: u.pathname,
            method: init?.method ?? "GET",
            headers,
            text,
        });
        if (!this.allowed) {
            return error(
                403,
                "forbidden",
                "not an origin this bridge answers to",
            );
        }
        switch (u.pathname) {
            case "/ping":
                return json(200, contract.ping);
            case "/pair":
                return this.pair(text);
            case "/hello":
                return this.hello(text);
            case "/add":
            case "/confirm":
                return this.link(u.pathname, text, headers);
            default:
                return error(404, "not_found");
        }
    }

    private parse(text: string): Record<string, unknown> | null {
        try {
            const value = JSON.parse(text);
            // A JSON object: not null, not a list.
            return typeof value === "object" &&
                value !== null &&
                !Array.isArray(value)
                ? value
                : null;
        } catch {
            return null;
        }
    }

    private async pair(text: string): Promise<Response> {
        const body = this.parse(text);
        if (body === null) return answer("body-not-an-object");
        const nonceE = body.nonceE;
        const proofE = body.proofE;
        if (
            typeof nonceE !== "string" ||
            !HEX32.test(nonceE) ||
            typeof proofE !== "string" ||
            !HEX64.test(proofE)
        ) {
            return answer("pair-bad-request");
        }
        if (this.code === null) return answer("no-code");
        this.pairCalls += 1;
        if (this.pairCalls > this.throttleAfter) {
            return answer(
                "too-many-attempts",
                { retryAfterSeconds: 30 },
                { "Retry-After": "30" },
            );
        }
        if (proofE !== (await pairProof(this.code, nonceE, this.port))) {
            this.wrongTries += 1;
            if (this.wrongTries >= this.burnAfter) this.code = null;
            return answer("wrong-code");
        }
        if (this.saveFailures > 0) {
            this.saveFailures -= 1;
            return answer("not-saved");
        }
        const code = this.code;
        const id = randomHex(16);
        const nonceA = randomHex(16);
        this.secrets.set(id, await deriveSecret(code, nonceE, nonceA));
        this.code = null;
        return answer("pair-paired", {
            id,
            nonceA,
            proofA: await appProof(code, nonceE, nonceA, this.port),
        });
    }

    private async hello(text: string): Promise<Response> {
        const body = this.parse(text);
        if (body === null) return answer("body-not-an-object");
        const { id, nonceE, proof } = body;
        if (
            typeof id !== "string" ||
            !HEX32.test(id) ||
            typeof nonceE !== "string" ||
            !HEX32.test(nonceE) ||
            typeof proof !== "string" ||
            !HEX64.test(proof)
        ) {
            return answer("hello-bad-request");
        }
        if (this.storeDown) return answer("store-unavailable");
        const secret = this.secrets.get(id);
        if (
            secret === undefined ||
            proof !== (await helloRequestProof(secret, id, nonceE, this.port))
        ) {
            return answer("unpaired");
        }
        const nonceA = randomHex(16);
        this.sessions.set(id, {
            nonceA,
            counter: 0,
            startedAt: this.clock(),
        });
        return answer("hello-ok", {
            nonceA,
            proof: await helloProof(secret, nonceE, nonceA, this.port),
        });
    }

    // Who sent it, in the order the app checks: none at all is a caller that is not paired; any other credentials are
    // checked, and a wrong one is refused and never taken for none.
    private async authenticate(
        path: string,
        text: string,
        headers: Record<string, string>,
    ): Promise<"anonymous" | "ok" | Response> {
        const id = headers["x-anydm-id"];
        const counter = headers["x-anydm-counter"];
        const mac = headers["x-anydm-mac"];
        if (id === undefined && counter === undefined && mac === undefined) {
            return "anonymous";
        }
        const unauthorized = () => answer("unauthorized");
        if (id === undefined || !HEX32.test(id)) return unauthorized();
        if (counter === undefined || !/^[1-9][0-9]{0,15}$/.test(counter)) {
            return unauthorized();
        }
        if (mac === undefined || !HEX64.test(mac)) return unauthorized();
        if (this.storeDown) return answer("store-unavailable");
        const secret = this.secrets.get(id);
        if (secret === undefined) return answer("unpaired");
        const session = this.sessions.get(id);
        if (
            session === undefined ||
            this.clock() - session.startedAt > this.sessionLife
        ) {
            this.sessions.delete(id);
            return answer("session-expired");
        }
        const number = Number(counter);
        const expected = await requestMac(
            secret,
            session.nonceA,
            number,
            path,
            utf8(text),
            this.port,
        );
        if (expected !== mac) return unauthorized();
        if (number <= session.counter) return answer("replay");
        session.counter = number;
        return "ok";
    }

    private async link(
        path: string,
        text: string,
        headers: Record<string, string>,
    ): Promise<Response> {
        const who = await this.authenticate(path, text, headers);
        if (typeof who !== "string") return who;
        const signed = who === "ok";
        const body = this.parse(text);
        if (body === null) return answer("body-not-an-object");
        this.open += 1;
        this.peakOpen = Math.max(this.peakOpen, this.open);
        try {
            if (path === "/add") {
                if (body.cookies !== undefined && !signed) {
                    return answer("cookies-unsigned");
                }
                await this.beforeAdd?.();
                const override = await this.addReply?.();
                if (override) return override;
                const token = randomHex(16);
                this.tokens.set(token, signed);
                this.added.push({ signed, body });
                const url = String(body.url);
                return answer("add-ok", {
                    token,
                    name: url.split("/").pop() || null,
                    size: this.size,
                    kind: "DIRECT",
                    rangesSupported: true,
                    duplicate: this.duplicate,
                });
            }
            await this.beforeConfirm?.();
            const override = await this.confirmReply?.();
            if (override) return override;
            const token = String(body.token);
            const minted = this.tokens.get(token);
            if (minted === undefined) return answer("unknown-token");
            if (minted && !signed) return answer("unknown-token");
            this.tokens.delete(token);
            this.confirmed.push({ signed, token });
            return answer("confirm-ok", { id: this.nextId++ });
        } finally {
            this.open -= 1;
        }
    }

    /** Every request that reached the app on [path]. */
    on(path: string): Seen[] {
        return this.seen.filter((s) => s.path === path);
    }
}

/** What a program that is not anydm but says it is does: it answers `/ping`, and whatever else it is told to. */
export class Spoofer implements Machine {
    readonly seen: Seen[] = [];

    constructor(
        readonly port: number,
        private readonly says: {
            /** What it answers `/pair` and `/hello` with. */
            pair?: "garbage" | "wrong_code" | "no_code";
            hello?: "garbage" | "unpaired";
            /** Answers `/hello`, `/add` and `/confirm` with a 401 and this error (a program that took the app's port). */
            refuses?: string;
            /** Paths it answers `/ping` and then never answers (the request waits until it is given up on). */
            stalls?: string[];
        } = {},
    ) {}

    respond(url: string, init?: RequestInit): Promise<Response> | Response {
        const u = new URL(url);
        this.seen.push({
            port: this.port,
            path: u.pathname,
            method: init?.method ?? "GET",
            headers: headersOf(init),
            text: typeof init?.body === "string" ? init.body : "",
        });
        if (this.says.stalls?.includes(u.pathname)) {
            return new Promise((_, reject) =>
                init?.signal?.addEventListener("abort", () =>
                    reject(new DOMException("aborted", "AbortError")),
                ),
            );
        }
        if (
            this.says.refuses !== undefined &&
            [
                "/hello",
                "/add",
                "/confirm",
            ].includes(u.pathname)
        ) {
            return error(401, this.says.refuses);
        }
        switch (u.pathname) {
            case "/ping":
                return json(200, contract.ping);
            case "/pair":
                if (this.says.pair === "wrong_code") {
                    return error(401, "wrong_code");
                }
                if (this.says.pair === "no_code") return error(409, "no_code");
                return json(200, {
                    id: randomHex(16),
                    nonceA: randomHex(16),
                    proofA: randomHex(32),
                });
            case "/hello":
                if (this.says.hello === "unpaired") {
                    return error(401, "unpaired");
                }
                return json(200, {
                    nonceA: randomHex(16),
                    proof: randomHex(32),
                });
            case "/add":
                return json(200, {
                    token: randomHex(16),
                    name: "x",
                    size: 1,
                    kind: "DIRECT",
                    rangesSupported: true,
                    duplicate: false,
                });
            case "/confirm":
                return json(200, { id: 1 });
            default:
                return error(404, "not_found");
        }
    }

    /** Whether it was ever sent anything that is a secret of the browser: cookies, or a signature. */
    gotCredentials(): boolean {
        return this.seen.some(
            (s) =>
                s.text.includes("cookies") ||
                Object.keys(s.headers).some((h) => h.startsWith("x-anydm-")),
        );
    }
}
