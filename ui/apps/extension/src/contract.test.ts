import { describe, expect, test } from "bun:test";
import bridge from "../contract/bridge.json";
import http from "../contract/pairing-http.json";
import { addLink } from "./bridge";
import { BridgeClient } from "./client";
import { utf8 } from "./crypto";
import { FakeApp, machineOf } from "./fakeApp";
import {
    helloRequestProof,
    loadPairing,
    pair,
    pairProof,
    requestMac,
    sayHello,
    type Pairing,
    type PairingStorage,
} from "./pairing";

// `contract/pairing-http.json` is what the app's server answers to /pair, /hello and signed /add and /confirm. Its other reader,
// `PairingHttpContractTest.kt`, asks the real server with each case; here each case is put to FakeApp, which the rest of
// these tests use as the app, so the model of the app can't be wrong in a way the real one is not.

const CODE = "7K3M9QX2VBHT4N6R";
const PORT = 18401;

interface Case {
    name: string;
    given: string[];
    request: {
        path: string;
        body: unknown;
        headers?: Record<string, string>;
    };
    answer: string;
}

interface Answer {
    status: number;
    body?: Record<string, unknown>;
    shape?: Record<string, string>;
    headers?: Record<string, string>;
}

const cases = http.cases as Case[];
const answers = http.answers as Record<string, Answer>;

const store = () => {
    const data = new Map<string, unknown>();
    const storage: PairingStorage = {
        get: async (key) =>
            data.has(key)
                ? {
                      [key]: data.get(key),
                  }
                : {},
        set: async (items) => {
            for (const [
                key,
                value,
            ] of Object.entries(items)) {
                data.set(key, value);
            }
        },
        remove: async (key) => {
            data.delete(key);
        },
    };
    return storage;
};

const HEX32 = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;

const TYPES: Record<string, (value: unknown) => boolean> = {
    hex32: (v) => typeof v === "string" && HEX32.test(v),
    hex64: (v) => typeof v === "string" && HEX64.test(v),
    int: (v) => typeof v === "number" && Number.isInteger(v),
    string: (v) => typeof v === "string",
    bool: (v) => typeof v === "boolean",
    "string-or-null": (v) => v === null || typeof v === "string",
    "int-or-null": (v) => v === null || Number.isInteger(v),
};

const plainAdd = { url: "https://files.example.com/a.zip" };

/** The state a case starts from, made through the extension's own code where the app is asked to do something. */
async function arrange(given: string[]) {
    const app = new FakeApp(PORT);
    const fetchFn = machineOf({ [PORT]: app });
    const storage = store();
    let now = 1_000_000;
    app.clock = () => now;
    let pairing: Pairing | null = null;
    let session: { nonceA: string; counter: number } | null = null;
    let token = "";
    let code: string | null = null;

    const ensurePaired = async () => {
        if (pairing) return;
        app.startPairing(CODE);
        const outcome = await pair({ fetch: fetchFn, storage }, CODE);
        expect(outcome).toEqual({ kind: "paired" });
        pairing = await loadPairing(storage);
    };
    const ensureSession = async () => {
        await ensurePaired();
        if (session) return;
        const hello = await sayHello(fetchFn, PORT, pairing!);
        if (hello.kind !== "verified") throw new Error("no session");
        session = { nonceA: hello.nonceA, counter: 0 };
    };

    for (const step of given) {
        switch (step) {
            case "code-waiting":
                app.startPairing(CODE);
                code = CODE;
                break;
            case "paired":
                await ensurePaired();
                break;
            case "session":
                await ensureSession();
                break;
            case "signed-add-done": {
                await ensureSession();
                const sign = async (path: string, text: string) => {
                    session!.counter += 1;
                    return {
                        "X-Anydm-Id": pairing!.id,
                        "X-Anydm-Counter": String(session!.counter),
                        "X-Anydm-Mac": await requestMac(
                            pairing!.secret,
                            session!.nonceA,
                            session!.counter,
                            path,
                            utf8(text),
                            PORT,
                        ),
                    };
                };
                const probed = await addLink(fetchFn, PORT, plainAdd, sign);
                if (probed.kind !== "probed") throw new Error("not added");
                token = probed.token;
                break;
            }
            case "session-ran-out":
                now += 61_000;
                break;
            case "store-unreadable":
                app.storeDown = true;
                break;
            case "save-fails":
                app.saveFailures = 1;
                break;
            case "throttled":
                app.throttleAfter = 0;
                break;
            default:
                throw new Error(
                    `the file has a state this test does not know: ${step}`,
                );
        }
    }
    return {
        app,
        fetchFn,
        pairing: () => pairing,
        session: () => session,
        token: () => token,
        code: () => code,
    };
}

type Arranged = Awaited<ReturnType<typeof arrange>>;

// A request as the file says it, with the placeholders made.
async function made(c: Case, t: Arranged) {
    const path = c.request.path;
    const body = structuredClone(c.request.body) as Record<string, unknown>;
    const headers = { ...c.request.headers };
    if (body !== null && !Array.isArray(body)) {
        for (const [
            key,
            value,
        ] of Object.entries(body)) {
            if (value === "$id") body[key] = t.pairing()!.id;
            else if (value === "$token") body[key] = t.token();
            else if (value === "$proofE") {
                body[key] = await pairProof(
                    t.code() ?? CODE,
                    body.nonceE as string,
                    PORT,
                );
            }
        }
        if (body.proof === "$helloRequestProof") {
            body.proof = await helloRequestProof(
                t.pairing()!.secret,
                body.id as string,
                body.nonceE as string,
                PORT,
            );
        }
    }
    const text = JSON.stringify(body);
    if (headers["X-Anydm-Id"] === "$id")
        headers["X-Anydm-Id"] = t.pairing()!.id;
    if (headers["X-Anydm-Mac"] === "$mac") {
        headers["X-Anydm-Mac"] = await requestMac(
            t.pairing()!.secret,
            t.session()!.nonceA,
            Number(headers["X-Anydm-Counter"]),
            path,
            utf8(text),
            PORT,
        );
    }
    return { path, text, headers };
}

