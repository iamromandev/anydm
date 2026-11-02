import { describe, expect, spyOn, test } from "bun:test";
import contract from "../contract/bridge.json";
import vectors from "../contract/pairing-vectors.json";
import type { Fetch } from "./bridge";
import { BridgeClient } from "./client";
import { FakeApp, Spoofer, json, machineOf, type Machine } from "./fakeApp";
import {
    forgetPairing,
    loadPairing,
    pair,
    savePairing,
    type PairingStorage,
} from "./pairing";

const CODE = "7K3M9QX2VBHT4N6R";
const body = contract.requests[0]!.body;
const settle = () => new Promise((resolve) => setTimeout(resolve, 5));

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

// A browser that has paired with an app on 18401, and whatever else is on the other ports.
async function setup(
    options: {
        extra?: Record<number, Machine>;
        paired?: boolean;
        random?: (bytes: number) => string;
        clock?: () => { mono: number; wall: number };
        /** The port the app is on (18401 unless said). */
        port?: number;
    } = {},
) {
    const port = options.port ?? 18401;
    const app = new FakeApp(port);
    const storage = store();
    const machines: Record<number, Machine> = {
        ...options.extra,
        [port]: app,
    };
    const fetchFn = machineOf(machines);
    if (options.paired !== false) {
        app.startPairing(CODE);
        const outcome = await pair({ fetch: fetchFn, storage }, CODE);
        expect(outcome).toEqual({ kind: "paired" });
        // What the test looks at is what happens after pairing.
        for (const machine of Object.values(machines)) {
            (machine as { seen?: unknown[] }).seen?.splice(0);
        }
    }
    const client = new BridgeClient({
        fetch: fetchFn,
        storage,
        random: options.random,
        clock: options.clock,
    });
    return { app, storage, machines, client, fetchFn };
}

// A clock the test moves by hand: the monotonic reading and the wall clock, which both start at a made-up time.
function handClock() {
    const time = { mono: 5_000, wall: 1_700_000_000_000 };
    return {
        read: () => ({ ...time }),
        /** Time passes for both clocks. */
        pass(ms: number) {
            time.mono += ms;
            time.wall += ms;
        },
        time,
    };
}

const headerOf = (seen: { headers: Record<string, string> }, name: string) =>
    seen.headers[name.toLowerCase()];

