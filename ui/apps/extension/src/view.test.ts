import { describe, expect, test } from "bun:test";
import type { State } from "./flow";
import { statusView, viewOf } from "./view";

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
                withoutCookies: false,
            },
            origin,
        );

        expect(view.heading).toBe("a.zip");
        expect(view.lines.join(" ")).toContain("4.2 MB");
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

describe("statusView", () => {
    test("says anydm is running, and which port", () => {
        const view = statusView({ port: 18402, allowed: true }, origin);

        expect(view.lines.join(" ")).toContain("running");
        expect(view.lines.join(" ")).toContain("18402");
        expect(view.origin).toBeUndefined();
    });

    test("says anydm is not running", () => {
        expect(statusView(null, origin).lines.join(" ")).toContain(
            "not running",
        );
    });

    test("a browser that is not paired is shown its address", () => {
        const view = statusView({ port: 18400, allowed: false }, origin);

        expect(view.origin).toBe(origin);
        expect(view.buttons.map((b) => b.id)).toContain("copy");
    });

    test("always says how to use it", () => {
        expect(statusView(null, origin).lines.join(" ")).toContain(
            "Right-click",
        );
    });
});
