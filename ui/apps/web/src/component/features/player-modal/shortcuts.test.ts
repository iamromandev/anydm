import { describe, expect, it } from "bun:test";

import {
    SEEK_LONG_S,
    SEEK_STEP_S,
    VOLUME_STEP,
    resolveShortcut,
} from "./shortcuts";

const press = (key: string, extra: Record<string, unknown> = {}) =>
    resolveShortcut({ key, ...extra });

describe("resolveShortcut", () => {
    it("plays and pauses on space and on k", () => {
        expect(press(" ")).toEqual({ type: "togglePlay" });
        expect(press("k")).toEqual({ type: "togglePlay" });
        expect(press("K")).toEqual({ type: "togglePlay" });
    });

    it("steps through the video with the arrow keys", () => {
        expect(press("ArrowRight")).toEqual({
            type: "seekBy",
            seconds: SEEK_STEP_S,
        });
        expect(press("ArrowLeft")).toEqual({
            type: "seekBy",
            seconds: -SEEK_STEP_S,
        });
    });

    it("takes a longer step on j and l", () => {
        expect(press("l")).toEqual({ type: "seekBy", seconds: SEEK_LONG_S });
        expect(press("j")).toEqual({ type: "seekBy", seconds: -SEEK_LONG_S });
    });

    it("changes volume on the up and down arrows", () => {
        expect(press("ArrowUp")).toEqual({
            type: "volumeBy",
            delta: VOLUME_STEP,
        });
        expect(press("ArrowDown")).toEqual({
            type: "volumeBy",
            delta: -VOLUME_STEP,
        });
    });

    it("mutes, goes fullscreen, and leaves", () => {
        expect(press("m")).toEqual({ type: "toggleMute" });
        expect(press("f")).toEqual({ type: "toggleFullscreen" });
        expect(press("Escape")).toEqual({ type: "escape" });
    });

    it("offers the list of shortcuts on ?", () => {
        expect(press("?", { shiftKey: true })).toEqual({ type: "toggleHelp" });
    });

    it("ignores a key it has no meaning for", () => {
        expect(press("q")).toBeNull();
        expect(press("Enter")).toBeNull();
    });

    it("keeps out of the way of the browser's own shortcuts", () => {
        expect(press("f", { metaKey: true })).toBeNull();
        expect(press("k", { ctrlKey: true })).toBeNull();
        expect(press(" ", { altKey: true })).toBeNull();
    });

    it("leaves typing alone", () => {
        for (const tagName of [
            "INPUT",
            "TEXTAREA",
            "SELECT",
        ]) {
            expect(press(" ", { target: { tagName } })).toBeNull();
        }
        expect(
            press(" ", { target: { tagName: "DIV", isContentEditable: true } }),
        ).toBeNull();
    });

    it("still works when the focus is on an ordinary element", () => {
        expect(press(" ", { target: { tagName: "BUTTON" } })).toEqual({
            type: "togglePlay",
        });
    });
});
