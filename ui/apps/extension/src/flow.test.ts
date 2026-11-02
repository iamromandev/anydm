import { describe, expect, spyOn, test } from "bun:test";
import contract from "../contract/bridge.json";
import { BridgeClient } from "./client";
import { FakeApp, Spoofer, json, machineOf, type Machine } from "./fakeApp";
import {
    Flow,
    TOKEN_LIFE_MS,
    type Deps,
    type State,
    type Target,
} from "./flow";
import { pair, type PairingStorage } from "./pairing";
import type { BrowserCookie } from "./request";
import type { Settings } from "./settings";

const CODE = "7K3M9QX2VBHT4N6R";

const reply = (name: string) => contract.replies.find((r) => r.name === name)!;

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

interface Calls {
    adds: Record<string, unknown>[];
    confirms: string[];
    cookieReads: string[];
    /** The port of each `/add` and `/confirm` that reached the app. */
    ports: number[];
}

// A browser (paired with the app unless [paired] is false), the app on [anydmOn], and what else is on the ports.
async function setup(
    options: {
        paired?: boolean;
        access?: boolean;
        allowedBy?: boolean;
        anydmOn?: number | null;
        add?: () => Response;
        confirm?: () => Response;
        cookies?: BrowserCookie[] | "fails";
        settings?: Settings | "fails";
        extra?: Record<number, Machine>;
    } = {},
) {
    const calls: Calls = { adds: [], confirms: [], cookieReads: [], ports: [] };
    const timers: { cb: () => void; ms: number; live: boolean }[] = [];
    let access = options.access ?? true;
    let remembered: number | undefined;
    const anydmOn = options.anydmOn === undefined ? 18401 : options.anydmOn;
    const app = new FakeApp(anydmOn ?? 18401);
    if (options.add) app.addReply = options.add;
    if (options.confirm) app.confirmReply = options.confirm;
    const machines: Record<number, Machine> = { ...options.extra };
    if (anydmOn !== null) machines[anydmOn] = app;
    const fetchFn = machineOf(machines);
    const storage = store();
    if (options.paired !== false && anydmOn !== null) {
        app.startPairing(CODE);
        const outcome = await pair({ fetch: fetchFn, storage }, CODE);
        if (outcome.kind !== "paired") throw new Error("not paired");
        for (const machine of Object.values(machines)) {
            (machine as { seen?: unknown[] }).seen?.splice(0);
        }
    }
    // The browser was paired while the app still answered it.
    app.allowed = options.allowedBy !== false;
    const bridge = new BridgeClient({ fetch: fetchFn, storage });

    const deps: Deps = {
        bridge,
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
        settings: async () => {
            if (options.settings === "fails") throw new Error("no storage");
            return (
                options.settings ?? {
                    captureDownloads: false,
                    minSizeMb: 0,
                    excludedHosts: [],
                }
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
        target: Target = {
            url: "https://f.test/a.zip",
            pageUrl: "https://f.test/",
            tabId: 4,
        },
    ) => new Flow(deps, target, (s) => states.push(s));
    const expire = () => timers.filter((t) => t.live).forEach((t) => t.cb());
    return {
        calls: {
            // What reached the app, whether it took it or not.
            get adds() {
                return app
                    .on("/add")
                    .map((s) => JSON.parse(s.text) as Record<string, unknown>);
            },
            get confirms() {
                return app
                    .on("/confirm")
                    .map((s) => JSON.parse(s.text).token as string);
            },
            get cookieReads() {
                return calls.cookieReads;
            },
            get ports() {
                return [
                    ...app.on("/add"),
                    ...app.on("/confirm"),
                ].map((s) => s.port);
            },
        },
        app,
        timers,
        states,
        flow,
        expire,
        deps,
        bridge,
        storage,
        machines,
        remembered: () => remembered,
    };
}

describe("Flow", () => {
    test("reads the link, shows what was found, and adds it on confirm", async () => {
        const t = await setup();
        const flow = t.flow();

        await flow.start();
        expect(flow.state).toMatchObject({
            step: "ready",
            name: "a.zip",
            size: 5000,
            type: "DIRECT",
            rangesSupported: true,
            droppedCookies: false,
            verified: true,
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
            t.app.confirmed[0]!.token,
        ]);
        expect(t.app.confirmed[0]!.signed).toBe(true);
    });

    test("remembers the port that worked and sends everything to it", async () => {
        const t = await setup({ anydmOn: 18406 });
        await t.flow().start();

        expect(t.remembered()).toBe(18406);
        expect(t.calls.ports).toEqual([
            18406,
        ]);
    });

    test("adds to the port it found even when the browser keeps no port", async () => {
        const t = await setup({ anydmOn: 18405 });
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
        const t = await setup({ anydmOn: 18405 });
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
        const t = await setup();
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
        const t = await setup({
            add: () => json(200, reply("probed-unknown").body),
        });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "duplicate" });
        await flow.confirm();
        expect(t.calls.confirms).toEqual([]);
    });

    test("says the link expired after 15 seconds, and checks it again on request", async () => {
        const t = await setup();
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
        const t = await setup();
        const flow = t.flow();
        await flow.start();
        await flow.confirm();

        t.expire();

        expect(flow.state.step).toBe("added");
    });

    test("a token the app has forgotten asks to check the link again", async () => {
        const t = await setup({
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
        const t = await setup({ access: false });
        const flow = t.flow();

        await flow.start();
        expect(flow.state.step).toBe("needs-permission");
        expect(t.calls.adds).toEqual([]);

        await flow.allowAccess();
        expect(flow.state.step).toBe("ready");
    });

    test("says anydm is not running when no port answers", async () => {
        const t = await setup({ anydmOn: null });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed", action: "retry" });
        expect(t.calls.adds).toEqual([]);
    });

    test("tells a browser the app does not answer yet to add its address in anydm", async () => {
        const t = await setup({ allowedBy: false });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed", action: "pair" });
        expect(t.calls.adds).toEqual([]);
    });

    test("offers to add without cookies when the app can't use them, and does", async () => {
        let first = true;
        const t = await setup({
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
        const t = await setup({ cookies: "fails" });
        const flow = t.flow();
        await flow.start();

        expect(flow.state.step).toBe("ready");
        expect(t.calls.adds[0]).not.toHaveProperty("cookies");
    });

    test("an automatic download whose cookies can't be read counts as one whose cookies were dropped", async () => {
        const t = await setup({ cookies: "fails" });
        const flow = t.flow({
            url: "https://f.test/a.zip",
            pageUrl: "https://f.test/",
            automatic: true,
        });
        await flow.start();

        expect(flow.state).toMatchObject({
            step: "ready",
            droppedCookies: true,
        });
    });

    test("a menu send whose cookies can't be read does not say so: it never did", async () => {
        const t = await setup({ cookies: "fails" });
        const flow = t.flow();
        await flow.start();

        expect(flow.state).toMatchObject({
            step: "ready",
            droppedCookies: false,
        });
    });

    test("says so when the cookies were too many to send", async () => {
        const t = await setup({
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
        const t = await setup();
        const flow = t.flow({
            url: "javascript:alert(1)",
            pageUrl: undefined,
            tabId: 1,
        });
        await flow.start();

        expect(flow.state).toMatchObject({ step: "failed" });
        expect(t.calls.adds).toEqual([]);
        expect(t.calls.cookieReads).toEqual([]);
    });

    test("reads no cookies for an address that has none", async () => {
        const t = await setup();
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
        const t = await setup({
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

    describe("who gets the cookies", () => {
        test("a browser that is not paired sends the link, the referrer and the User-Agent, never cookies, and reads none", async () => {
            const t = await setup({ paired: false });
            const flow = t.flow();

            await flow.start();

            expect(flow.state).toMatchObject({
                step: "ready",
                verified: false,
            });
            expect(t.calls.cookieReads).toEqual([]);
            expect(t.calls.adds[0]).toEqual({
                url: "https://f.test/a.zip",
                referrer: "https://f.test/",
                pageUrl: "https://f.test/",
                headers: { "User-Agent": "UA/1" },
            });
            await flow.confirm();
            expect(flow.state.step).toBe("added");
            expect(t.app.added[0]!.signed).toBe(false);
            expect(t.app.confirmed[0]!.signed).toBe(false);
        });

        test("a paired browser sends the cookies, signed, to the app that proved itself", async () => {
            const t = await setup();
            await t.flow().start();

            expect(t.calls.cookieReads).toEqual([
                "https://f.test/a.zip",
            ]);
            expect(t.app.added[0]!.signed).toBe(true);
            expect(t.app.added[0]!.body.cookies).toBe("sid=abc");
        });

        test("a program that can't prove itself gets no cookies and no request, and the browser's cookies are not even read", async () => {
            const spoofer = new Spoofer(18401, { hello: "garbage" });
            const t = await setup({
                extra: { 18401: spoofer },
                anydmOn: 18402,
            });
            delete t.machines[18402];
            const flow = t.flow();

            await flow.start();

            expect(flow.state).toMatchObject({ step: "failed" });
            expect((flow.state as { text: string }).text).toContain("prove");
            expect(t.calls.cookieReads).toEqual([]);
            expect(spoofer.gotCredentials()).toBe(false);
            expect(spoofer.seen.map((s) => s.path)).not.toContain("/add");
        });

        test("a program in front that can't prove itself is skipped for the app behind it", async () => {
            const spoofer = new Spoofer(18400, { hello: "garbage" });
            const t = await setup({ extra: { 18400: spoofer } });
            await t.flow().start();

            expect(t.app.added).toHaveLength(1);
            expect(spoofer.gotCredentials()).toBe(false);
            expect(spoofer.seen.map((s) => s.path)).not.toContain("/add");
        });

        test("an app that does not know this browser any more is told to the person, and nothing is sent", async () => {
            const t = await setup();
            t.app.revoke(t.app.id);
            const flow = t.flow();

            await flow.start();

            expect(flow.state).toMatchObject({ step: "failed" });
            expect((flow.state as { text: string }).text).toContain("know");
            expect(t.calls.cookieReads).toEqual([]);
            expect(t.calls.adds).toEqual([]);
        });

        test("an app that can't read its paired browsers asks to try again, and sends nothing", async () => {
            const t = await setup();
            t.app.storeDown = true;
            const flow = t.flow();

            await flow.start();

            expect(flow.state).toMatchObject({
                step: "failed",
                action: "retry",
            });
            expect(t.calls.cookieReads).toEqual([]);
            expect(t.calls.adds).toEqual([]);
        });

        test("a request refused on the way, after the link was read, is told as a failure", async () => {
            const t = await setup();
            const flow = t.flow();
            await flow.start();
            t.app.storeDown = true;

            await flow.confirm();

            expect(flow.state).toMatchObject({
                step: "failed",
                action: "retry",
            });
            expect(t.app.confirmed).toEqual([]);
        });

        test("an automatic download is not taken by an app this browser is not paired with", async () => {
            const t = await setup({ paired: false });
            const flow = t.flow({
                url: "https://f.test/a.zip",
                pageUrl: "https://f.test/",
                automatic: true,
            });

            await flow.start();

            expect(flow.state).toMatchObject({ step: "failed" });
            expect(t.calls.adds).toEqual([]);
            expect(t.calls.cookieReads).toEqual([]);
        });

        test("the cookies a download's page could not have sent are not sent for an automatic download", async () => {
            const cookies: BrowserCookie[] = [
                { name: "a", value: "1", sameSite: "strict" },
                { name: "b", value: "2", sameSite: "lax" },
                { name: "c", value: "3", sameSite: "no_restriction" },
                { name: "d", value: "4", sameSite: "unspecified" },
                { name: "e", value: "5" },
                { name: "f", value: "6", sameSite: "Strict" },
            ];
            const sentFor = async (target: {
                url: string;
                pageUrl?: string;
                automatic?: boolean;
            }) => {
                const t = await setup({ cookies });
                await t.flow(target).start();
                return t.calls.adds[0]!.cookies;
            };

            expect(
                await sentFor({
                    url: "https://f.test/a.zip",
                    pageUrl: "https://other.test/page",
                    automatic: true,
                }),
            ).toBe("b=2; c=3; d=4; e=5");
            expect(
                await sentFor({
                    url: "https://f.test/a.zip",
                    automatic: true,
                }),
            ).toBe("b=2; c=3; d=4; e=5");
            expect(
                await sentFor({
                    url: "https://f.test/a.zip",
                    pageUrl: "https://f.test/page",
                    automatic: true,
                }),
            ).toBe("a=1; b=2; c=3; d=4; e=5; f=6");
            // The same host in other letters is the same host.
            expect(
                await sentFor({
                    url: "https://F.test/a.zip",
                    pageUrl: "https://f.TEST/page",
                    automatic: true,
                }),
            ).toBe("a=1; b=2; c=3; d=4; e=5; f=6");
            // A link the person sent on purpose carries every cookie.
            expect(
                await sentFor({
                    url: "https://f.test/a.zip",
                    pageUrl: "https://other.test/page",
                }),
            ).toBe("a=1; b=2; c=3; d=4; e=5; f=6");
        });

        test("a page on another site with a similar name is not the same host", async () => {
            const t = await setup({
                cookies: [
                    { name: "a", value: "1", sameSite: "strict" },
                ],
            });
            await t
                .flow({
                    url: "https://f.test/a.zip",
                    pageUrl: "https://sub.f.test/page",
                    automatic: true,
                })
                .start();

            expect(t.calls.adds[0]).not.toHaveProperty("cookies");
        });
    });

    describe("close", () => {
        test("takes the expiry timer away, so a flow that was dropped holds nothing", async () => {
            const t = await setup();
            const flow = t.flow();
            await flow.start();
            expect(t.timers.filter((x) => x.live)).toHaveLength(1);

            flow.close();

            expect(t.timers.filter((x) => x.live)).toEqual([]);
            t.expire();
            expect(flow.state.step).toBe("ready");
        });

        test("reading again takes the old timer away: only the new one is left", async () => {
            const t = await setup();
            const flow = t.flow();
            await flow.start();
            await flow.start();

            expect(t.timers).toHaveLength(2);
            expect(t.timers.filter((x) => x.live)).toHaveLength(1);
        });

        test("a flow that was closed does not add", async () => {
            const t = await setup();
            const flow = t.flow();
            await flow.start();
            flow.close();

            await flow.confirm();

            expect(t.calls.confirms).toEqual([]);
        });

        test("a read still in flight when it is closed ends quietly", async () => {
            const t = await setup();
            const flow = t.flow();
            const reading = flow.start();
            flow.close();
            await reading;

            expect(flow.state.step).not.toBe("ready");
            expect(t.timers.filter((x) => x.live)).toEqual([]);
        });
    });

    describe("skipping what the browser should keep", () => {
        const auto = {
            url: "https://f.test/a.zip",
            pageUrl: "https://f.test/",
            tabId: 4,
            automatic: true,
        };

        test("an automatic download from an excluded host is skipped without asking the app", async () => {
            const t = await setup({
                settings: {
                    captureDownloads: false,
                    minSizeMb: 0,
                    excludedHosts: [
                        "f.test",
                    ],
                },
            });
            const flow = t.flow(auto);
            await flow.start();

            expect(flow.state).toEqual({ step: "skipped", reason: "excluded" });
            expect(t.calls.adds).toEqual([]);
            expect(t.calls.cookieReads).toEqual([]);
        });

        test("an automatic download of a known size under the minimum is skipped, and not confirmable", async () => {
            const t = await setup({
                settings: {
                    captureDownloads: false,
                    minSizeMb: 1,
                    excludedHosts: [],
                },
            });
            const flow = t.flow(auto);
            await flow.start();

            expect(flow.state).toEqual({ step: "skipped", reason: "small" });
            await flow.confirm();
            expect(t.calls.confirms).toEqual([]);
            expect(t.timers.filter((x) => x.live)).toEqual([]);
        });

        test("an automatic download at or over the minimum goes on to ready", async () => {
            const t = await setup({
                settings: {
                    captureDownloads: false,
                    minSizeMb: 0.004,
                    excludedHosts: [],
                },
            });
            const flow = t.flow(auto);
            await flow.start();

            expect(flow.state.step).toBe("ready");
        });

        test("an automatic download of unknown size is sent", async () => {
            const t = await setup({
                settings: {
                    captureDownloads: false,
                    minSizeMb: 100,
                    excludedHosts: [],
                },
                add: () =>
                    json(200, {
                        ...reply("probed").body,
                        size: null,
                    }),
            });
            const flow = t.flow(auto);
            await flow.start();

            expect(flow.state.step).toBe("ready");
        });

        test("an explicit send ignores the exclusions and the minimum", async () => {
            const t = await setup({
                settings: {
                    captureDownloads: false,
                    minSizeMb: 1000,
                    excludedHosts: [
                        "f.test",
                    ],
                },
            });
            const flow = t.flow();
            await flow.start();

            expect(flow.state.step).toBe("ready");
            await flow.confirm();
            expect(flow.state.step).toBe("added");
        });

        test("settings that can't be read send the download", async () => {
            const t = await setup({ settings: "fails" });
            const flow = t.flow(auto);
            await flow.start();

            expect(flow.state.step).toBe("ready");
        });
    });
});