describe("connect", () => {
    test("a browser that is not paired is ready, not verified, and says no hello", async () => {
        const t = await setup({ paired: false });

        expect(await t.client.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: false,
        });
        expect(t.app.on("/hello")).toEqual([]);
    });

    test("a paired browser is verified once the app proves it knows the secret", async () => {
        const t = await setup();

        expect(await t.client.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: true,
        });
        expect(t.app.on("/hello")).toHaveLength(1);
    });

    test("nothing answering, and anydm not answering this browser, are told apart", async () => {
        const client = new BridgeClient({
            fetch: machineOf({}),
            storage: store(),
        });
        expect(await client.connect()).toEqual({ kind: "none" });

        const t = await setup();
        t.app.allowed = false;
        expect(await t.client.connect()).toEqual({ kind: "forbidden" });
    });

    test("a program in front that can't prove itself is skipped, and gets nothing but the hello", async () => {
        const spoofer = new Spoofer(18400, { hello: "garbage" });
        const t = await setup({ extra: { 18400: spoofer } });

        expect(await t.client.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: true,
        });
        expect(spoofer.seen.map((s) => s.path).sort()).toEqual([
            "/hello",
            "/ping",
        ]);
        expect(spoofer.gotCredentials()).toBe(false);
    });

    test("when only a program that can't prove itself answers, nothing is verified", async () => {
        const spoofer = new Spoofer(18400, { hello: "garbage" });
        const t = await setup({ extra: { 18400: spoofer } });
        delete t.machines[18401];

        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "no-proof",
        });
    });

    test("an app that does not know this browser is reported, and the secret is kept", async () => {
        const t = await setup();
        const before = await loadPairing(t.storage);
        t.app.revoke(t.app.id);

        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "unpaired",
        });
        expect(await loadPairing(t.storage)).toEqual(before);
    });

    test("a program that says 'unpaired' does not make the extension forget its pairing", async () => {
        const spoofer = new Spoofer(18400, { hello: "unpaired" });
        const t = await setup({ extra: { 18400: spoofer } });
        delete t.machines[18401];
        const before = await loadPairing(t.storage);

        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "unpaired",
        });
        expect(await loadPairing(t.storage)).toEqual(before);
    });

    test("an app that can't read its paired browsers is transient, and the secret is kept", async () => {
        const t = await setup();
        const before = await loadPairing(t.storage);
        t.app.storeDown = true;

        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "unavailable",
        });
        expect(await loadPairing(t.storage)).toEqual(before);

        t.app.storeDown = false;
        expect((await t.client.connect()).kind).toBe("ready");
    });

    test("the most useful reason is reported: transient, then unpaired, then no proof", async () => {
        const garbage = new Spoofer(18400, { hello: "garbage" });
        const t = await setup({ extra: { 18400: garbage } });
        t.app.revoke(t.app.id);

        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "unpaired",
        });
        t.app.storeDown = true;
        expect(await t.client.connect()).toEqual({
            kind: "unverified",
            reason: "unavailable",
        });
    });

    test("when several ports prove themselves the port that worked last is the one used", async () => {
        const second = new FakeApp(18405);
        const t = await setup({ extra: { 18405: second } });
        second.secrets.set(t.app.id, t.app.secret);

        expect(await t.client.connect(18405)).toEqual({
            kind: "ready",
            port: 18405,
            verified: true,
        });
        // The session is the one with that port's app: a request to it is signed for it and it takes it.
        expect((await t.client.add(18405, body)).kind).toBe("probed");
        expect(second.added).toHaveLength(1);
        expect(second.added[0]!.signed).toBe(true);
        expect(t.app.added).toEqual([]);
        // Without a preference, the lowest port.
        expect(await t.client.connect()).toMatchObject({ port: 18401 });
    });

    test("a pairing that was forgotten is no pairing", async () => {
        const t = await setup();
        await forgetPairing(t.storage);

        expect(await t.client.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: false,
        });
    });
});

