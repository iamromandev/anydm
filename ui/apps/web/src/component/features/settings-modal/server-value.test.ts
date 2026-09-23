import { describe, expect, it } from "bun:test";
import { serverLabel, serverValue } from "./server-value";

describe("serverLabel", () => {
    it("turns a snake_case key into a sentence", () => {
        expect(serverLabel("download_workers")).toBe("Download workers");
    });

    it("drops the unit from a rate, which the value now carries", () => {
        expect(serverLabel("download_rate_limit_bps")).toBe(
            "Download rate limit",
        );
    });

    it("drops the unit from a size too", () => {
        expect(serverLabel("download_min_free_bytes")).toBe(
            "Download min free",
        );
    });
});

describe("serverValue", () => {
    it("says a zero rate is unlimited rather than stopped", () => {
        expect(serverValue("torrent_upload_limit_bps", 0)).toBe("Unlimited");
    });

    it("gives a rate in readable units", () => {
        expect(serverValue("download_rate_limit_bps", 262144)).toBe(
            "256.0 KB/s",
        );
    });

    it("gives a size in readable units", () => {
        expect(serverValue("download_min_free_bytes", 1073741824)).toBe(
            "1.0 GB",
        );
    });

    it("says a zero floor is off rather than zero bytes", () => {
        expect(serverValue("download_min_free_bytes", 0)).toBe("Off");
    });

    it("leaves everything else as the API said it", () => {
        expect(serverValue("download_workers", 0)).toBe("0");
        expect(serverValue("torrent_enabled", true)).toBe("true");
        expect(serverValue("download_dir", "./download")).toBe("./download");
    });
});
