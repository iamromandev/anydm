import { describe, expect, test } from "bun:test";
import type { Connected } from "./client";
import type { State } from "./flow";
import type { PairOutcome } from "./pairing";
import { DEFAULT_SETTINGS } from "./settings";
import {
    captureAccessMessage,
    pairMessage,
    pairingView,
    settingsView,
    statusView,
    viewOf,
} from "./view";

const origin = "moz-extension://5f1c3a52-0b7e-4c6a-9d11-2e1f6c0a7b44";
const ids = (state: State) => viewOf(state, origin).buttons.map((b) => b.id);

describe("viewOf", () => {
    test("shows what was found and offers to add it", () => {
        const view = viewOf(
            {
                step: "ready",
                name: "a.zip",
                size: 4_400_000,
                type: "DIRECT",
                rangesSupported: true,
                droppedCookies: false,
                verified: true,
                withoutCookies: false,
            },
            origin,
        );

        expect(view.heading).toBe("a.zip");
        expect(view.lines.join(" ")).toContain("4.2 MiB");
        expect(view.buttons).toEqual([
            { id: "add", label: "Add to anydm", primary: true },
        ]);
    });

    test("says when the name or size is not known, and when downloads can't resume", () => {
        const view = viewOf(
            {
                step: "ready",
                name: null,
                size: null,
                type: "DIRECT",
                rangesSupported: false,
                droppedCookies: false,
                verified: true,
                withoutCookies: false,
            },
            origin,
        );

        expect(view.heading).toBe("Ready to add");
        expect(view.lines.join(" ")).toContain("unknown size");
        expect(view.lines.join(" ")).toContain("resume");
    });

    test("says when the cookies were left out", () => {
        const base = {
            step: "ready" as const,
            name: "a",
            size: 1,
            type: "DIRECT",
            rangesSupported: true,
            verified: true,
        };

        expect(
            viewOf(
                { ...base, droppedCookies: true, withoutCookies: false },
                origin,
            ).lines.join(" "),
        ).toContain("Too many cookies");
        expect(
            viewOf(
                { ...base, droppedCookies: false, withoutCookies: true },
                origin,
            ).lines.join(" "),
        ).toContain("without cookies");
    });

    test("a browser that is not paired is told why the cookies are not sent", () => {
        const view = viewOf(
            {
                step: "ready",
                name: "a",
                size: 1,
                type: "DIRECT",
                rangesSupported: true,
                droppedCookies: false,
                verified: false,
                withoutCookies: false,
            },
            origin,
        );

        expect(view.lines.join(" ")).toContain("not paired");
        expect(view.lines.join(" ")).toContain("cookies");
    });

    test("a paired browser is not told that", () => {
        const view = viewOf(
            {
                step: "ready",
                name: "a",
                size: 1,
                type: "DIRECT",
                rangesSupported: true,
                droppedCookies: false,
                verified: true,
                withoutCookies: false,
            },
            origin,
        );

        expect(view.lines.join(" ")).not.toContain("not paired");
    });

    test("a link already in anydm has no Add button", () => {
        expect(ids({ step: "duplicate", name: "a.zip" })).toEqual([]);
        expect(
            viewOf({ step: "duplicate", name: "a.zip" }, origin).heading,
        ).toContain("Already");
    });

    test("each end state offers the way on", () => {
        expect(ids({ step: "needs-permission" })).toEqual([
            "allow",
        ]);
        expect(ids({ step: "expired" })).toEqual([
            "probe-again",
        ]);
        expect(ids({ step: "added" })).toEqual([
            "close",
        ]);
        expect(ids({ step: "finding" })).toEqual([]);
        expect(ids({ step: "adding" })).toEqual([]);
    });

    test("a failure offers the button for its action", () => {
        expect(ids({ step: "failed", text: "x", action: "retry" })).toEqual([
            "retry",
        ]);
        expect(
            ids({ step: "failed", text: "x", action: "without-cookies" }),
        ).toEqual([
            "without-cookies",
        ]);
        expect(
            ids({ step: "failed", text: "x", action: "probe-again" }),
        ).toEqual([
            "probe-again",
        ]);
        expect(ids({ step: "failed", text: "x", action: undefined })).toEqual([
            "close",
        ]);
    });

    test("a browser that is not paired is shown its address to copy", () => {
        const view = viewOf(
            { step: "failed", text: "Add this browser", action: "pair" },
            origin,
        );

        expect(view.origin).toBe(origin);
        expect(view.buttons.map((b) => b.id)).toEqual([
            "copy",
            "retry",
        ]);
    });

    test("no other view shows the address", () => {
        expect(viewOf({ step: "added" }, origin).origin).toBeUndefined();
    });
});