describe("signed requests", () => {
    test("each carries the id, a counter that goes up from 1, and the MAC the app accepts", async () => {
        const t = await setup();
        const link = await t.client.connect();
        expect(link.kind).toBe("ready");

        const probed = await t.client.add(18401, body);
        expect(probed.kind).toBe("probed");
        const token = (probed as { token: string }).token;
        expect(await t.client.confirm(18401, token)).toEqual({
            kind: "added",
            id: 1,
        });

        const sent = [
            ...t.app.on("/add"),
            ...t.app.on("/confirm"),
        ];
        expect(sent.map((s) => headerOf(s, "X-Anydm-Id"))).toEqual([
            t.app.id,
            t.app.id,
        ]);
        expect(sent.map((s) => headerOf(s, "X-Anydm-Counter"))).toEqual([
            "1",
            "2",
        ]);
        expect(
            sent.every((s) =>
                /^[0-9a-f]{64}$/.test(headerOf(s, "X-Anydm-Mac")!),
            ),
        ).toBe(true);
        expect(t.app.added[0]!.signed).toBe(true);
        expect(t.app.confirmed[0]!.signed).toBe(true);
    });

    test("the MACs are the shared vectors' for the same session, bodies and counters", async () => {
        const v = vectors.hello;
        const [
            add,
            confirm,
            nonAscii,
        ] = vectors.requests;
        const seen: Record<string, string>[] = [];
        const app: Machine = {
            respond: async (url, init) => {
                const path = new URL(url).pathname;
                if (path === "/ping") return json(200, contract.ping);
                if (path === "/hello") {
                    return json(200, { nonceA: v.nonceA, proof: v.proof });
                }
                seen.push(init!.headers as Record<string, string>);
                return json(
                    200,
                    path === "/add"
                        ? contract.replies[0]!.body
                        : contract.replies[6]!.body,
                );
            },
        };
        const storage = store();
        await savePairing(storage, { id: v.id, secret: v.secret });
        const client = new BridgeClient({
            fetch: machineOf({ [v.port]: app }),
            storage,
            random: () => v.nonceE,
        });

        expect(await client.connect()).toMatchObject({ verified: true });
        await client.add(v.port, JSON.parse(add!.body));
        await client.confirm(v.port, JSON.parse(confirm!.body).token);
        await client.add(v.port, JSON.parse(nonAscii!.body));

        expect(seen.map((h) => h["X-Anydm-Mac"])).toEqual([
            add!.mac,
            confirm!.mac,
            nonAscii!.mac,
        ]);
        expect(seen.map((h) => h["X-Anydm-Counter"])).toEqual([
            "1",
            "2",
            "3",
        ]);
    });

    test("requests are sent one at a time, in order, with the counter going up", async () => {
        const t = await setup();
        await t.client.connect();
        let release: () => void = () => {};
        const held = new Promise<void>((resolve) => {
            release = resolve;
        });
        let first = true;
        t.app.beforeAdd = async () => {
            if (first) {
                first = false;
                await held;
            }
        };

        const a = t.client.add(18401, body);
        const b = t.client.add(18401, body);
        const c = t.client.add(18401, body);
        await settle();

        expect(t.app.on("/add")).toHaveLength(1);

        release();
        await Promise.all([
            a,
            b,
            c,
        ]);

        expect(t.app.peakOpen).toBe(1);
        expect(
            t.app.on("/add").map((s) => headerOf(s, "X-Anydm-Counter")),
        ).toEqual([
            "1",
            "2",
            "3",
        ]);
    });

    test("a request that finds no session says hello first", async () => {
        const t = await setup();

        expect((await t.client.add(18401, body)).kind).toBe("probed");

        expect(t.app.on("/hello")).toHaveLength(1);
        expect(t.app.added[0]!.signed).toBe(true);
    });

    test("a session that ran out is renewed with one hello, and the request goes again", async () => {
        const t = await setup();
        let now = 1_000_000;
        t.app.clock = () => now;
        await t.client.connect();
        now += 61_000;

        const outcome = await t.client.add(18401, body);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(2);
        expect(t.app.on("/add")).toHaveLength(2);
        expect(headerOf(t.app.on("/add")[1]!, "X-Anydm-Counter")).toBe("1");
    });

    test("a session another hello cut is renewed once", async () => {
        const t = await setup();
        await t.client.connect();
        // Another hello made a new session: the app answers a MAC for the old one as it answers a forgery.
        t.app.sessions.set(t.app.id, {
            nonceA: "f".repeat(32),
            counter: 0,
            startedAt: Date.now(),
        });

        const outcome = await t.client.add(18401, body);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(2);
    });

    test("a counter the app has already passed is renewed once", async () => {
        const t = await setup();
        await t.client.connect();
        t.app.sessions.get(t.app.id)!.counter = 50;

        const outcome = await t.client.add(18401, body);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(2);
        expect(t.app.on("/add")).toHaveLength(2);
    });

    test("a refusal that keeps coming is tried once more and then given up, never looped", async () => {
        for (const code of [
            "session_expired",
            "unauthorized",
            "replay",
        ]) {
            const t = await setup();
            await t.client.connect();
            t.app.addReply = () => json(401, { error: code, message: "" });

            const outcome = await t.client.add(18401, body);

            expect(outcome).toMatchObject({
                kind: "refused",
                status: 401,
                error: code,
            });
            expect(t.app.on("/add")).toHaveLength(2);
            expect(t.app.on("/hello")).toHaveLength(2);
        }
    });

    test("a confirm is renewed the same way", async () => {
        const t = await setup();
        await t.client.connect();
        const probed = (await t.client.add(18401, body)) as { token: string };
        t.app.sessions.set(t.app.id, {
            nonceA: "e".repeat(32),
            counter: 0,
            startedAt: Date.now(),
        });

        const outcome = await t.client.confirm(18401, probed.token);

        expect(outcome.kind).toBe("added");
        expect(t.app.on("/hello")).toHaveLength(2);
    });

    test("'unpaired' mid-session is reported and not retried: the secret is kept", async () => {
        const t = await setup();
        await t.client.connect();
        const before = await loadPairing(t.storage);
        t.app.revoke(t.app.id);

        const outcome = await t.client.add(18401, body);

        expect(outcome).toMatchObject({
            kind: "refused",
            status: 401,
            error: "unpaired",
        });
        expect(t.app.on("/hello")).toHaveLength(1);
        expect(t.app.on("/add")).toHaveLength(1);
        expect(await loadPairing(t.storage)).toEqual(before);
    });

    test("'store unavailable' is reported as transient and not retried", async () => {
        const t = await setup();
        await t.client.connect();
        const before = await loadPairing(t.storage);
        t.app.storeDown = true;

        const outcome = await t.client.add(18401, body);

        expect(outcome).toMatchObject({
            kind: "refused",
            status: 503,
            error: "store_unavailable",
        });
        expect(t.app.on("/add")).toHaveLength(1);
        expect(await loadPairing(t.storage)).toEqual(before);
    });

    test("a hello that is refused for a request is reported, and the request is not sent", async () => {
        const t = await setup();
        t.app.storeDown = true;

        const outcome = await t.client.add(18401, body);

        expect(outcome).toMatchObject({
            kind: "refused",
            status: 503,
            error: "store_unavailable",
        });
        expect(t.app.on("/add")).toEqual([]);
    });

    test("when the hello can't be verified the request, and its cookies, are never sent", async () => {
        const spoofer = new Spoofer(18400, { hello: "garbage" });
        const t = await setup({ extra: { 18400: spoofer } });
        delete t.machines[18401];

        const outcome = await t.client.add(18400, {
            ...body,
            cookies: "sid=secret",
        });
        const confirmed = await t.client.confirm(18400, "tok");

        expect(outcome).toMatchObject({ kind: "refused", error: "unverified" });
        expect(confirmed).toMatchObject({
            kind: "refused",
            error: "unverified",
        });
        expect(spoofer.gotCredentials()).toBe(false);
        expect(spoofer.seen.map((s) => s.path)).not.toContain("/add");
        expect(spoofer.seen.map((s) => s.path)).not.toContain("/confirm");
    });

    test("a failing request does not stop the ones queued after it", async () => {
        const t = await setup();
        await t.client.connect();
        let first = true;
        const real = t.fetchFn;
        const flaky: Fetch = async (url, init) => {
            if (first && new URL(url).pathname === "/add") {
                first = false;
                throw new TypeError("reset");
            }
            return real(url, init);
        };
        const client = new BridgeClient({ fetch: flaky, storage: t.storage });
        await client.connect();

        const a = await client.add(18401, body);
        const b = await client.add(18401, body);

        expect(a).toEqual({ kind: "unreachable" });
        expect(b.kind).toBe("probed");
        // The counter of the call that never got through is spent: the next one is higher.
        expect(
            t.app.on("/add").map((s) => headerOf(s, "X-Anydm-Counter")),
        ).toEqual([
            "2",
        ]);
    });
});

