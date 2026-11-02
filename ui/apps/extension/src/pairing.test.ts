import { describe, expect, spyOn, test } from "bun:test";
import vectors from "../contract/pairing-vectors.json";
import type { Fetch } from "./bridge";
import { sha256Hex, utf8 } from "./crypto";
import { FakeApp, Spoofer, json, machineOf, type Machine } from "./fakeApp";
import {
    PAIRING_KEY,
    appProof,
    deriveSecret,
    forgetPairing,
    helloProof,
    helloRequestProof,
    loadPairing,
    normaliseCode,
    pair,
    pairProof,
    requestMac,
    savePairing,
    sayHello,
    type Pairing,
    type PairingStorage,
} from "./pairing";

// Every expected value here was made by an independent HMAC (Python, see the file's note), and the app's own test
// (`PairingVectorsTest.kt`) reads the same file: a formula changed on one side only fails on the other.

describe("the pairing code", () => {
    for (const { input, canonical } of vectors.codeNormalisation) {
        test(`reads ${JSON.stringify(input)} as ${JSON.stringify(canonical)}`, () => {
            expect(normaliseCode(input)).toBe(canonical);
        });
    }

    test("only the sixteen characters of the alphabet make a code", () => {
        for (const char of vectors.alphabet) {
            expect(normaliseCode(char.repeat(16))).toBe(char.repeat(16));
        }
    });
});

describe("the formulas, against the shared vectors", () => {
    const { pair, hello } = vectors;

    test("proofE", async () => {
        expect(pair.messages.proofE).toBe(
            `anydm-pair-1|${pair.nonceE}|${pair.port}`,
        );
        expect(await pairProof(pair.code, pair.nonceE, pair.port)).toBe(
            pair.proofE,
        );
    });

    test("proofA", async () => {
        expect(
            await appProof(pair.code, pair.nonceE, pair.nonceA, pair.port),
        ).toBe(pair.proofA);
    });

    test("secret has no port in it", async () => {
        expect(await deriveSecret(pair.code, pair.nonceE, pair.nonceA)).toBe(
            pair.secret,
        );
    });

    test("the hello request proof", async () => {
        expect(
            await helloRequestProof(
                hello.secret,
                hello.id,
                hello.nonceE,
                hello.port,
            ),
        ).toBe(hello.requestProof);
    });

    test("the hello reply proof", async () => {
        expect(
            await helloProof(
                hello.secret,
                hello.nonceE,
                hello.nonceA,
                hello.port,
            ),
        ).toBe(hello.proof);
    });

    for (const request of vectors.requests) {
        test(`the MAC of "${request.name}" and the hash of its body`, async () => {
            expect(await sha256Hex(utf8(request.body))).toBe(
                request.bodySha256,
            );
            expect(
                await requestMac(
                    hello.secret,
                    request.nonceA,
                    request.counter,
                    request.path,
                    utf8(request.body),
                    request.port,
                ),
            ).toBe(request.mac);
        });
    }

    test("another port changes every proof but the secret", async () => {
        const other = vectors.otherPort;

        expect(await pairProof(pair.code, pair.nonceE, other.port)).toBe(
            other.proofE,
        );
        expect(
            await appProof(pair.code, pair.nonceE, pair.nonceA, other.port),
        ).toBe(other.proofA);
        expect(await deriveSecret(pair.code, pair.nonceE, pair.nonceA)).toBe(
            other.secret,
        );
        expect(
            await helloRequestProof(
                hello.secret,
                hello.id,
                hello.nonceE,
                other.port,
            ),
        ).toBe(other.helloRequestProof);
        expect(
            await helloProof(
                hello.secret,
                hello.nonceE,
                hello.nonceA,
                other.port,
            ),
        ).toBe(other.helloProof);
        const first = vectors.requests[0]!;
        expect(
            await requestMac(
                hello.secret,
                first.nonceA,
                first.counter,
                first.path,
                utf8(first.body),
                other.port,
            ),
        ).toBe(other.mac);
    });

    test("a port outside 1..65535 or a bad counter or path is refused, not signed", async () => {
        const first = vectors.requests[0]!;
        const sign = (port: number, counter = 1, path = "/add") =>
            requestMac(
                hello.secret,
                first.nonceA,
                counter,
                path,
                utf8("{}"),
                port,
            );

        for (const port of [
            0,
            -1,
            65536,
            1.5,
            Number.NaN,
        ]) {
            await expect(sign(port)).rejects.toThrow();
            await expect(
                pairProof(pair.code, pair.nonceE, port),
            ).rejects.toThrow();
        }
        for (const counter of [
            0,
            -1,
            1.5,
            2 ** 53,
            Number.NaN,
        ]) {
            await expect(sign(18403, counter)).rejects.toThrow();
        }
        await expect(sign(18403, 1, "/ping")).rejects.toThrow();
        await expect(sign(18403, 1, "/add?x=1")).rejects.toThrow();
    });
});

