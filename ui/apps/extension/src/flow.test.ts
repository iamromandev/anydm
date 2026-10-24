import { describe, expect, spyOn, test } from "bun:test";
import contract from "../contract/bridge.json";
import type { Fetch } from "./bridge";
import { Flow, TOKEN_LIFE_MS, type Deps, type State } from "./flow";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });

const reply = (name: string) => contract.replies.find((r) => r.name === name)!;

interface Calls {
    adds: Record<string, unknown>[];
    confirms: string[];
    cookieReads: string[];
    ports: number[];
}

function setup(
    options: {
        access?: boolean;
        allowedBy?: boolean;
        anydmOn?: number | null;
        add?: () => Response;
        confirm?: () => Response;
        cookies?: { name: string; value: string }[] | "fails";
    } = {},
) {
    const calls: Calls = { adds: [], confirms: [], cookieReads: [], ports: [] };
    const timers: { cb: () => void; ms: number; live: boolean }[] = [];
    let access = options.access ?? true;
    let remembered: number | undefined;
    const anydmOn = options.anydmOn === undefined ? 18401 : options.anydmOn;

    const fetchFn: Fetch = async (url, init) => {
        const u = new URL(url);
        const port = Number(u.port);
        if (u.pathname === "/ping") {
            if (port !== anydmOn) throw new TypeError("refused");
            return options.allowedBy === false
                ? json(403, contract.refusals[0]!.body)
                : json(200, contract.ping);
        }
        calls.ports.push(port);
        const body = JSON.parse(init!.body as string);
        if (u.pathname === "/add") {
            calls.adds.push(body);
            return (options.add ?? (() => json(200, reply("probed").body)))();
        }
        calls.confirms.push(body.token);
        return (options.confirm ?? (() => json(200, reply("added").body)))();
    };

    const deps: Deps = {
        fetch: fetchFn,
        userAgent: "UA/1",
        hasHostAccess: async () => access,
        requestHostAccess: async () => {
            access = true;
            return true;
        },
        cookiesFor: async (url) => {
            calls.cookieReads.push(url);
            if (options.cookies === "fails") throw new Error("no store");
            return (
                options.cookies ?? [
                    { name: "sid", value: "abc" },
                ]
            );
        },
        lastPort: {
            get: () => remembered,
            set: (port) => {
                remembered = port;
            },
        },
        setTimer: (cb, ms) => {
            const timer = { cb, ms, live: true };
            timers.push(timer);
            return timer;
        },
        clearTimer: (handle) => {
            (handle as { live: boolean }).live = false;
        },
    };
    const states: State[] = [];
    const flow = (
        target = {
            url: "https://f.test/a.zip",
            pageUrl: "https://f.test/",
            tabId: 4,
        },
    ) => new Flow(deps, target, (s) => states.push(s));
    const expire = () => timers.filter((t) => t.live).forEach((t) => t.cb());
    return {
        calls,
        timers,
        states,
        flow,
        expire,
        deps,
        remembered: () => remembered,
    };
}