const ready = (verified: boolean, port = 18402): Connected => ({
    kind: "ready",
    port,
    verified,
});

describe("statusView", () => {
    test("says anydm is running, and which port", () => {
        const view = statusView(ready(true), origin);

        expect(view.lines.join(" ")).toContain("running");
        expect(view.lines.join(" ")).toContain("18402");
        expect(view.origin).toBeUndefined();
    });

    test("says anydm is not running", () => {
        expect(statusView({ kind: "none" }, origin).lines.join(" ")).toContain(
            "not running",
        );
    });

    test("a browser that anydm does not answer is shown its address", () => {
        const view = statusView({ kind: "forbidden" }, origin);

        expect(view.origin).toBe(origin);
        expect(view.buttons.map((b) => b.id)).toContain("copy");
    });

    test("always says how to use it", () => {
        expect(statusView({ kind: "none" }, origin).lines.join(" ")).toContain(
            "Right-click",
        );
    });

    test("says when the extension's own background part did not answer", () => {
        const view = statusView({ kind: "no-background" }, origin);

        expect(view.lines.join(" ")).toContain("background");
        expect(view.buttons.map((b) => b.id)).toEqual([
            "retry",
        ]);
    });

    test("a paired browser that could not verify anydm is told so, and nothing is sent", () => {
        for (const reason of [
            "unpaired",
            "unavailable",
            "no-proof",
        ] as const) {
            const view = statusView({ kind: "unverified", reason }, origin);

            expect(view.lines.join(" ")).toContain("anydm");
            expect(view.buttons.map((b) => b.id)).toEqual([
                "retry",
            ]);
        }
    });
});

describe("pairingView", () => {
    test("a browser with no secret is not paired, and can pair", () => {
        const view = pairingView(false, { kind: "none" });

        expect(view.status).toBe("Not paired.");
        expect(view.canPair).toBe(true);
        expect(view.canForget).toBe(false);
        expect(view.help).toContain("Pair a browser");
    });

    test("a paired browser whose app proved itself is paired", () => {
        const view = pairingView(true, ready(true));

        expect(view.status).toBe("Paired with anydm.");
        expect(view.canForget).toBe(true);
        expect(view.canPair).toBe(true);
    });

    test("a paired browser says how it is when anydm can't be verified, and can still forget", () => {
        const cases: [
            Connected,
            string,
        ][] = [
            [
                { kind: "none" },
                "not running",
            ],
            [
                { kind: "forbidden" },
                "does not answer this browser",
            ],
            [
                { kind: "unverified", reason: "unpaired" },
                "does not know this browser",
            ],
            [
                { kind: "unverified", reason: "unavailable" },
                "can't read",
            ],
            [
                { kind: "unverified", reason: "no-proof" },
                "could not prove",
            ],
            [
                { kind: "no-background" },
                "background",
            ],
        ];

        for (const [
            connected,
            words,
        ] of cases) {
            const view = pairingView(true, connected);

            expect(view.status).toContain("Paired");
            expect(view.status + view.help).toContain(words);
            expect(view.canForget).toBe(true);
        }
    });

    test("nothing in it names a secret, an id or a code", () => {
        const shown = JSON.stringify([
            pairingView(true, ready(true)),
            pairingView(false, ready(false)),
        ]);

        expect(shown).not.toMatch(/[0-9a-f]{32}/);
    });
});

describe("pairMessage", () => {
    const outcomes: PairOutcome[] = [
        { kind: "paired" },
        { kind: "bad-code" },
        { kind: "none" },
        { kind: "forbidden" },
        { kind: "wrong-code" },
        { kind: "no-code" },
        { kind: "throttled", retryAfterSeconds: 30 },
        { kind: "not-saved" },
        { kind: "not-kept" },
        { kind: "unavailable" },
        { kind: "mismatch" },
        { kind: "failed" },
    ];

    test("says something for each outcome, and what a wrong code or a program that is not anydm comes to", () => {
        for (const outcome of outcomes) {
            expect(pairMessage(outcome).length).toBeGreaterThan(10);
        }
        expect(pairMessage({ kind: "wrong-code" })).toContain(
            "not the anydm app",
        );
    });

    test("says how long to wait when there were too many tries", () => {
        expect(
            pairMessage({ kind: "throttled", retryAfterSeconds: 30 }),
        ).toContain("30 seconds");
    });

    test("says to try again where trying again helps", () => {
        for (const kind of [
            "not-saved",
            "unavailable",
        ] as const) {
            expect(pairMessage({ kind })).toContain("try again");
        }
    });

    test("says a new code is needed when anydm took the code but this browser could not keep the pairing, and where the leftover is", () => {
        const text = pairMessage({ kind: "not-kept" });

        expect(text).toContain("new code");
        expect(text).toContain("Browsers");
        expect(text).toContain("revoke");
        expect(text).not.toContain("The code still works");
    });

    test("says to start pairing in anydm when it is not waiting for a code", () => {
        expect(pairMessage({ kind: "no-code" })).toContain("Pair a browser");
    });
});