// ---- pairing and saying hello, against a model of the app ----

const CODE = "7K3M9QX2VBHT4N6R";
const store = () => {
    const data = new Map<string, unknown>();
    const storage: PairingStorage & { data: Map<string, unknown> } = {
        data,
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

function setup(
    options: {
        apps?: Record<number, Machine>;
        random?: (bytes: number) => string;
    } = {},
) {
    const app = new FakeApp(18401);
    const apps = options.apps ?? { 18401: app };
    const storage = store();
    const calls: string[] = [];
    const fetchFn = machineOf(apps);
    const deps = {
        fetch: (async (url, init) => {
            calls.push(new URL(url).pathname);
            return fetchFn(url, init);
        }) as Fetch,
        storage,
        random: options.random,
    };
    return { app, apps, storage, deps, calls };
}

describe("pair", () => {
    test("pairs with the app that has the code, and keeps its id and the secret both sides worked out", async () => {
        const t = setup();
        t.app.startPairing(CODE);

        const outcome = await pair(t.deps, CODE);

        expect(outcome).toEqual({ kind: "paired" });
        const kept = await loadPairing(t.storage);
        expect(kept).toEqual({ id: t.app.id, secret: t.app.secret });
        expect(t.app.code).toBeNull();
    });

    test("sends the nonce and the proof, and not the code", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        await pair(t.deps, CODE);

        const sent = t.app.on("/pair");
        expect(sent).toHaveLength(1);
        expect(Object.keys(JSON.parse(sent[0]!.text)).sort()).toEqual([
            "nonceE",
            "proofE",
        ]);
        expect(JSON.stringify(t.app.seen)).not.toContain(CODE);
        expect(sent[0]!.headers["content-type"]).toBe("application/json");
    });

    test("works out the vector's secret from the vector's answer", async () => {
        const v = vectors.pair;
        const answer: Machine = {
            respond: async (url, init) => {
                if (new URL(url).pathname === "/ping") {
                    return json(200, { app: "anydm" });
                }
                expect(JSON.parse(init!.body as string)).toEqual({
                    nonceE: v.nonceE,
                    proofE: v.proofE,
                });
                return json(200, {
                    id: v.id,
                    nonceA: v.nonceA,
                    proofA: v.proofA,
                });
            },
        };
        const t = setup({
            apps: { [v.port]: answer },
            random: () => v.nonceE,
        });

        expect(await pair(t.deps, v.code)).toEqual({ kind: "paired" });
        expect(await loadPairing(t.storage)).toEqual({
            id: v.id,
            secret: v.secret,
        });
    });

    test("reads the code the way the app shows it: groups, spaces, lower case", async () => {
        for (const typed of [
            "7K3M-9QX2-VBHT-4N6R",
            "7k3m 9qx2 vbht 4n6r",
            " 7K3M9QX2VBHT4N6R\n",
        ]) {
            const t = setup();
            t.app.startPairing(CODE);

            expect(await pair(t.deps, typed)).toEqual({ kind: "paired" });
        }
    });

    test("something that is not a code sends nothing at all", async () => {
        const t = setup();
        t.app.startPairing(CODE);

        for (const typed of [
            "",
            "7K3M",
            "7K3M9QX2VBHT4N6U",
            "ıK3M9QX2VBHT4N6R",
        ]) {
            expect(await pair(t.deps, typed)).toEqual({ kind: "bad-code" });
        }

        expect(t.calls).toEqual([]);
        expect(t.storage.data.size).toBe(0);
    });

    test("a wrong code is refused by the app, counts as a try there, and keeps nothing", async () => {
        const t = setup();
        t.app.startPairing(CODE);

        expect(await pair(t.deps, "0000000000000000")).toEqual({
            kind: "wrong-code",
        });

        expect(t.app.wrongTries).toBe(1);
        expect(t.app.code).toBe(CODE);
        expect(t.storage.data.size).toBe(0);
    });

    test("an app that is not waiting for a code says so", async () => {
        const t = setup();

        expect(await pair(t.deps, CODE)).toEqual({ kind: "no-code" });
    });

    test("too many tries is reported with the wait", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        t.app.throttleAfter = 0;

        expect(await pair(t.deps, CODE)).toEqual({
            kind: "throttled",
            retryAfterSeconds: 30,
        });
        expect(t.storage.data.size).toBe(0);
    });

    test("an app that can't save is tried once more, and the code is still good", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        t.app.saveFailures = 1;

        expect(await pair(t.deps, CODE)).toEqual({ kind: "paired" });
        expect(t.app.on("/pair")).toHaveLength(2);
    });

    test("an app that keeps failing to save is reported, nothing is kept, and the code still works", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        t.app.saveFailures = 2;

        expect(await pair(t.deps, CODE)).toEqual({ kind: "not-saved" });
        expect(t.storage.data.size).toBe(0);
        expect(t.app.code).toBe(CODE);

        expect(await pair(t.deps, CODE)).toEqual({ kind: "paired" });
    });

    test("a program that is not the app can't be paired with: it can't prove it, and nothing is kept", async () => {
        const spoofer = new Spoofer(18400, { pair: "garbage" });
        const t = setup({ apps: { 18400: spoofer } });

        expect(await pair(t.deps, CODE)).toEqual({ kind: "wrong-code" });
        expect(t.storage.data.size).toBe(0);
        expect(spoofer.seen.filter((s) => s.path === "/pair")).toHaveLength(1);
    });

    test("when a program in front fails its proof, the next port that answers is tried", async () => {
        const spoofer = new Spoofer(18400, { pair: "garbage" });
        const app = new FakeApp(18403);
        const t = setup({ apps: { 18400: spoofer, 18403: app } });
        app.startPairing(CODE);

        expect(await pair(t.deps, CODE)).toEqual({ kind: "paired" });
        expect(await loadPairing(t.storage)).toEqual({
            id: app.id,
            secret: app.secret,
        });
    });

    test("a program that says wrong code does not stop the next port either", async () => {
        const spoofer = new Spoofer(18400, { pair: "wrong_code" });
        const app = new FakeApp(18401);
        const t = setup({ apps: { 18400: spoofer, 18401: app } });
        app.startPairing(CODE);

        expect(await pair(t.deps, CODE)).toEqual({ kind: "paired" });
    });

    test("the most useful answer is the one reported when no port pairs", async () => {
        const spoofer = new Spoofer(18400, { pair: "no_code" });
        const app = new FakeApp(18401);
        const t = setup({ apps: { 18400: spoofer, 18401: app } });
        app.startPairing(CODE);

        expect(await pair(t.deps, "0000000000000000")).toEqual({
            kind: "wrong-code",
        });
    });

    // Neither refusal carries a proof (the app has nothing to prove it with before the browser is paired), so which one a
    // person is shown is a hint a program in front can pick. It can't pair the browser, and it never has the code. Documented
    // in the README's known limits.
    test("a program in front can choose which refusal is shown, and gets nothing but the proof", async () => {
        const spoofer = new Spoofer(18400, { pair: "wrong_code" });
        const app = new FakeApp(18401);
        const t = setup({ apps: { 18400: spoofer, 18401: app } });

        expect(await pair(t.deps, CODE)).toEqual({ kind: "wrong-code" });
        expect(spoofer.seen.map((s) => s.text).join()).not.toContain(CODE);
        expect(t.storage.data.size).toBe(0);
        expect(app.secrets.size).toBe(0);
    });

    test("a relay on another port can't get a pairing through: the proofs are for the port dialed", async () => {
        const app = new FakeApp(18403);
        app.startPairing(CODE);
        const relay: Machine = {
            respond: (url, init) =>
                app.respond(url.replace(":18402", ":18403"), init),
        };
        const t = setup({ apps: { 18402: relay } });

        expect(await pair(t.deps, CODE)).toEqual({ kind: "wrong-code" });
        expect(t.storage.data.size).toBe(0);
        expect(app.secrets.size).toBe(0);
    });

    test("an answer made for another port is not accepted", async () => {
        const v = vectors.pair;
        const other = vectors.otherPort;
        const answer: Machine = {
            respond: async (url) =>
                new URL(url).pathname === "/ping"
                    ? json(200, { app: "anydm" })
                    : json(200, {
                          id: v.id,
                          nonceA: v.nonceA,
                          // Right for the port the app has, but this was dialed on another one.
                          proofA: other.proofA,
                      }),
        };
        const t = setup({
            apps: { [v.port]: answer },
            random: () => v.nonceE,
        });

        expect(await pair(t.deps, v.code)).toEqual({ kind: "wrong-code" });
        expect(t.storage.data.size).toBe(0);
    });

    test("an answer that is not the shape of one is not accepted", async () => {
        const v = vectors.pair;
        for (const bad of [
            { id: "x", nonceA: v.nonceA, proofA: v.proofA },
            { id: v.id, nonceA: v.nonceA.toUpperCase(), proofA: v.proofA },
            { id: v.id, nonceA: v.nonceA },
            "text",
        ]) {
            const answer: Machine = {
                respond: async (url) =>
                    new URL(url).pathname === "/ping"
                        ? json(200, { app: "anydm" })
                        : json(200, bad),
            };
            const t = setup({
                apps: { [v.port]: answer },
                random: () => v.nonceE,
            });

            expect(await pair(t.deps, v.code)).toEqual({ kind: "wrong-code" });
            expect(t.storage.data.size).toBe(0);
        }
    });

    test("anydm that does not answer this browser is told apart", async () => {
        const t = setup();
        t.app.allowed = false;

        expect(await pair(t.deps, CODE)).toEqual({ kind: "forbidden" });
    });

    test("nothing there is reported as nothing there", async () => {
        const t = setup({ apps: {} });

        expect(await pair(t.deps, CODE)).toEqual({ kind: "none" });
    });

    test("an app that can't read its paired browsers is a transient answer", async () => {
        const answer: Machine = {
            respond: async (url) =>
                new URL(url).pathname === "/ping"
                    ? json(200, { app: "anydm" })
                    : json(503, { error: "store_unavailable", message: "" }),
        };
        const t = setup({ apps: { 18401: answer } });

        expect(await pair(t.deps, CODE)).toEqual({ kind: "unavailable" });
    });

    test("a refusal to save on this side is reported, not thrown: the app took the code, so a new one is needed", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        t.deps.storage.set = async () => {
            throw new Error("quota");
        };

        expect(await pair(t.deps, CODE)).toEqual({ kind: "not-kept" });
        // The app has a pairing nobody holds the secret of, and the code is spent.
        expect(t.app.secrets.size).toBe(1);
        expect(t.app.code).toBeNull();
        expect(t.storage.data.size).toBe(0);
    });

    test("a pairing the app made and this browser could not keep ends the search: no other port is tried with the spent code", async () => {
        const other = new FakeApp(18405);
        const t = setup({ apps: { 18401: new FakeApp(18401), 18405: other } });
        const first = t.apps[18401] as FakeApp;
        first.startPairing(CODE);
        other.startPairing(CODE);
        t.deps.storage.set = async () => {
            throw new Error("quota");
        };

        expect(await pair(t.deps, CODE)).toEqual({ kind: "not-kept" });
        expect(other.on("/pair")).toEqual([]);
    });

    test("pairing again replaces the old pairing", async () => {
        const t = setup();
        t.app.startPairing(CODE);
        await pair(t.deps, CODE);
        const first = await loadPairing(t.storage);
        t.app.startPairing(CODE);
        await pair(t.deps, CODE);

        expect((await loadPairing(t.storage))!.id).not.toBe(first!.id);
    });

    test("nonceE is sixteen fresh random bytes every time", async () => {
        const t = setup();
        const sizes: number[] = [];
        const real = crypto.getRandomValues.bind(crypto);
        const spy = spyOn(crypto, "getRandomValues").mockImplementation(((
            array: Uint8Array,
        ) => {
            sizes.push(array.length);
            return real(array);
        }) as typeof crypto.getRandomValues);
        t.app.startPairing(CODE);
        await pair(t.deps, "0000000000000000");
        await pair(t.deps, "0000000000000000");
        spy.mockRestore();

        const nonces = t.app.on("/pair").map((s) => JSON.parse(s.text).nonceE);
        expect(nonces).toHaveLength(2);
        expect(nonces[0]).not.toBe(nonces[1]);
        expect(nonces[0]).toMatch(/^[0-9a-f]{32}$/);
        expect(sizes).toEqual([
            16,
            16,
        ]);
    });
});

