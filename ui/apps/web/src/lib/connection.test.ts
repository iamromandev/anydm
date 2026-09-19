import { describe, expect, it } from "bun:test";

import {
    WARN_AFTER_MS,
    connectionLabel,
    connectionOnError,
    isDegraded,
    shouldWarn,
} from "./connection";

// EventSource.readyState, which is not defined outside a browser.
const CONNECTING = 0;
const OPEN = 1;
const CLOSED = 2;

describe("connectionOnError", () => {
    it("is reconnecting while the browser is still trying", () => {
        expect(connectionOnError(CONNECTING)).toBe("reconnecting");
    });

    it("is offline once the browser has given up", () => {
        expect(connectionOnError(CLOSED)).toBe("offline");
    });

    it("treats an error on an open stream as a reconnect, not a death", () => {
        expect(connectionOnError(OPEN)).toBe("reconnecting");
    });
});

describe("isDegraded", () => {
    it("is false only when the stream is confirmed live", () => {
        expect(isDegraded("live")).toBe(false);
        expect(isDegraded("connecting")).toBe(true);
        expect(isDegraded("reconnecting")).toBe(true);
        expect(isDegraded("offline")).toBe(true);
    });
});

describe("shouldWarn", () => {
    const NOW = 1_700_000_000_000;

    it("stays quiet about a stream that is working", () => {
        expect(shouldWarn("live", NOW - WARN_AFTER_MS * 5, NOW)).toBe(false);
    });

    it("stays quiet about a blip shorter than the grace period", () => {
        expect(shouldWarn("reconnecting", NOW - WARN_AFTER_MS + 1, NOW)).toBe(
            false,
        );
    });

    it("speaks up once the gap outlasts the grace period", () => {
        expect(shouldWarn("reconnecting", NOW - WARN_AFTER_MS, NOW)).toBe(true);
    });

    it("stays quiet when nothing has gone wrong yet", () => {
        expect(shouldWarn("reconnecting", null, NOW)).toBe(false);
    });
});

describe("connectionLabel", () => {
    it("names every state, so a new one cannot render as blank", () => {
        expect(connectionLabel("connecting")).toBe("Connecting…");
        expect(connectionLabel("live")).toBe("Live");
        expect(connectionLabel("reconnecting")).toBe("Reconnecting…");
        expect(connectionLabel("offline")).toBe("Offline");
    });
});
