import { describe, expect, it } from "bun:test";

import { connectingMessage, loadingMessage } from "./loading-message";

describe("loadingMessage", () => {
    it("shows a plain starting message before any time has passed", () => {
        expect(loadingMessage(0)).toBe("Starting stream…");
    });

    it("stays plain for the first few seconds", () => {
        expect(loadingMessage(4)).toBe("Starting stream…");
    });

    it("adds elapsed time once the wait is noticeable", () => {
        expect(loadingMessage(5)).toBe("Starting stream… (5s)");
        expect(loadingMessage(29)).toBe("Starting stream… (29s)");
    });

    it("reassures the viewer once the wait gets long", () => {
        expect(loadingMessage(31)).toBe(
            "Starting stream… (31s) — first play on a fresh torrent can take a minute",
        );
    });
});

describe("connectingMessage", () => {
    it("says it's looking for peers when none are connected yet", () => {
        expect(connectingMessage(0, 0)).toBe("Connecting to swarm… looking for peers");
    });

    it("shows the peer count once at least one peer connects", () => {
        expect(connectingMessage(1, 0)).toBe("Connecting to swarm… 1 peer");
    });

    it("pluralizes peer for more than one", () => {
        expect(connectingMessage(3, 0)).toBe("Connecting to swarm… 3 peers");
    });

    it("appends download speed once data is flowing", () => {
        expect(connectingMessage(2, 348160)).toBe("Connecting to swarm… 2 peers, 340.0 KB/s");
    });
});
