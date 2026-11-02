import { describe, expect, test } from "bun:test";
import contract from "../contract/bridge.json";
import type { Fetch } from "./bridge";
import {
    CAPTURE_ACCESS,
    Capturer,
    decide,
    hookDownloads,
    lazyDownloads,
    turnCaptureOff,
    turnCaptureOn,
    type DownloadInfo,
    type DownloadsApi,
    type DownloadsEvents,
} from "./capture";
import { BridgeClient } from "./client";
import { FakeApp, Spoofer, json, machineOf, type Machine } from "./fakeApp";
import type { Deps } from "./flow";
import { loadPairing, pair, type PairingStorage } from "./pairing";
import type { BrowserCookie } from "./request";
import { DEFAULT_SETTINGS, type Settings } from "./settings";

const MB = 1024 * 1024;
const ON: Settings = { ...DEFAULT_SETTINGS, captureDownloads: true };

const reply = (name: string) => contract.replies.find((r) => r.name === name)!;

const item = (over: Partial<DownloadInfo> = {}): DownloadInfo => ({
    id: 7,
    url: "https://f.test/a.zip",
    finalUrl: "https://cdn.f.test/a.zip",
    referrer: "https://f.test/page",
    filename: "",
    mime: "application/zip",
    fileSize: -1,
    totalBytes: -1,
    state: "in_progress",
    incognito: false,
    ...over,
});

describe("decide", () => {
    test("leaves everything to the browser while capturing is off", () => {
        expect(decide(item(), DEFAULT_SETTINGS)).toEqual({
            capture: false,
            reason: "off",
        });
    });

    test("takes a web download and builds an automatic target from it", () => {
        expect(decide(item(), ON)).toEqual({
            capture: true,
            target: {
                url: "https://f.test/a.zip",
                pageUrl: "https://f.test/page",
                automatic: true,
            },
        });
    });

    test("sends the address the link had, not the one after redirects", () => {
        const d = decide(item({ url: "https://f.test/go?id=1" }), ON);

        expect(d.capture && d.target.url).toBe("https://f.test/go?id=1");
    });

    test("an empty or non-web referrer is not passed on as the page", () => {
        for (const referrer of [
            "",
            undefined,
            "about:blank",
            "chrome://downloads",
            "not a url",
        ]) {
            const d = decide(item({ referrer }), ON);

            expect(d.capture && d.target.pageUrl).toBeUndefined();
        }
    });

    test("leaves a download whose address is not http or https", () => {
        for (const url of [
            "blob:https://f.test/0b4c-11",
            "data:text/plain;base64,aGk=",
            "file:///Users/me/a.zip",
            "filesystem:https://f.test/temporary/a.zip",
            "ftp://f.test/a.zip",
            "about:blank",
            "javascript:void(0)",
            "not a url",
            "",
        ]) {
            expect(decide(item({ url, finalUrl: url }), ON)).toEqual({
                capture: false,
                reason: "scheme",
            });
        }
    });

    test("leaves a download that ends up at an address that is not http or https", () => {
        expect(
            decide(item({ finalUrl: "blob:https://f.test/0b4c-11" }), ON),
        ).toEqual({ capture: false, reason: "scheme" });
    });

    test("a browser that gives no final address is fine", () => {
        expect(decide(item({ finalUrl: undefined }), ON).capture).toBe(true);
        expect(decide(item({ finalUrl: "" }), ON).capture).toBe(true);
    });

    test("leaves a download from an excluded site or its subdomain", () => {
        const s = {
            ...ON,
            excludedHosts: [
                "f.test",
            ],
        };

        expect(decide(item({ finalUrl: undefined }), s)).toEqual({
            capture: false,
            reason: "excluded",
        });
        expect(
            decide(
                item({ url: "https://dl.f.test/a", finalUrl: undefined }),
                s,
            ),
        ).toEqual({ capture: false, reason: "excluded" });
    });

    test("leaves a download that redirects to an excluded site", () => {
        const s = {
            ...ON,
            excludedHosts: [
                "cdn.f.test",
            ],
        };

        expect(decide(item(), s)).toEqual({
            capture: false,
            reason: "excluded",
        });
    });

    test("leaves a download of a known size under the minimum", () => {
        const s = { ...ON, minSizeMb: 5 };

        expect(decide(item({ totalBytes: 5 * MB - 1 }), s)).toEqual({
            capture: false,
            reason: "small",
        });
    });

    test("reads the size from fileSize when totalBytes is unknown", () => {
        const s = { ...ON, minSizeMb: 5 };

        expect(decide(item({ totalBytes: -1, fileSize: 1000 }), s)).toEqual({
            capture: false,
            reason: "small",
        });
    });

    test("takes a download at or over the minimum", () => {
        const s = { ...ON, minSizeMb: 5 };

        expect(decide(item({ totalBytes: 5 * MB }), s).capture).toBe(true);
        expect(decide(item({ totalBytes: 900 * MB }), s).capture).toBe(true);
    });

    test("takes a download of unknown size: -1, 0 or absent are not 'small'", () => {
        const s = { ...ON, minSizeMb: 5 };

        for (const sizes of [
            { totalBytes: -1, fileSize: -1 },
            { totalBytes: 0, fileSize: 0 },
            { totalBytes: undefined, fileSize: undefined },
        ]) {
            expect(decide(item(sizes), s).capture).toBe(true);
        }
    });

    test("leaves a download another extension started, this one's included", () => {
        for (const byExtensionId of [
            "mbbppgojipgblgakhmhbiakindokfmhn",
            "extension@anydm.dev",
            "someone-else",
        ]) {
            expect(decide(item({ byExtensionId }), ON)).toEqual({
                capture: false,
                reason: "extension",
            });
        }
    });

    test("leaves a private window's download: its cookies can't be told apart", () => {
        expect(decide(item({ incognito: true }), ON)).toEqual({
            capture: false,
            reason: "private",
        });
    });

    test("leaves a download that is no longer running", () => {
        for (const state of [
            "complete",
            "interrupted",
        ] as const) {
            expect(decide(item({ state }), ON)).toEqual({
                capture: false,
                reason: "not-running",
            });
        }
    });

    test("leaves a saved page: html, in any case or with a charset", () => {
        for (const mime of [
            "text/html",
            "TEXT/HTML",
            "text/html; charset=utf-8",
            " text/html ",
        ]) {
            expect(decide(item({ mime }), ON)).toEqual({
                capture: false,
                reason: "page",
            });
        }
        for (const mime of [
            "application/zip",
            "text/plain",
            "",
            undefined,
        ]) {
            expect(decide(item({ mime }), ON).capture).toBe(true);
        }
    });

    test("leaves a download whose file the person already named (Save page as, Save link as)", () => {
        expect(decide(item({ filename: "/home/me/saved.zip" }), ON)).toEqual({
            capture: false,
            reason: "named",
        });
        expect(decide(item({ filename: "" }), ON).capture).toBe(true);
        expect(decide(item({ filename: undefined }), ON).capture).toBe(true);
    });

    test("a browser that gives no state is taken to be running", () => {
        expect(decide(item({ state: undefined }), ON).capture).toBe(true);
    });
});