describe("what is stored", () => {
    test("loads only an id and a secret of the right shape", async () => {
        const storage = store();
        const good = { id: "a".repeat(32), secret: "b".repeat(64) };

        await savePairing(storage, good);
        expect(await loadPairing(storage)).toEqual(good);

        for (const bad of [
            null,
            "text",
            { id: "a", secret: "b".repeat(64) },
            { id: "a".repeat(32), secret: "B".repeat(64) },
            { id: "a".repeat(32) },
            { id: 1, secret: 2 },
        ]) {
            storage.data.set(PAIRING_KEY, bad);
            expect(await loadPairing(storage)).toBeNull();
        }
    });

    test("a store that fails to read is no pairing", async () => {
        const storage = store();
        storage.get = async () => {
            throw new Error("no storage");
        };

        expect(await loadPairing(storage)).toBeNull();
    });

    test("forgetting removes it", async () => {
        const storage = store();
        await savePairing(storage, {
            id: "a".repeat(32),
            secret: "b".repeat(64),
        });

        await forgetPairing(storage);

        expect(await loadPairing(storage)).toBeNull();
        expect(storage.data.size).toBe(0);
    });

    test("only a copy of the id and the secret is saved, nothing else", async () => {
        const storage = store();
        await savePairing(storage, {
            id: "a".repeat(32),
            secret: "b".repeat(64),
            extra: "x",
        } as Pairing);

        expect(storage.data.get(PAIRING_KEY)).toEqual({
            id: "a".repeat(32),
            secret: "b".repeat(64),
        });
    });
});