describe("a session that was verified a while ago", () => {
    // The app quit and a program that is not the app took its port; to anything signed it says the session is over.
    const impostor = () => new Spoofer(18401, { refuses: "session_expired" });

    test("cookies never go to a program that took the port after the app was verified", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        expect((await t.client.connect()).kind).toBe("ready");
        clock.pass(10_000);
        const other = impostor();
        t.machines[18401] = other;

        const outcome = await t.client.add(18401, {
            ...body,
            cookies: "sid=SECRET",
        });

        expect(outcome).toMatchObject({ kind: "refused", error: "unverified" });
        expect(other.gotCredentials()).toBe(false);
        expect(other.seen.map((s) => s.path)).toEqual([
            "/hello",
        ]);
    });

    test("a session verified moments ago is used for cookies without another hello", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.pass(4_999);

        const outcome = await t.client.add(18401, body);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(1);
        expect(t.app.on("/add")).toHaveLength(1);
    });

    test("an older session is verified again before cookies go, and the request is signed in the new one", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.pass(5_000);

        const outcome = await t.client.add(18401, body);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(2);
        expect(t.app.on("/add")).toHaveLength(1);
        expect(headerOf(t.app.on("/add")[0]!, "X-Anydm-Counter")).toBe("1");
        // The check counts from the new hello: another add straight after needs none.
        await t.client.add(18401, body);
        expect(t.app.on("/hello")).toHaveLength(2);
    });

    test("a request without cookies keeps the session however old it is", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.pass(50_000);
        const { cookies: _cookies, ...plain } = body;

        const outcome = await t.client.add(18401, plain);

        expect(outcome.kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(1);
    });

    test("an empty cookie string is no cookies", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.pass(50_000);

        await t.client.add(18401, { ...body, cookies: "" });

        expect(t.app.on("/hello")).toHaveLength(1);
    });

    test("time the monotonic clock did not count (a sleeping computer) still ages the session", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.time.wall += 600_000;

        await t.client.add(18401, body);

        expect(t.app.on("/hello")).toHaveLength(2);
    });

    test("a wall clock that was set back ages the session too", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.time.wall -= 3_600_000;

        await t.client.add(18401, body);

        expect(t.app.on("/hello")).toHaveLength(2);
    });

    test("the verification is made inside the request's turn in the queue", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        clock.pass(10_000);

        const [
            first,
            second,
        ] = await Promise.all([
            t.client.add(18401, body),
            t.client.add(18401, body),
        ]);

        expect([
            first.kind,
            second.kind,
        ]).toEqual([
            "probed",
            "probed",
        ]);
        // One new hello for the pair of them (the other is connect's): the second finds the session the first just verified.
        expect(t.app.on("/hello")).toHaveLength(2);
        expect(
            t.app.on("/add").map((s) => headerOf(s, "X-Anydm-Counter")),
        ).toEqual([
            "1",
            "2",
        ]);
    });

    test("a confirm needs no new hello: the app is proved again before the browser's copy goes", async () => {
        const clock = handClock();
        const t = await setup({ clock: clock.read });
        await t.client.connect();
        const probed = (await t.client.add(18401, body)) as { token: string };
        clock.pass(10_000);

        const outcome = await t.client.confirm(18401, probed.token);

        expect(outcome.kind).toBe("added");
        expect(t.app.on("/hello")).toHaveLength(1);
    });
});

