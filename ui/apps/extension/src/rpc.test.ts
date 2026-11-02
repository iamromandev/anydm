import { describe, expect, test } from "bun:test";
import contract from "../contract/bridge.json";
import { BridgeClient, type BridgeApi } from "./client";
import { FakeApp, machineOf } from "./fakeApp";
import { pair, type PairingStorage } from "./pairing";
import { remoteBridge, serveBridge, type Runtime } from "./rpc";

const CODE = "7K3M9QX2VBHT4N6R";
const body = contract.requests[0]!.body;

type Listener = (
    message: unknown,
    sender: { id?: string },
    sendResponse: (reply: unknown) => void,
) => boolean | undefined;

// The browser's messaging between the extension's own parts: one worker that listens, pages that send.
function runtimeOf(id = "me") {
    const listeners: Listener[] = [];
    const runtime: Runtime = {
        id,
        onMessage: {
            addListener: (listener) => {
                listeners.push(listener);
            },
        },
    };
    // What a page's `runtime.sendMessage` does: the first listener that takes it answers.
    const send = (message: unknown, from = id): Promise<unknown> =>
        new Promise((resolve, reject) => {
            for (const listener of listeners) {
                const kept = listener(
                    JSON.parse(JSON.stringify(message)),
                    { id: from },
                    (reply) => resolve(JSON.parse(JSON.stringify(reply))),
                );
                if (kept) return;
            }
            reject(new Error("Receiving end does not exist"));
        });
    return { runtime, send, listeners };
}

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

async function setup(paired = true) {
    const app = new FakeApp(18401);
    const fetchFn = machineOf({ 18401: app });
    const storage = store();
    if (paired) {
        app.startPairing(CODE);
        await pair({ fetch: fetchFn, storage }, CODE);
    }
    const client = new BridgeClient({ fetch: fetchFn, storage });
    const m = runtimeOf();
    serveBridge(client, m.runtime);
    return { app, client, m, remote: remoteBridge(m.send) };
}

describe("a page asks the worker that owns the session", () => {
    test("connect, add and confirm come back as the worker's own answers", async () => {
        const t = await setup();

        expect(await t.remote.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: true,
        });
        const probed = await t.remote.add(18401, { ...body, cookies: "sid=1" });
        expect(probed.kind).toBe("probed");
        const added = await t.remote.confirm(
            18401,
            (probed as { token: string }).token,
        );
        expect(added).toEqual({ kind: "added", id: 1 });
        expect(t.app.added[0]!.signed).toBe(true);
        expect(t.app.added[0]!.body.cookies).toBe("sid=1");
    });

    test("the page never says hello itself: one hello per connect, all from the worker", async () => {
        const t = await setup();
        await t.remote.connect();
        await t.remote.add(18401, body);
        await t.remote.connect();

        expect(t.app.on("/hello")).toHaveLength(2);
        expect(t.app.sessions.size).toBe(1);
    });

    test("a browser that is not paired sends nothing signed, whoever asks", async () => {
        const t = await setup(false);

        expect(await t.remote.connect()).toEqual({
            kind: "ready",
            port: 18401,
            verified: false,
        });
        await t.remote.add(18401, { ...body, cookies: "sid=1" });

        expect(t.app.added[0]!.signed).toBe(false);
        expect(t.app.added[0]!.body).not.toHaveProperty("cookies");
    });

    test("with no worker listening, a connect says so and a request is unreachable", async () => {
        const m = runtimeOf();
        const remote = remoteBridge(m.send);

        expect(await remote.connect()).toEqual({ kind: "no-background" });
        expect(await remote.add(18401, body)).toEqual({ kind: "unreachable" });
        expect(await remote.confirm(18401, "tok")).toEqual({
            kind: "unreachable",
        });
    });

    test("an answer that is not the worker's is no answer", async () => {
        for (const odd of [
            undefined,
            null,
            "ok",
            { ok: true },
            { ok: false },
        ]) {
            const remote = remoteBridge(async () => odd);

            expect(await remote.connect()).toEqual({ kind: "no-background" });
            expect(await remote.add(18401, body)).toEqual({
                kind: "unreachable",
            });
        }
    });
});

describe("the worker", () => {
    test("answers through sendResponse and says it will, which both browsers take", async () => {
        const t = await setup();
        let reply: unknown;
        const kept = t.m.listeners[0]!(
            { anydm: "bridge-1", op: "connect" },
            { id: "me" },
            (r) => {
                reply = r;
            },
        );

        expect(kept).toBe(true);
        await new Promise((resolve) => setTimeout(resolve, 20));
        expect(reply).toMatchObject({ ok: true });
    });

    test("leaves a message that is not for it to whoever it is for", async () => {
        const t = await setup();

        for (const message of [
            undefined,
            "hello",
            { op: "connect" },
            { anydm: "other", op: "connect" },
        ]) {
            expect(
                t.m.listeners[0]!(message, { id: "me" }, () => {
                    throw new Error("answered");
                }),
            ).toBe(false);
        }
    });

    test("does not answer a sender that is not this extension", async () => {
        const t = await setup();
        t.app.seen.splice(0);

        expect(
            t.m.listeners[0]!(
                { anydm: "bridge-1", op: "connect" },
                { id: "someone-else" },
                () => {
                    throw new Error("answered");
                },
            ),
        ).toBe(false);
        expect(
            t.m.listeners[0]!({ anydm: "bridge-1", op: "connect" }, {}, () => {
                throw new Error("answered");
            }),
        ).toBe(false);
        expect(t.app.seen).toEqual([]);
    });

    test("refuses a port that is not one of anydm's, and a body that is not a link, without calling the bridge", async () => {
        const calls: string[] = [];
        const bridge: BridgeApi = {
            connect: async () => {
                calls.push("connect");
                return { kind: "none" };
            },
            add: async () => {
                calls.push("add");
                return { kind: "unreachable" };
            },
            confirm: async () => {
                calls.push("confirm");
                return { kind: "unreachable" };
            },
        };
        const m = runtimeOf();
        serveBridge(bridge, m.runtime);
        const ask = (call: object) => m.send({ anydm: "bridge-1", ...call });

        for (const call of [
            { op: "add", port: 80, body },
            { op: "add", port: "18401", body },
            { op: "add", port: 18410, body },
            { op: "add", port: 18401, body: "text" },
            { op: "add", port: 18401, body: null },
            { op: "add", port: 18401, body: {} },
            { op: "confirm", port: 80, token: "t" },
            { op: "confirm", port: 18401, token: 5 },
            { op: "connect", preferred: "x" },
            { op: "nope" },
        ]) {
            expect(await ask(call)).toEqual({ ok: false });
        }
        expect(calls).toEqual([]);
    });

    test("a bridge that throws is an answer that says it failed, not a hang", async () => {
        const bridge: BridgeApi = {
            connect: async () => {
                throw new Error("boom");
            },
            add: async () => ({ kind: "unreachable" }),
            confirm: async () => ({ kind: "unreachable" }),
        };
        const m = runtimeOf();
        serveBridge(bridge, m.runtime);
        const remote = remoteBridge(m.send);

        expect(await remote.connect()).toEqual({ kind: "no-background" });
    });
});
