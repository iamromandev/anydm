import { describe, expect, it } from "bun:test";

import { matchesSearch } from "./search";
import type { UiTask } from "./api";

const task = (overrides: Partial<UiTask> = {}): UiTask => ({
    id: "t",
    title: "Big Buck Bunny",
    url: "https://example.com/bunny.mkv",
    kind: "file",
    status: "complete",
    progress: 100,
    eta: 0,
    attempts: 0,
    downloadedBytes: 0,
    totalBytes: 0,
    downloadSpeed: 0,
    uploadSpeed: 0,
    peersConnected: 0,
    ...overrides,
});

describe("matchesSearch", () => {
    it("matches everything when nothing was typed", () => {
        expect(matchesSearch(task(), "")).toBe(true);
        expect(matchesSearch(task(), "   ")).toBe(true);
    });

    it("matches the title regardless of case", () => {
        expect(matchesSearch(task(), "buck")).toBe(true);
        expect(matchesSearch(task(), "BUCK")).toBe(true);
    });

    it("matches the source, which is how a magnet is found", () => {
        expect(matchesSearch(task(), "bunny.mkv")).toBe(true);
    });

    it("matches a torrent by its info hash", () => {
        expect(matchesSearch(task({ infoHash: "abc123" }), "ABC1")).toBe(true);
    });

    it("does not match what is not there", () => {
        expect(matchesSearch(task(), "sintel")).toBe(false);
    });

    it("ignores the spaces around what was typed", () => {
        expect(matchesSearch(task(), "  buck  ")).toBe(true);
    });

    it("survives a task with no info hash", () => {
        expect(matchesSearch(task({ infoHash: undefined }), "abc")).toBe(false);
    });
});