describe("Flow", () => {
    test("reads the link, shows what was found, and adds it on confirm", async () => {
        const t = setup();
        const flow = t.flow();

        await flow.start();
        expect(flow.state).toMatchObject({
            step: "ready",
            name: "a.zip",
            size: 5000,
            type: "DIRECT",
            rangesSupported: true,
            droppedCookies: false,
        });
        expect(t.calls.adds[0]).toMatchObject({
            url: "https://f.test/a.zip",
            cookies: "sid=abc",
            referrer: "https://f.test/",
            pageUrl: "https://f.test/",
            headers: { "User-Agent": "UA/1" },
        });

        await flow.confirm();
        expect(flow.state.step).toBe("added");
        expect(t.calls.confirms).toEqual([
            reply("probed").body.token as string,
        ]);
    });

    test("remembers the port that worked and sends everything to it", async () => {
        const t = setup({ anydmOn: 18406 });
        await t.flow().start();

        expect(t.remembered()).toBe(18406);
        expect(t.calls.ports).toEqual([
            18406,
        ]);
    });

    test("adds to the port it found even when the browser keeps no port", async () => {
        const t = setup({ anydmOn: 18405 });
        const forgetful = {
            get: () => undefined,
            set: () => {},
        };
        const flow = new Flow(
            { ...t.deps, lastPort: forgetful },
            { url: "https://f.test/a.zip" },
            () => {},
        );
        await flow.start();

        await flow.confirm();

        expect(flow.state.step).toBe("added");
        expect(t.calls.ports).toEqual([
            18405,
            18405,
        ]);
    });

    test("adds to the port it found, not one another window stored since", async () => {
        const t = setup({ anydmOn: 18405 });
        let stored: number | undefined;
        const shared = {
            get: () => stored,
            set: (port: number) => {
                stored = port;
            },
        };
        const flow = new Flow(
            { ...t.deps, lastPort: shared },
            { url: "https://f.test/a.zip" },
            () => {},
        );
        await flow.start();
        stored = 18409;

        await flow.confirm();

        expect(t.calls.ports.at(-1)).toBe(18405);
    });

    test("pressing Add twice adds once", async () => {
        const t = setup();
        const flow = t.flow();
        await flow.start();

        await Promise.all([
            flow.confirm(),
            flow.confirm(),
        ]);

        expect(t.calls.confirms.length).toBe(1);
        expect(flow.state.step).toBe("added");
    });

    test("a link that is already in anydm can't be added", async () => {
        const t = setup({ add: () => json(200, reply("probed-unknown").body) });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "duplicate" });
        await flow.confirm();
        expect(t.calls.confirms).toEqual([]);
    });

    test("says the link expired after 15 seconds, and checks it again on request", async () => {
        const t = setup();
        const flow = t.flow();
        await flow.start();

        expect(TOKEN_LIFE_MS).toBe(15_000);
        expect(t.timers.at(-1)!.ms).toBe(TOKEN_LIFE_MS);
        t.expire();
        expect(flow.state.step).toBe("expired");

        await flow.confirm();
        expect(t.calls.confirms).toEqual([]);

        await flow.start();
        expect(flow.state.step).toBe("ready");
        expect(t.calls.adds.length).toBe(2);
        expect(t.calls.cookieReads.length).toBe(2);
    });

    test("an expiry timer does not fire once the link was added", async () => {
        const t = setup();
        const flow = t.flow();
        await flow.start();
        await flow.confirm();

        t.expire();

        expect(flow.state.step).toBe("added");
    });

    test("a token the app has forgotten asks to check the link again", async () => {
        const t = setup({
            confirm: () => json(410, reply("unknown-token").body),
        });
        const flow = t.flow();
        await flow.start();
        await flow.confirm();

        expect(flow.state).toMatchObject({
            step: "failed",
            action: "probe-again",
        });
    });

    test("asks for access first when the browser has not granted it", async () => {
        const t = setup({ access: false });
        const flow = t.flow();

        await flow.start();
        expect(flow.state.step).toBe("needs-permission");
        expect(t.calls.adds).toEqual([]);

        await flow.allowAccess();
        expect(flow.state.step).toBe("ready");
    });

    test("says anydm is not running when no port answers", async () => {
        const t = setup({ anydmOn: null });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed", action: "retry" });
        expect(t.calls.adds).toEqual([]);
    });

    test("tells a browser that is not paired to pair it", async () => {
        const t = setup({ allowedBy: false });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed", action: "pair" });
        expect(t.calls.adds).toEqual([]);
    });

    test("offers to add without cookies when the app can't use them, and does", async () => {
        let first = true;
        const t = setup({
            add: () => {
                if (first) {
                    first = false;
                    return json(400, reply("add-bad-request").body);
                }
                return json(200, reply("probed").body);
            },
        });
        const flow = t.flow();
        await flow.start();
        expect(flow.state).toMatchObject({
            step: "failed",
            action: "without-cookies",
        });

        await flow.withoutCookies();

        expect(flow.state.step).toBe("ready");
        expect(t.calls.adds[1]).not.toHaveProperty("cookies");
        expect(t.calls.adds[1]).toHaveProperty("headers");
    });

    test("carries on without cookies when the browser can't read them", async () => {
        const t = setup({ cookies: "fails" });
        const flow = t.flow();
        await flow.start();

        expect(flow.state.step).toBe("ready");
        expect(t.calls.adds[0]).not.toHaveProperty("cookies");
    });

    test("says so when the cookies were too many to send", async () => {
        const t = setup({
            cookies: [
                { name: "big", value: "x".repeat(70_000) },
            ],
        });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({
            step: "ready",
            droppedCookies: true,
        });
    });

    test("never asks the network about a link it can't hand over", async () => {
        const t = setup();
        const flow = t.flow({
            url: "javascript:alert(1)",
            pageUrl: undefined,
            tabId: 1,
        } as never);
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed" });
        expect(t.calls.adds).toEqual([]);
        expect(t.calls.cookieReads).toEqual([]);
    });

    test("reads no cookies for an address that has none", async () => {
        const t = setup();
        const flow = t.flow({
            url: "magnet:?xt=urn:btih:abc",
            pageUrl: "https://f.test/",
            tabId: 4,
        });
        await flow.start();

        expect(t.calls.cookieReads).toEqual([]);
        expect(t.calls.adds[0]).not.toHaveProperty("cookies");
    });

    test("logs and keeps nothing of the cookies", async () => {
        const log = spyOn(console, "log");
        const error = spyOn(console, "error");
        const warn = spyOn(console, "warn");
        const t = setup({
            confirm: () => json(503, reply("confirm-unavailable").body),
        });
        const flow = t.flow();
        await flow.start();
        await flow.confirm();

        expect(log).not.toHaveBeenCalled();
        expect(error).not.toHaveBeenCalled();
        expect(warn).not.toHaveBeenCalled();
        expect(JSON.stringify(t.states)).not.toContain("abc");
        log.mockRestore();
        error.mockRestore();
        warn.mockRestore();
    });
});