describe("a program that answers /ping and then stalls", () => {
    // Time runs a hundred times faster for deadlines: the 4 s of a hello is 40 ms, the 35 s of a link is 350 ms.
    function fastDeadlines() {
        const real = AbortSignal.timeout.bind(AbortSignal);
        return spyOn(AbortSignal, "timeout").mockImplementation((ms) =>
            real(ms / 100),
        );
    }

    test("connect finds the app past many stalled ports in one deadline, not one for each", async () => {
        const stalled: Record<number, Machine> = {};
        for (let port = 18400; port <= 18408; port += 1) {
            stalled[port] = new Spoofer(port, {
                stalls: [
                    "/hello",
                ],
            });
        }
        // The app is on the last port; every port before it stalls.
        const t = await setup({ extra: stalled, port: 18409 });
        const spy = fastDeadlines();
        const started = Date.now();
        let found;
        try {
            found = await t.client.connect();
        } finally {
            spy.mockRestore();
        }

        expect(found).toEqual({ kind: "ready", port: 18409, verified: true });
        // Nine in a row would be nine deadlines (360 ms here).
        expect(Date.now() - started).toBeLessThan(250);
    });

    test("a stalled hello holds the queue for the short deadline, not the long one", async () => {
        const stalled = new Spoofer(18400, {
            stalls: [
                "/hello",
            ],
        });
        const t = await setup({ extra: { 18400: stalled } });
        const spy = fastDeadlines();
        const started = Date.now();
        let verified: boolean;
        let outcome;
        try {
            const stuck = t.client.verify(18400);
            outcome = t.client.add(18401, body);
            verified = await stuck;
            outcome = await outcome;
        } finally {
            spy.mockRestore();
        }

        expect(verified).toBe(false);
        expect(outcome.kind).toBe("probed");
        // Behind a hello that waited the long deadline (350 ms here) the link would not even have begun.
        expect(Date.now() - started).toBeLessThan(250);
    });

    test("a link that takes a while on the verified app is still waited for", async () => {
        const t = await setup();
        await t.client.connect();
        t.app.beforeAdd = () =>
            new Promise((resolve) => setTimeout(resolve, 120));
        const spy = fastDeadlines();
        let outcome;
        try {
            // 350 ms is what a link gets here; a hello's 40 ms would have cut this off.
            outcome = await t.client.add(18401, body);
        } finally {
            spy.mockRestore();
        }

        expect(outcome.kind).toBe("probed");
    });
});

