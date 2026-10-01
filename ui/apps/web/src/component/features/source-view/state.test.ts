import { describe, expect, it } from "bun:test";

import {
    emptySourceView,
    rememberTest,
    testLine,
    type SourceViewState,
} from "./state";

describe("emptySourceView", () => {
    it("starts unloaded, idle and with nothing confirmed", () => {
        expect(emptySourceView()).toEqual({
            items: [],
            loading: false,
            busy: {},
            lastTest: {},
            failure: null,
            confirmingDelete: null,
        });
    });
});

describe("testLine", () => {
    it("reads an answered test with its count and seconds", () => {
        expect(
            testLine({ ok: true, count: 12, tookMs: 400, message: "Answered" }),
        ).toBe("Answered · 12 results · 0.4 s");
    });

    it("uses the singular for one result", () => {
        expect(
            testLine({ ok: true, count: 1, tookMs: 800, message: "Answered" }),
        ).toBe("Answered · 1 result · 0.8 s");
    });

    it("repeats a failure in its own words", () => {
        expect(
            testLine({
                ok: false,
                count: null,
                tookMs: 30,
                message: "refused the request",
            }),
        ).toBe("refused the request");
    });

    it("says an untested source has not been tested", () => {
        expect(testLine(null)).toBe("not tested");
    });
});

describe("rememberTest", () => {
    it("keeps every other row's last answer", () => {
        const state: SourceViewState = {
            ...emptySourceView(),
            lastTest: {
                apibay: {
                    ok: true,
                    count: 3,
                    tookMs: 100,
                    message: "Answered",
                },
            },
        };

        const next = rememberTest(state, "nyaa", {
            ok: false,
            count: null,
            tookMs: 20,
            message: "timed out after 1 s",
        });

        expect(next.lastTest["apibay"]).toEqual({
            ok: true,
            count: 3,
            tookMs: 100,
            message: "Answered",
        });
        expect(next.lastTest["nyaa"]).toEqual({
            ok: false,
            count: null,
            tookMs: 20,
            message: "timed out after 1 s",
        });
    });

    it("replaces the row's own earlier answer", () => {
        const state: SourceViewState = {
            ...emptySourceView(),
            lastTest: {
                apibay: {
                    ok: false,
                    count: null,
                    tookMs: 20,
                    message: "timed out after 1 s",
                },
            },
        };

        const next = rememberTest(state, "apibay", {
            ok: true,
            count: 5,
            tookMs: 300,
            message: "Answered",
        });

        expect(next.lastTest["apibay"]?.message).toBe("Answered");
    });
});