describe("captureAccessMessage", () => {
    test("says capture is on, or that it stays off and why", () => {
        expect(captureAccessMessage(true, true)).toContain("on");
        expect(captureAccessMessage(true, false)).toContain("paired");
        expect(captureAccessMessage(false, true)).toContain("stays off");
        expect(captureAccessMessage(false, true)).toContain("downloads");
        expect(captureAccessMessage(false, true)).toContain("all sites");
    });
});

describe("viewOf skipped", () => {
    test("says the browser keeps a small file", () => {
        const view = viewOf({ step: "skipped", reason: "small" }, origin);

        expect(view.heading).toBe("Left to the browser");
        expect(view.lines.join(" ")).toContain("smaller");
        expect(view.buttons.map((b) => b.id)).toEqual([
            "close",
        ]);
    });

    test("says the browser keeps an excluded site", () => {
        const view = viewOf({ step: "skipped", reason: "excluded" }, origin);

        expect(view.heading).toBe("Left to the browser");
        expect(view.lines.join(" ")).toContain("excluded");
    });
});

describe("settingsView", () => {
    test("says that the size and site settings do nothing while capture is off, and not when it is on", () => {
        expect(settingsView(DEFAULT_SETTINGS, true).limitsNote).toContain(
            "only to captured downloads",
        );
        expect(
            settingsView({ ...DEFAULT_SETTINGS, captureDownloads: true }, true)
                .limitsNote,
        ).toBeNull();
    });

    test("says that capture needs a pairing when the browser has none", () => {
        expect(settingsView(DEFAULT_SETTINGS, false).pairingNote).toContain(
            "paired",
        );
        expect(settingsView(DEFAULT_SETTINGS, true).pairingNote).toBeNull();
    });

    test("says what a MiB is, and never calls it a MB", () => {
        const view = settingsView(DEFAULT_SETTINGS, true);

        expect(view.sizeHint).toContain("1,048,576");
        expect(JSON.stringify(view)).not.toMatch(/\bMB\b/);
    });

    test("shows the capture switch off by default, labelled and explained", () => {
        const view = settingsView(DEFAULT_SETTINGS, true);

        expect(view.captureLabel).toBe("Capture browser downloads");
        expect(view.captureValue).toBe(false);
        expect(view.captureHint).toContain("browser keeps");
    });

    test("shows the capture switch on once the user turned it on", () => {
        expect(
            settingsView({ ...DEFAULT_SETTINGS, captureDownloads: true }, true)
                .captureValue,
        ).toBe(true);
    });

    test("labels the size field and shows its value", () => {
        const view = settingsView({
            captureDownloads: false,
            minSizeMb: 5,
            excludedHosts: [],
        });

        expect(view.sizeLabel).toBe("Skip files smaller than N MiB");
        expect(view.sizeValue).toBe("5");
        expect(view.sizeHint).toContain("0");
    });

    test("shows 0 when the check is off", () => {
        expect(
            settingsView({
                captureDownloads: false,
                minSizeMb: 0,
                excludedHosts: [],
            }).sizeValue,
        ).toBe("0");
    });

    test("says there are no excluded sites when the list is empty", () => {
        const view = settingsView({
            captureDownloads: false,
            minSizeMb: 0,
            excludedHosts: [],
        });

        expect(view.hosts).toEqual([]);
        expect(view.empty).toBe("No excluded sites.");
    });

    test("lists each excluded site with a remove label naming it", () => {
        const view = settingsView({
            captureDownloads: false,
            minSizeMb: 0,
            excludedHosts: [
                "a.test",
                "b.test",
            ],
        });

        expect(view.empty).toBeNull();
        expect(view.hosts).toEqual([
            { host: "a.test", removeLabel: "Remove a.test" },
            { host: "b.test", removeLabel: "Remove b.test" },
        ]);
    });
});