describe("not paired", () => {
    test("a request goes out unsigned, without cookies, even if it was given some", async () => {
        const t = await setup({ paired: false });
        await t.client.connect();

        const outcome = await t.client.add(18401, {
            ...body,
            cookies: "sid=secret",
        });

        expect(outcome.kind).toBe("probed");
        const sent = t.app.on("/add")[0]!;
        expect(sent.text).not.toContain("sid=secret");
        expect(JSON.parse(sent.text)).not.toHaveProperty("cookies");
        expect(
            Object.keys(sent.headers).some((h) => h.startsWith("x-anydm")),
        ).toBe(false);
        expect(t.app.on("/hello")).toEqual([]);
    });

    test("a pairing forgotten while a link was being read leaves the confirm unsigned and without cookies", async () => {
        const t = await setup();
        await t.client.connect();
        const probed = (await t.client.add(18401, body)) as { token: string };
        await forgetPairing(t.storage);

        const outcome = await t.client.confirm(18401, probed.token);

        // The app refuses an unsigned confirm of a signed add's token: nothing is added without its owner.
        expect(outcome).toMatchObject({ kind: "refused", status: 410 });
        expect(
            Object.keys(t.app.on("/confirm")[0]!.headers).some((h) =>
                h.startsWith("x-anydm"),
            ),
        ).toBe(false);
    });

    test("pairing again later uses the new id", async () => {
        const t = await setup();
        await t.client.connect();
        const first = t.app.id;
        t.app.startPairing(CODE);
        await pair({ fetch: t.fetchFn, storage: t.storage }, CODE);
        const second = (await loadPairing(t.storage))!.id;

        await t.client.add(18401, body);

        expect(second).not.toBe(first);
        expect(headerOf(t.app.on("/add").at(-1)!, "X-Anydm-Id")).toBe(second);
    });
});

describe("verify", () => {
    test("is true for the app that holds the secret, and a session that works follows", async () => {
        const t = await setup();

        expect(await t.client.verify(18401)).toBe(true);
        expect((await t.client.add(18401, body)).kind).toBe("probed");
        expect(t.app.on("/hello")).toHaveLength(1);
    });

    test("is false for a program that can't prove it, and when not paired", async () => {
        const spoofer = new Spoofer(18400, { hello: "garbage" });
        const t = await setup({ extra: { 18400: spoofer } });
        expect(await t.client.verify(18400)).toBe(false);

        const unpaired = await setup({ paired: false });
        expect(await unpaired.client.verify(18401)).toBe(false);
    });
});

describe("secrets stay out of sight", () => {
    test("nothing is logged, and no outcome holds the secret or a MAC", async () => {
        const spies = [
            spyOn(console, "log"),
            spyOn(console, "info"),
            spyOn(console, "warn"),
            spyOn(console, "error"),
            spyOn(console, "debug"),
        ];
        const t = await setup();
        const secret = (await loadPairing(t.storage))!.secret;
        const link = await t.client.connect();
        const probed = await t.client.add(18401, {
            ...body,
            cookies: "sid=abc",
        });
        t.app.cutSession(t.app.id);
        const again = await t.client.add(18401, body);

        for (const spy of spies) {
            expect(spy).not.toHaveBeenCalled();
            spy.mockRestore();
        }
        const shown = JSON.stringify([
            link,
            probed,
            again,
        ]);
        expect(shown).not.toContain(secret);
        expect(shown).not.toMatch(/[0-9a-f]{64}/);
    });
});