const CODE = "7K3M9QX2VBHT4N6R";

// A fake browser and a fake app sharing one log, so the order of what happened can be checked. The browser is paired with
// the app, as capture needs.
async function setup(
    options: {
        settings?: Settings | "fails";
        /** The port the app is on; null: it was there when the browser was paired and has gone since. */
        anydmOn?: number | null;
        paired?: boolean;
        access?: boolean;
        add?: () => Response | Promise<Response>;
        confirm?: () => Response | Promise<Response>;
        cancelFails?: boolean;
        eraseFails?: boolean;
        searchFails?: boolean;
        /** `cancel` does nothing, because the download has finished in the meantime. */
        finishesOnCancel?: boolean;
        /** What `ambiguousStores` says. */
        stores?: boolean | "fails";
        cookies?: BrowserCookie[] | "fails";
        extra?: Record<number, Machine>;
        limit?: number;
        maxWaiting?: number;
        /** Holds each flow here, as it begins. */
        atStart?: () => Promise<void>;
    } = {},
) {
    const log: string[] = [];
    const store = new Map<number, DownloadInfo>();
    const timers: { cb: () => void; ms: number; live: boolean }[] = [];
    const port = options.anydmOn ?? 18401;
    const app = new FakeApp(port);
    const machines: Record<number, Machine> = { ...options.extra, [port]: app };
    if (options.add) app.addReply = options.add;
    if (options.confirm) app.confirmReply = options.confirm;
    let settings: Settings | "fails" = options.settings ?? ON;
    const storage = pairingStore();
    const base = machineOf(machines);
    const fetchFn: Fetch = async (url, init) => {
        const path = new URL(url).pathname;
        if (path === "/add") log.push("add");
        if (path === "/confirm") log.push("confirm");
        const answer = await base(url, init);
        if (path === "/confirm") log.push("confirmed");
        return answer;
    };
    if (options.paired !== false) {
        app.startPairing(CODE);
        const outcome = await pair({ fetch: base, storage }, CODE);
        if (outcome.kind !== "paired") throw new Error("not paired");
        for (const machine of Object.values(machines)) {
            (machine as { seen?: unknown[] }).seen?.splice(0);
        }
    }
    if (options.anydmOn === null) delete machines[port];
    const bridge = new BridgeClient({ fetch: fetchFn, storage });
    const asked = { access: 0, inFlight: 0, peak: 0 };

    const flow: Deps = {
        bridge,
        userAgent: "UA/1",
        hasHostAccess: async () => {
            asked.access += 1;
            asked.inFlight += 1;
            asked.peak = Math.max(asked.peak, asked.inFlight);
            try {
                await options.atStart?.();
            } finally {
                asked.inFlight -= 1;
            }
            return options.access ?? true;
        },
        requestHostAccess: async () => false,
        cookiesFor: async () => {
            if (options.cookies === "fails") throw new Error("no store");
            return (
                options.cookies ?? [
                    { name: "sid", value: "abc" },
                ]
            );
        },
        lastPort: { get: () => undefined, set: () => {} },
        setTimer: (cb, ms) => {
            const timer = { cb, ms, live: true };
            timers.push(timer);
            return timer;
        },
        clearTimer: (handle) => {
            (handle as { live: boolean }).live = false;
        },
    };

    const downloads: DownloadsApi = {
        search: async ({ id }) => {
            if (options.searchFails) throw new Error("no downloads");
            const found = store.get(id);
            return found
                ? [
                      { ...found },
                  ]
                : [];
        },
        cancel: async (id) => {
            log.push(`cancel ${id}`);
            if (options.cancelFails) throw new Error("cannot cancel");
            const found = store.get(id);
            if (!found) return;
            // A finished download is not cancelled: the call succeeds and does nothing.
            if (options.finishesOnCancel) found.state = "complete";
            if (found.state === "in_progress") found.state = "interrupted";
        },
        erase: async ({ id }) => {
            log.push(`erase ${id}`);
            if (options.eraseFails) throw new Error("cannot erase");
            store.delete(id);
            return [
                id,
            ];
        },
    };

    const deps = {
        flow,
        settings: async () => {
            if (settings === "fails") throw new Error("no storage");
            return settings;
        },
        downloads,
        paired: async () => (await loadPairing(storage)) !== null,
        verify: (p: number) => bridge.verify(p),
        ambiguousStores: async () => {
            if (options.stores === "fails") throw new Error("no stores");
            return options.stores === true;
        },
        limit: options.limit,
        maxWaiting: options.maxWaiting,
    };
    const capturer = new Capturer(deps);
    return {
        log,
        app,
        machines,
        timers,
        asked,
        store,
        capturer,
        deps,
        storage,
        get adds() {
            return app
                .on("/add")
                .map((s) => JSON.parse(s.text) as Record<string, unknown>);
        },
        /** A download the browser has started: it is known to the browser and announced to the extension. */
        created(over: Partial<DownloadInfo> = {}) {
            const made = item(over);
            store.set(made.id, { ...made });
            return made;
        },
        finish(id: number) {
            store.get(id)!.state = "complete";
        },
        set(next: Settings | "fails") {
            settings = next;
        },
        /** What reached the app, whatever it was. */
        appWasAsked: () => log.some((l) => l === "add" || l === "confirm"),
        browserCopyUntouched: (id: number) =>
            store.get(id)?.state === "in_progress" &&
            !log.includes(`cancel ${id}`) &&
            !log.includes(`erase ${id}`),
        liveTimers: () => timers.filter((x) => x.live).length,
    };
}