describe("hello", () => {
    const v = vectors.hello;
    const pairing: Pairing = { id: v.id, secret: v.secret };

    test("the vector's request goes out and the vector's reply is verified", async () => {
        const answer: Machine = {
            respond: async (url, init) => {
                expect(JSON.parse(init!.body as string)).toEqual({
                    id: v.id,
                    nonceE: v.nonceE,
                    proof: v.requestProof,
                });
                expect(new URL(url).port).toBe(String(v.port));
                return json(200, { nonceA: v.nonceA, proof: v.proof });
            },
        };

        expect(
            await sayHello(
                machineOf({ [v.port]: answer }),
                v.port,
                pairing,
                () => v.nonceE,
            ),
        ).toEqual({ kind: "verified", nonceA: v.nonceA });
    });

    test("a program that has no secret can't be verified, and is reported without its proof", async () => {
        const spoofer = new Spoofer(18400, { hello: "garbage" });

        expect(
            await sayHello(machineOf({ 18400: spoofer }), 18400, pairing),
        ).toEqual({ kind: "no-proof" });
    });

    test("a proof made for another port is not a proof", async () => {
        const other = vectors.otherPort;
        const answer: Machine = {
            respond: () =>
                json(200, { nonceA: v.nonceA, proof: other.helloProof }),
        };

        expect(
            await sayHello(
                machineOf({ [v.port]: answer }),
                v.port,
                pairing,
                () => v.nonceE,
            ),
        ).toEqual({ kind: "no-proof" });
    });

    test("an answer that is not the shape of one is not a proof", async () => {
        for (const bad of [
            { nonceA: "zz", proof: v.proof },
            { nonceA: v.nonceA },
            { proof: v.proof },
            [],
        ]) {
            const answer: Machine = { respond: () => json(200, bad) };

            expect(
                await sayHello(
                    machineOf({ [v.port]: answer }),
                    v.port,
                    pairing,
                    () => v.nonceE,
                ),
            ).toEqual({ kind: "no-proof" });
        }
    });

    test("the app that knows the secret is verified, with a session of its own", async () => {
        const app = new FakeApp(18401);
        app.secrets.set(v.id, v.secret);

        const outcome = await sayHello(
            machineOf({ 18401: app }),
            18401,
            pairing,
        );

        expect(outcome.kind).toBe("verified");
        expect(app.sessions.get(v.id)!.nonceA).toBe(
            (outcome as { nonceA: string }).nonceA,
        );
    });

    test("an app that does not know the id says unpaired", async () => {
        const app = new FakeApp(18401);

        expect(
            await sayHello(machineOf({ 18401: app }), 18401, pairing),
        ).toEqual({
            kind: "unpaired",
        });
    });

    test("an app that has another secret under the id says unpaired", async () => {
        const app = new FakeApp(18401);
        app.secrets.set(v.id, "c".repeat(64));

        expect(
            await sayHello(machineOf({ 18401: app }), 18401, pairing),
        ).toEqual({
            kind: "unpaired",
        });
    });

    test("an app that can't read its paired browsers is a transient answer", async () => {
        const app = new FakeApp(18401);
        app.storeDown = true;

        expect(
            await sayHello(machineOf({ 18401: app }), 18401, pairing),
        ).toEqual({
            kind: "unavailable",
        });
    });

    test("nothing there is unreachable, and anything else is a plain failure", async () => {
        expect(await sayHello(machineOf({}), 18401, pairing)).toEqual({
            kind: "unreachable",
        });
        const odd: Machine = { respond: () => json(500, { error: "boom" }) };
        expect(
            await sayHello(machineOf({ 18401: odd }), 18401, pairing),
        ).toEqual({
            kind: "failed",
        });
    });

    test("nonceE is sixteen fresh random bytes each hello", async () => {
        const app = new FakeApp(18401);
        app.secrets.set(v.id, v.secret);
        const fetchFn = machineOf({ 18401: app });
        await sayHello(fetchFn, 18401, pairing);
        await sayHello(fetchFn, 18401, pairing);

        const nonces = app.on("/hello").map((s) => JSON.parse(s.text).nonceE);
        expect(nonces[0]).not.toBe(nonces[1]);
        expect(nonces[0]).toMatch(/^[0-9a-f]{32}$/);
    });

    test("a replayed hello is answered with a fresh nonceA, and cuts the live session", async () => {
        const app = new FakeApp(18401);
        app.secrets.set(v.id, v.secret);
        const fetchFn = machineOf({ 18401: app });
        const first = await sayHello(fetchFn, 18401, pairing, () => v.nonceE);
        const second = await sayHello(fetchFn, 18401, pairing, () => v.nonceE);

        expect(first.kind).toBe("verified");
        expect(second.kind).toBe("verified");
        expect((first as { nonceA: string }).nonceA).not.toBe(
            (second as { nonceA: string }).nonceA,
        );
        expect(app.sessions.get(v.id)!.nonceA).toBe(
            (second as { nonceA: string }).nonceA,
        );
    });
});

describe("secrets stay out of sight", () => {
    test("pairing and saying hello log nothing, and no outcome holds the code, the secret or a proof", async () => {
        const log = spyOn(console, "log");
        const info = spyOn(console, "info");
        const warn = spyOn(console, "warn");
        const err = spyOn(console, "error");
        const debug = spyOn(console, "debug");
        const t = setup();
        t.app.startPairing(CODE);

        const paired = await pair(t.deps, CODE);
        const kept = await loadPairing(t.storage);
        const verified = await sayHello(t.deps.fetch, 18401, kept!);
        const wrong = await pair(t.deps, "0000000000000000");
        const spoofed = await sayHello(
            machineOf({ 18400: new Spoofer(18400, { hello: "garbage" }) }),
            18400,
            kept!,
        );

        for (const spy of [
            log,
            info,
            warn,
            err,
            debug,
        ]) {
            expect(spy).not.toHaveBeenCalled();
            spy.mockRestore();
        }
        const shown = JSON.stringify([
            paired,
            verified.kind,
            wrong,
            spoofed,
        ]);
        expect(shown).not.toContain(CODE);
        expect(shown).not.toContain(kept!.secret);
        expect(shown).not.toMatch(/[0-9a-f]{64}/);
    });
});