describe("every case of pairing-http.json is answered by FakeApp as the file says", () => {
    for (const c of cases) {
        test(c.name, async () => {
            const t = await arrange(c.given);
            const sent = await made(c, t);

            const response = await t.fetchFn(
                `http://127.0.0.1:${PORT}${sent.path}`,
                {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        ...sent.headers,
                    },
                    body: sent.text,
                },
            );

            const expected = answers[c.answer]!;
            expect(response.status).toBe(expected.status);
            const body = (await response.json()) as Record<string, unknown>;
            expect(Object.keys(body).sort()).toEqual(
                [
                    ...Object.keys(expected.body ?? {}),
                    ...Object.keys(expected.shape ?? {}),
                ].sort(),
            );
            for (const [
                key,
                value,
            ] of Object.entries(expected.body ?? {})) {
                expect(body[key]).toEqual(value);
            }
            for (const [
                key,
                type,
            ] of Object.entries(expected.shape ?? {})) {
                expect(TYPES[type]!(body[key])).toBe(true);
            }
            for (const [
                name,
                type,
            ] of Object.entries(expected.headers ?? {})) {
                const value = response.headers.get(name);
                expect(value).not.toBeNull();
                expect(
                    TYPES[type]!(
                        Number.isNaN(Number(value)) ? value : Number(value),
                    ),
                ).toBe(true);
            }
        });
    }
});

describe("the file itself", () => {
    test("every case names an answer that exists, and every answer is used", () => {
        const used = new Set(cases.map((c) => c.answer));
        for (const name of used) expect(answers).toHaveProperty(name);
        for (const name of Object.keys(answers)) {
            expect(used.has(name)).toBe(true);
        }
    });

    test("every error string the extension acts on is an answer of the file", () => {
        const codes = new Set(
            Object.values(answers).map((a) => a.body?.error as string),
        );
        for (const code of [
            // What a session that ended is answered with (`client.ts` says hello again for these)...
            "session_expired",
            "unauthorized",
            "replay",
            // ...what a browser the app does not know, and a list it can't read, are told...
            "unpaired",
            "store_unavailable",
            // ...and what pairing handles (`pairing.ts`).
            "wrong_code",
            "no_code",
            "too_many_attempts",
            "not_saved",
            "bad_request",
            "unknown_token",
        ]) {
            expect(codes.has(code)).toBe(true);
        }
    });

    test("every case's state is one the file describes", () => {
        for (const c of cases) {
            for (const step of c.given) expect(http.given).toHaveProperty(step);
        }
    });

    test("the answers have no placeholders and no extra fields: what is in body and shape is all there is", () => {
        for (const spec of Object.values(answers)) {
            const keys = [
                ...Object.keys(spec.body ?? {}),
                ...Object.keys(spec.shape ?? {}),
            ];
            expect(new Set(keys).size).toBe(keys.length);
            for (const type of Object.values(spec.shape ?? {})) {
                expect(TYPES).toHaveProperty(type);
            }
        }
    });
});

describe("what the extension sends is what the file says the app reads", () => {
    const requests = http.requests as Record<
        string,
        { bodyFields: string[]; headers: string[] }
    >;

    async function sent() {
        const app = new FakeApp(PORT);
        const fetchFn = machineOf({ [PORT]: app });
        const storage = store();
        app.startPairing(CODE);
        expect(await pair({ fetch: fetchFn, storage }, CODE)).toEqual({
            kind: "paired",
        });
        const client = new BridgeClient({ fetch: fetchFn, storage });
        expect((await client.connect()).kind).toBe("ready");
        const full = bridge.requests[0]!.body;
        const probed = await client.add(PORT, full);
        if (probed.kind !== "probed") throw new Error("not read");
        await client.confirm(PORT, probed.token);
        return app;
    }

    for (const path of [
        "/pair",
        "/hello",
        "/add",
        "/confirm",
    ]) {
        test(`${path}: a JSON POST with the fields and headers of the file`, async () => {
            const app = await sent();
            const request = app.on(path).at(-1)!;
            const spec = requests[path]!;

            expect(request.method).toBe("POST");
            expect(request.headers["content-type"]).toBe("application/json");
            const fields = Object.keys(JSON.parse(request.text));
            if (path === "/add") {
                // A body holds the fields the file lists, and at least the link.
                expect(fields).toContain("url");
                for (const field of fields) {
                    expect(spec.bodyFields).toContain(field);
                }
            } else {
                expect(fields.sort()).toEqual(
                    [
                        ...spec.bodyFields,
                    ].sort(),
                );
            }
            const credentials = Object.keys(request.headers)
                .filter((name) => name.startsWith("x-anydm-"))
                .sort();
            expect(credentials).toEqual(
                spec.headers.map((name) => name.toLowerCase()).sort(),
            );
        });
    }
});
