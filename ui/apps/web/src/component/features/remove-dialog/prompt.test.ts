import { describe, expect, it } from "bun:test";

import { removePrompt } from "./prompt";

describe("removePrompt", () => {
    it("offers to keep the files of a finished download", () => {
        const prompt = removePrompt("complete");

        expect(prompt.canKeepFiles).toBe(true);
        expect(prompt.confirmLabel).toBe("Remove");
    });

    it("offers to keep the files of a torrent that is still sharing", () => {
        expect(removePrompt("seeding").canKeepFiles).toBe(true);
    });

    it("says sharing stops, because removing a seeding torrent ends it", () => {
        expect(removePrompt("seeding").body).toContain("Sharing stops");
    });

    it("does not offer to keep a partial download", () => {
        for (const status of [
            "pending",
            "downloading",
            "muxing",
            "paused",
        ]) {
            expect(removePrompt(status).canKeepFiles).toBe(false);
        }
    });

    it("does not offer to keep the remains of a failure", () => {
        expect(removePrompt("failed").canKeepFiles).toBe(false);
    });

    it("warns that a running download is stopped, not just delisted", () => {
        expect(removePrompt("downloading").confirmLabel).toBe(
            "Stop and remove",
        );
        expect(removePrompt("downloading").body).toContain("discarded");
    });

    it("names every status it might meet, including one it has not met", () => {
        // Arrives over SSE as a string; a status this build predates must
        // still produce a usable dialog rather than an empty one.
        const prompt = removePrompt("something-new");

        expect(prompt.heading.length).toBeGreaterThan(0);
        expect(prompt.body.length).toBeGreaterThan(0);
        expect(prompt.canKeepFiles).toBe(false);
    });
});