function pairingStore(): PairingStorage {
    const data = new Map<string, unknown>();
    return {
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
}

/** Lets what is waiting on a promise run, so a test can look at a call that is held open. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 5));

describe("Capturer: what is left to the browser", () => {
    test("with capturing off, nothing is sent and nothing is cancelled", async () => {
        const t = await setup({ settings: DEFAULT_SETTINGS });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "ignored", reason: "off" });
        expect(t.appWasAsked()).toBe(false);
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a setting that can't be read means off", async () => {
        const t = await setup({ settings: "fails" });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "ignored", reason: "off" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("the switch is read for each download", async () => {
        const t = await setup({ settings: DEFAULT_SETTINGS });
        await t.capturer.handle(t.created({ id: 1 }));
        t.set(ON);
        const outcome = await t.capturer.handle(t.created({ id: 2 }));

        expect(outcome.kind).toBe("taken");
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 2",
            "erase 2",
        ]);
        expect(t.browserCopyUntouched(1)).toBe(true);
    });

    test("blob, data, file and filesystem downloads are never sent", async () => {
        const t = await setup();
        let id = 10;
        for (const url of [
            "blob:https://f.test/0b4c-11",
            "data:text/plain;base64,aGk=",
            "file:///Users/me/a.zip",
            "filesystem:https://f.test/temporary/a.zip",
        ]) {
            id += 1;
            const outcome = await t.capturer.handle(
                t.created({ id, url, finalUrl: url }),
            );

            expect(outcome).toEqual({ kind: "ignored", reason: "scheme" });
            expect(t.browserCopyUntouched(id)).toBe(true);
        }

        expect(t.log).toEqual([]);
    });

    test("a download from an excluded site is not sent", async () => {
        const t = await setup({
            settings: {
                ...ON,
                excludedHosts: [
                    "f.test",
                ],
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "ignored", reason: "excluded" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a download the browser knows to be small is not sent", async () => {
        const t = await setup({ settings: { ...ON, minSizeMb: 1 } });
        const outcome = await t.capturer.handle(
            t.created({ totalBytes: 1000 }),
        );

        expect(outcome).toEqual({ kind: "ignored", reason: "small" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a download of unknown size that the app finds small is read but not added, and kept", async () => {
        const t = await setup({ settings: { ...ON, minSizeMb: 1 } });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "skipped" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("this extension's own downloads are not taken", async () => {
        const t = await setup();
        const outcome = await t.capturer.handle(
            t.created({ byExtensionId: "mbbppgojipgblgakhmhbiakindokfmhn" }),
        );

        expect(outcome).toEqual({ kind: "ignored", reason: "extension" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a private window's download is not taken", async () => {
        const t = await setup();
        const outcome = await t.capturer.handle(t.created({ incognito: true }));

        expect(outcome).toEqual({ kind: "ignored", reason: "private" });
        expect(t.log).toEqual([]);
    });

    test("a download that is already complete is left alone", async () => {
        const t = await setup();
        const done = t.created({ state: "complete" });
        const outcome = await t.capturer.handle(done);

        expect(outcome).toEqual({ kind: "ignored", reason: "not-running" });
        expect(t.log).toEqual([]);
        expect(t.store.get(7)?.state).toBe("complete");
    });
});

describe("Capturer: when anydm does not take it, the browser keeps it", () => {
    test("anydm not running: nothing is cancelled or erased", async () => {
        const t = await setup({ anydmOn: null });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("anydm refuses to read the link: untouched", async () => {
        const t = await setup({
            add: () => json(502, reply("add-unreadable").body),
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("anydm does not answer this browser: untouched", async () => {
        const t = await setup({
            add: () => json(403, contract.refusals[0]!.body),
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome.kind).toBe("kept");
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("anydm refuses at the last step: untouched, and never cancelled", async () => {
        const t = await setup({
            confirm: () => json(500, { error: "boom", message: "" }),
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("anydm stops answering at the last step: untouched", async () => {
        const t = await setup({
            confirm: () => {
                throw new TypeError("connection reset");
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome.kind).toBe("kept");
        expect(t.log.some((l) => l.startsWith("cancel"))).toBe(false);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("the link is already in anydm: untouched", async () => {
        const t = await setup({
            add: () => json(200, { ...reply("probed").body, duplicate: true }),
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "duplicate" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("the browser has not given the extension site access: untouched, nothing sent", async () => {
        const t = await setup({ access: false });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "needs-permission" });
        expect(t.log).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a browser that can't say whether the download still runs: untouched, and not confirmed", async () => {
        const t = await setup({ searchFails: true });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome.kind).toBe("kept");
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });
});

describe("Capturer: when anydm takes it", () => {
    test("cancels and erases the browser's copy, once each, after anydm has accepted", async () => {
        const t = await setup();
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "taken" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 7",
            "erase 7",
        ]);
        expect(t.store.has(7)).toBe(false);
    });

    test("the browser's copy is not touched while anydm has not answered yet", async () => {
        let accept: (r: Response) => void = () => {};
        const held = new Promise<Response>((resolve) => {
            accept = resolve;
        });
        const t = await setup({ confirm: () => held });
        const done = t.capturer.handle(t.created());
        await settle();

        expect(t.log).toEqual([
            "add",
            "confirm",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);

        accept(json(200, reply("added").body));
        expect(await done).toEqual({ kind: "taken" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 7",
            "erase 7",
        ]);
    });

    test("sends the cookies, the User-Agent and the referrer the way the context menu does", async () => {
        const t = await setup();
        await t.capturer.handle(t.created());

        expect(t.adds).toEqual([
            {
                url: "https://f.test/a.zip",
                cookies: "sid=abc",
                headers: { "User-Agent": "UA/1" },
                referrer: "https://f.test/page",
                pageUrl: "https://f.test/page",
            },
        ]);
    });

    test("two events for one download add it and cancel it once", async () => {
        const t = await setup();
        const made = t.created();
        const [
            first,
            second,
        ] = await Promise.all([
            t.capturer.handle(made),
            t.capturer.handle(made),
        ]);

        expect(
            [
                first.kind,
                second.kind,
            ].sort(),
        ).toEqual([
            "ignored",
            "taken",
        ]);
        expect(t.log.filter((l) => l === "add")).toHaveLength(1);
        expect(t.log.filter((l) => l === "confirm")).toHaveLength(1);
        expect(t.log.filter((l) => l === "cancel 7")).toHaveLength(1);
        expect(t.log.filter((l) => l === "erase 7")).toHaveLength(1);
    });

    test("an event that comes again after the download was handled is ignored", async () => {
        const t = await setup();
        const made = t.created();
        await t.capturer.handle(made);
        const again = await t.capturer.handle(made);

        expect(again).toEqual({ kind: "ignored", reason: "seen" });
        expect(t.log.filter((l) => l.startsWith("cancel"))).toHaveLength(1);
    });

    test("a download that was kept is not tried again by a later event", async () => {
        const t = await setup({ anydmOn: null });
        const made = t.created();
        await t.capturer.handle(made);
        const again = await t.capturer.handle(made);

        expect(again).toEqual({ kind: "ignored", reason: "seen" });
    });

    test("different downloads are each taken and each cancelled once", async () => {
        const t = await setup();
        const a = t.created({ id: 1, url: "https://f.test/a.zip" });
        const b = t.created({ id: 2, url: "https://f.test/b.zip" });
        const outcomes = await Promise.all([
            t.capturer.handle(a),
            t.capturer.handle(b),
        ]);

        expect(outcomes).toEqual([
            { kind: "taken" },
            { kind: "taken" },
        ]);
        expect(t.log.filter((l) => l === "cancel 1")).toHaveLength(1);
        expect(t.log.filter((l) => l === "cancel 2")).toHaveLength(1);
        expect(t.log.filter((l) => l === "erase 1")).toHaveLength(1);
        expect(t.log.filter((l) => l === "erase 2")).toHaveLength(1);
    });
});

describe("Capturer: the browser finishes first", () => {
    test("a download that finishes while anydm reads the link is left, and not added", async () => {
        const t = await setup({
            add: () => {
                t.finish(7);
                return json(200, reply("probed").body);
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "finished" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.store.get(7)?.state).toBe("complete");
    });

    test("a download that finishes while anydm adds it is left whole: no cancel, no erase", async () => {
        const t = await setup({
            confirm: () => {
                t.finish(7);
                return json(200, reply("added").body);
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "finished" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
        ]);
        expect(t.store.get(7)?.state).toBe("complete");
    });

    test("a download that finishes as it is cancelled is not erased", async () => {
        const t = await setup({ finishesOnCancel: true });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "finished" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 7",
        ]);
        expect(t.store.get(7)?.state).toBe("complete");
    });

    test("a download the user removed meanwhile is nothing left to cancel", async () => {
        const t = await setup({
            confirm: () => {
                t.store.delete(7);
                return json(200, reply("added").body);
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "finished" });
        expect(t.log.some((l) => l.startsWith("cancel"))).toBe(false);
    });
});

describe("Capturer: the browser's calls fail", () => {
    test("a cancel that fails is not followed by an erase, and does not throw", async () => {
        const t = await setup({ cancelFails: true });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "cancel-failed" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 7",
        ]);
        expect(t.store.has(7)).toBe(true);
    });

    test("an erase that fails does not undo the cancel, and does not throw", async () => {
        const t = await setup({ eraseFails: true });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "taken" });
        expect(t.log).toEqual([
            "add",
            "confirm",
            "confirmed",
            "cancel 7",
            "erase 7",
        ]);
    });

    test("an error nobody expected leaves the download alone and does not throw", async () => {
        const t = await setup({
            add: () => {
                throw new Error("surprise");
            },
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome.kind).toBe("kept");
        expect(t.browserCopyUntouched(7)).toBe(true);
    });
});

describe("Capturer: only a browser that is paired, with an app that proves itself", () => {
    test("a browser that is not paired does not capture: nothing is looked up, nothing is sent", async () => {
        const t = await setup({ paired: false });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "ignored", reason: "unpaired" });
        expect(t.app.seen).toEqual([]);
        expect(t.asked.access).toBe(0);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a pairing that can't be read is no pairing", async () => {
        const t = await setup();
        t.storage.get = async () => {
            throw new Error("no storage");
        };
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "ignored", reason: "unpaired" });
        expect(t.app.seen).toEqual([]);
    });

    test("a program that answers like anydm but can't prove it is kept away from the cookies, and the browser keeps its download", async () => {
        const spoofer = new Spoofer(18401, { hello: "garbage" });
        const t = await setup({ extra: { 18401: spoofer }, anydmOn: 18402 });
        delete t.machines[18402];
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(spoofer.gotCredentials()).toBe(false);
        expect(spoofer.seen.map((s) => s.path)).not.toContain("/add");
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("an app that does not know this browser (revoked) leaves the download to the browser", async () => {
        const t = await setup();
        t.app.revoke(t.app.id);
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(t.appWasAsked()).toBe(false);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("an app that can't read its paired browsers leaves it to the browser, for now", async () => {
        const t = await setup();
        t.app.storeDown = true;
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "failed" });
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("the link is sent signed, so the cookies go only to the app that proved itself", async () => {
        const t = await setup();
        await t.capturer.handle(t.created());

        expect(t.app.added[0]!.signed).toBe(true);
        expect(t.app.confirmed[0]!.signed).toBe(true);
    });

    test("an app that is swapped for another program after it accepted the link is not believed: no cancel", async () => {
        const spoofer = new Spoofer(18401, { hello: "garbage" });
        const t = await setup();
        t.app.confirmReply = () => {
            // The app answers, then something else is on its port.
            t.machines[18401] = spoofer;
            return undefined;
        };
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "unverified" });
        expect(t.log.some((l) => l.startsWith("cancel"))).toBe(false);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("the app is proved once more just before the cancel, on the port it was told on", async () => {
        const t = await setup();
        await t.capturer.handle(t.created());

        // A hello to read the link, none while adding (the session is kept), and one more before the cancel.
        expect(t.app.on("/hello")).toHaveLength(2);
        expect(t.log.at(-2)).toBe("cancel 7");
    });
});

describe("Capturer: a browser copy is kept when anydm would fetch something else", () => {
    test("cookies that were too many to send mean anydm reads the link as a stranger: the browser keeps its copy", async () => {
        const t = await setup({
            cookies: [
                { name: "big", value: "x".repeat(70_000) },
            ],
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "cookies-dropped" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
        expect(t.liveTimers()).toBe(0);
    });

    test("cookies the browser could not be asked for mean the same: the browser keeps its copy", async () => {
        const t = await setup({ cookies: "fails" });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "cookies-dropped" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a size anydm found that is not the browser's is kept: it is another file", async () => {
        const t = await setup();
        t.app.size = 5000;
        const outcome = await t.capturer.handle(
            t.created({ totalBytes: 4000 }),
        );

        expect(outcome).toEqual({ kind: "kept", reason: "size-differs" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
        expect(t.liveTimers()).toBe(0);
    });

    test("the browser's size is read again just before: a size it learned since counts", async () => {
        const t = await setup();
        const made = t.created({ totalBytes: 4000 });

        const outcome = await t.capturer.handle({ ...made, totalBytes: -1 });

        expect(outcome).toEqual({ kind: "kept", reason: "size-differs" });
    });

    test("the same size is the same file", async () => {
        const t = await setup();
        const outcome = await t.capturer.handle(
            t.created({ totalBytes: 5000 }),
        );

        expect(outcome).toEqual({ kind: "taken" });
    });

    test("a size either side does not know is not a difference", async () => {
        const unknownHere = await setup();
        expect(
            await unknownHere.capturer.handle(
                unknownHere.created({ totalBytes: -1 }),
            ),
        ).toEqual({ kind: "taken" });

        const unknownThere = await setup();
        unknownThere.app.size = null;
        expect(
            await unknownThere.capturer.handle(
                unknownThere.created({ totalBytes: 4000 }),
            ),
        ).toEqual({ kind: "taken" });
    });

    test("cookies marked SameSite=Strict are not sent when the page was on another site", async () => {
        const t = await setup({
            cookies: [
                { name: "a", value: "1", sameSite: "strict" },
                { name: "b", value: "2", sameSite: "lax" },
            ],
        });
        await t.capturer.handle(
            t.created({ referrer: "https://other.test/page" }),
        );

        expect(t.adds[0]!.cookies).toBe("b=2");
    });

    test("with several cookie stores (Firefox containers) the download can't be told its own cookies: the browser keeps it", async () => {
        const t = await setup({ stores: true });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "containers" });
        expect(t.app.seen).toEqual([]);
        expect(t.browserCopyUntouched(7)).toBe(true);
    });

    test("a browser that can't say how many stores it has is taken to have several", async () => {
        const t = await setup({ stores: "fails" });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "containers" });
        expect(t.browserCopyUntouched(7)).toBe(true);
    });
});

describe("Capturer: the switch is turned off while a download is being read", () => {
    test("the download is then not taken, and the browser keeps it", async () => {
        let release: () => void = () => {};
        const held = new Promise<void>((resolve) => {
            release = resolve;
        });
        const t = await setup();
        t.app.beforeAdd = () => held;
        const done = t.capturer.handle(t.created());
        await settle();
        expect(t.log).toEqual([
            "add",
        ]);

        t.set(DEFAULT_SETTINGS);
        release();

        expect(await done).toEqual({ kind: "kept", reason: "off" });
        expect(t.log).toEqual([
            "add",
        ]);
        expect(t.browserCopyUntouched(7)).toBe(true);
        expect(t.liveTimers()).toBe(0);
    });

    test("a switch that can't be read then means off", async () => {
        const t = await setup();
        t.app.beforeAdd = async () => {
            t.set("fails");
        };
        const outcome = await t.capturer.handle(t.created());

        expect(outcome).toEqual({ kind: "kept", reason: "off" });
        expect(t.browserCopyUntouched(7)).toBe(true);
    });
});

describe("Capturer: no more at once than it can look after", () => {
    test("at most `limit` downloads are read at a time, and all of them are taken in the end", async () => {
        const gates: (() => void)[] = [];
        const t = await setup({
            limit: 2,
            atStart: () =>
                new Promise<void>((resolve) => {
                    gates.push(resolve);
                }),
        });
        const done = [
            1,
            2,
            3,
            4,
            5,
        ].map((id) =>
            t.capturer.handle(
                t.created({ id, url: `https://f.test/${id}.zip` }),
            ),
        );
        await settle();

        expect(t.asked.peak).toBe(2);
        expect(t.asked.inFlight).toBe(2);

        while (gates.length > 0 || t.asked.access < 5 || t.asked.inFlight > 0) {
            gates.shift()?.();
            await settle();
        }
        const outcomes = await Promise.all(done);

        expect(outcomes.every((o) => o.kind === "taken")).toBe(true);
        expect(t.asked.peak).toBe(2);
        expect(t.log.filter((l) => l.startsWith("cancel"))).toHaveLength(5);
    });

    test("a line longer than it will wait is left to the browser, untouched", async () => {
        const gates: (() => void)[] = [];
        const t = await setup({
            limit: 1,
            maxWaiting: 1,
            atStart: () =>
                new Promise<void>((resolve) => {
                    gates.push(resolve);
                }),
        });
        const first = t.capturer.handle(t.created({ id: 1 }));
        const second = t.capturer.handle(t.created({ id: 2 }));
        await settle();
        const third = await t.capturer.handle(t.created({ id: 3 }));

        expect(third).toEqual({ kind: "kept", reason: "busy" });
        expect(t.browserCopyUntouched(3)).toBe(true);

        while (gates.length > 0 || t.asked.inFlight > 0) {
            gates.shift()?.();
            await settle();
        }
        expect((await first).kind).toBe("taken");
        expect((await second).kind).toBe("taken");
    });

    test("one that fails still lets the next one in", async () => {
        const t = await setup({ limit: 1, anydmOn: null });
        const a = t.capturer.handle(t.created({ id: 1 }));
        const b = t.capturer.handle(t.created({ id: 2 }));

        expect((await a).kind).toBe("kept");
        expect((await b).kind).toBe("kept");
    });
});

describe("Capturer: leaves nothing running", () => {
    test("no timer is left after a download that was taken", async () => {
        const t = await setup();
        await t.capturer.handle(t.created());

        expect(t.timers.length).toBeGreaterThan(0);
        expect(t.liveTimers()).toBe(0);
    });

    test("none is left after one the browser finished while anydm was reading it", async () => {
        const t = await setup({
            add: () => {
                t.finish(7);
                return json(200, reply("probed").body);
            },
        });
        await t.capturer.handle(t.created());

        expect(t.timers.length).toBeGreaterThan(0);
        expect(t.liveTimers()).toBe(0);
    });

    test("none is left after one anydm refused to add", async () => {
        const t = await setup({
            confirm: () => json(500, { error: "boom", message: "" }),
        });
        await t.capturer.handle(t.created());

        expect(t.liveTimers()).toBe(0);
    });

    test("none is left after one that finished as it was cancelled", async () => {
        const t = await setup({ finishesOnCancel: true });
        await t.capturer.handle(t.created());

        expect(t.liveTimers()).toBe(0);
    });
});

describe("the permission goes with the switch", () => {
    const events = () => {
        const listeners: ((item: DownloadInfo) => void)[] = [];
        return {
            listeners,
            api: {
                onCreated: {
                    addListener: (l: (item: DownloadInfo) => void) => {
                        listeners.push(l);
                    },
                    removeListener: (l: (item: DownloadInfo) => void) => {
                        const at = listeners.indexOf(l);
                        if (at >= 0) listeners.splice(at, 1);
                    },
                },
            },
        };
    };
    const happenings = <T>() => {
        const callbacks: ((arg: T) => void)[] = [];
        return {
            callbacks,
            event: {
                addListener: (l: (arg: T) => void) => {
                    callbacks.push(l);
                },
            },
        };
    };

    test("taking the permission back drops the listener, and granting it again registers one again", () => {
        const e = events();
        const added = happenings<unknown>();
        const removed = happenings<{ permissions?: string[] }>();
        let granted = true;
        hookDownloads(
            () => (granted ? e.api : undefined),
            () => {},
            added.event,
            removed.event,
        );
        expect(e.listeners).toHaveLength(1);

        granted = false;
        removed.callbacks[0]!({
            permissions: [
                "downloads",
            ],
        });
        expect(e.listeners).toHaveLength(0);

        granted = true;
        added.callbacks[0]!(undefined);
        expect(e.listeners).toHaveLength(1);
    });

    test("taking back something else leaves the listener", () => {
        const e = events();
        const removed = happenings<{
            permissions?: string[];
            origins?: string[];
        }>();
        hookDownloads(
            () => e.api,
            () => {},
            happenings().event,
            removed.event,
        );

        removed.callbacks[0]!({
            origins: [
                "<all_urls>",
            ],
        });
        removed.callbacks[0]!({
            permissions: [
                "cookies",
            ],
        });

        expect(e.listeners).toHaveLength(1);
    });

    test("a browser that can't remove the listener, or has lost the API, is no trouble", () => {
        const added = happenings<unknown>();
        const removed = happenings<{ permissions?: string[] }>();
        let api: DownloadsEvents | undefined = {
            onCreated: {
                addListener: () => {},
                removeListener: () => {
                    throw new Error("gone");
                },
            },
        };
        hookDownloads(
            () => api,
            () => {},
            added.event,
            removed.event,
        );

        api = undefined;
        removed.callbacks[0]!({
            permissions: [
                "downloads",
            ],
        });
        added.callbacks[0]!(undefined);
        removed.callbacks[0]!({
            permissions: [
                "downloads",
            ],
        });
    });

    // The settings as they are stored, and what the browser was asked, in the order it was.
    const browser = (
        answers: {
            grant?: Promise<boolean>;
            failSaves?: number;
            failRemove?: boolean;
        } = {},
    ) => {
        const log: string[] = [];
        let stored: Settings = DEFAULT_SETTINGS;
        let failSaves = answers.failSaves ?? 0;
        return {
            log,
            stored: () => stored,
            deps: {
                save: async (settings: Settings) => {
                    log.push(
                        `save:${settings.captureDownloads ? "on" : "off"}`,
                    );
                    if (failSaves > 0) {
                        failSaves -= 1;
                        throw new Error("storage");
                    }
                    stored = settings;
                },
                request: (access: unknown) => {
                    log.push(`request:${JSON.stringify(access)}`);
                    return answers.grant ?? Promise.resolve(true);
                },
                remove: async (access: unknown) => {
                    log.push(`remove:${JSON.stringify(access)}`);
                    if (answers.failRemove) throw new Error("no");
                    return true;
                },
            },
        };
    };

    test("turning capture on is saved before the browser's question is answered, and the question is asked at once", async () => {
        // The browser closes the toolbar window when its question appears: whatever is awaited after the request may never run.
        const never = new Promise<boolean>(() => {});
        const b = browser({ grant: never });

        void turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        // Synchronously: the click that is going on is what lets the browser ask.
        expect(b.log.map((l) => l.split(":")[0])).toEqual([
            "save",
            "request",
        ]);
        expect(b.log[0]).toBe("save:on");
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(b.stored().captureDownloads).toBe(true);
    });

    test("when the browser says yes capture stays on, and the settings are saved once", async () => {
        const b = browser();

        const result = await turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        expect(result).toEqual({
            settings: { ...DEFAULT_SETTINGS, captureDownloads: true },
            granted: true,
            saved: true,
        });
        expect(b.log.filter((l) => l.startsWith("save"))).toEqual([
            "save:on",
        ]);
    });

    test("when the browser says no the setting is put back", async () => {
        const b = browser({ grant: Promise.resolve(false) });

        const result = await turnCaptureOn(
            { ...DEFAULT_SETTINGS, minSizeMb: 5 },
            b.deps,
        );

        expect(result).toEqual({
            settings: { ...DEFAULT_SETTINGS, minSizeMb: 5 },
            granted: false,
            saved: true,
        });
        expect(b.stored().captureDownloads).toBe(false);
        expect(b.log.filter((l) => l.startsWith("save"))).toEqual([
            "save:on",
            "save:off",
        ]);
    });

    test("a question that fails is a no", async () => {
        const b = browser({ grant: Promise.reject(new Error("closed")) });

        const result = await turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        expect(result.granted).toBe(false);
        expect(b.stored().captureDownloads).toBe(false);
    });

    test("a setting that could not be saved at first is saved again once the browser says yes", async () => {
        const b = browser({ failSaves: 1 });

        const result = await turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        expect(result).toMatchObject({ granted: true, saved: true });
        expect(b.stored().captureDownloads).toBe(true);
    });

    test("a setting that can't be saved at all is said so, and capture is shown off", async () => {
        const b = browser({ failSaves: 5 });

        const result = await turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        expect(result).toEqual({
            settings: DEFAULT_SETTINGS,
            granted: true,
            saved: false,
        });
    });

    test("a no that can't be put back is said so", async () => {
        const b = browser({ grant: Promise.resolve(false), failSaves: 2 });

        const result = await turnCaptureOn(DEFAULT_SETTINGS, b.deps);

        expect(result).toMatchObject({ granted: false, saved: false });
    });

    test("turning capture off saves it, then gives the downloads permission back, and only that", async () => {
        const b = browser();
        const on = { ...DEFAULT_SETTINGS, captureDownloads: true };

        const result = await turnCaptureOff(on, b.deps);

        expect(result).toEqual({
            settings: DEFAULT_SETTINGS,
            saved: true,
            removed: true,
        });
        expect(b.log).toEqual([
            "save:off",
            'remove:{"permissions":["downloads"]}',
        ]);
    });

    test("capture is off whatever the browser does about the permission", async () => {
        const b = browser({ failRemove: true });

        const result = await turnCaptureOff(
            { ...DEFAULT_SETTINGS, captureDownloads: true },
            b.deps,
        );

        expect(result).toMatchObject({ saved: true, removed: false });
        expect(b.stored().captureDownloads).toBe(false);
    });

    test("when turning it off can't be saved the permission is kept, as the setting says", async () => {
        const b = browser({ failSaves: 1 });

        const result = await turnCaptureOff(
            { ...DEFAULT_SETTINGS, captureDownloads: true },
            b.deps,
        );

        expect(result).toMatchObject({ saved: false, removed: false });
        expect(b.log).toEqual([
            "save:off",
        ]);
    });
});

describe("downloads are an optional permission", () => {
    const events = () => {
        const listeners: ((item: DownloadInfo) => void)[] = [];
        return {
            listeners,
            api: {
                onCreated: {
                    addListener: (l: (item: DownloadInfo) => void) => {
                        listeners.push(l);
                    },
                },
            },
        };
    };
    const added = () => {
        const callbacks: (() => void)[] = [];
        return {
            callbacks,
            event: {
                addListener: (l: () => void) => {
                    callbacks.push(l);
                },
            },
        };
    };

    test("without the permission nothing is registered and nothing throws", () => {
        const a = added();
        const seen: DownloadInfo[] = [];

        hookDownloads(
            () => undefined,
            (i) => seen.push(i),
            a.event,
        );

        expect(seen).toEqual([]);
        expect(a.callbacks).toHaveLength(1);
    });

    test("with the permission already there the listener is registered at once, once", () => {
        const e = events();
        const a = added();
        const seen: DownloadInfo[] = [];

        hookDownloads(
            () => e.api,
            (i) => seen.push(i),
            a.event,
        );
        a.callbacks[0]!();
        a.callbacks[0]!();

        expect(e.listeners).toHaveLength(1);
        e.listeners[0]!(item());
        expect(seen).toEqual([
            item(),
        ]);
    });

    test("when the person grants the permission later, the listener is registered then", () => {
        const e = events();
        const a = added();
        let granted = false;
        hookDownloads(
            () => (granted ? e.api : undefined),
            () => {},
            a.event,
        );
        expect(e.listeners).toHaveLength(0);

        granted = true;
        a.callbacks[0]!();

        expect(e.listeners).toHaveLength(1);
    });

    test("a permission added that is not this one registers nothing", () => {
        const e = events();
        const a = added();
        hookDownloads(
            () => undefined,
            () => {},
            a.event,
        );

        a.callbacks[0]!();

        expect(e.listeners).toHaveLength(0);
    });

    test("the downloads API that is looked up when it is used: missing means every call fails, which the capturer reads as 'leave it'", async () => {
        let api: DownloadsApi | undefined;
        const lazy = lazyDownloads(() => api);

        await expect(lazy.search({ id: 1 })).rejects.toThrow();
        await expect(lazy.cancel(1)).rejects.toThrow();
        await expect(lazy.erase({ id: 1 })).rejects.toThrow();

        const calls: string[] = [];
        api = {
            search: async () => {
                calls.push("search");
                return [];
            },
            cancel: async () => {
                calls.push("cancel");
            },
            erase: async () => {
                calls.push("erase");
                return [];
            },
        };
        await lazy.search({ id: 1 });
        await lazy.cancel(1);
        await lazy.erase({ id: 1 });
        expect(calls).toEqual([
            "search",
            "cancel",
            "erase",
        ]);
    });

    test("with the permission taken back the browser keeps the download", async () => {
        const t = await setup();
        t.capturer = new Capturer({
            ...t.deps,
            downloads: lazyDownloads(() => undefined),
        });
        const outcome = await t.capturer.handle(t.created());

        expect(outcome.kind).toBe("kept");
        expect(t.log.some((l) => l.startsWith("cancel"))).toBe(false);
    });

    test("capture asks for the downloads permission and for all sites, which the manifest allows", () => {
        expect(CAPTURE_ACCESS).toEqual({
            permissions: [
                "downloads",
            ],
            origins: [
                "<all_urls>",
            ],
        });
    });
});
