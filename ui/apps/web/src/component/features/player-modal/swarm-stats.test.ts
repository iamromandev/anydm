import { describe, expect, it } from "bun:test";

import { swarmStatsView } from "./swarm-stats";

describe("swarmStatsView", () => {
    it("pluralizes the peer count", () => {
        expect(
            swarmStatsView({
                peersConnected: 0,
                downloadBps: 0,
                progressBytes: 0,
                totalBytes: 0,
            }).peersLabel,
        ).toBe("0 peers");
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 0,
                progressBytes: 0,
                totalBytes: 0,
            }).peersLabel,
        ).toBe("1 peer");
        expect(
            swarmStatsView({
                peersConnected: 35,
                downloadBps: 0,
                progressBytes: 0,
                totalBytes: 0,
            }).peersLabel,
        ).toBe("35 peers");
    });

    it("shows a dash for speed when nothing is downloading yet", () => {
        expect(
            swarmStatsView({
                peersConnected: 0,
                downloadBps: 0,
                progressBytes: 0,
                totalBytes: 0,
            }).speedLabel,
        ).toBe("—");
    });

    it("formats a live download speed", () => {
        expect(
            swarmStatsView({
                peersConnected: 35,
                downloadBps: 7_864_320, // 7.5 MB/s
                progressBytes: 0,
                totalBytes: 900_000_000,
            }).speedLabel,
        ).toBe("7.5 MB/s");
    });

    it("computes a rounded download percent from progress and total bytes", () => {
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 0,
                progressBytes: 450_000_000,
                totalBytes: 900_000_000,
            }).percent,
        ).toBe(50);
    });

    it("treats an unknown total as zero percent instead of dividing by zero", () => {
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 0,
                progressBytes: 0,
                totalBytes: 0,
            }).percent,
        ).toBe(0);
    });

    it("has no eta when there's no speed to estimate from", () => {
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 0,
                progressBytes: 100,
                totalBytes: 900_000_000,
            }).etaLabel,
        ).toBeNull();
    });

    it("estimates remaining time from the bytes left and current speed", () => {
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 1_000_000,
                progressBytes: 0,
                totalBytes: 60_000_000,
            }).etaLabel,
        ).toBe("1m 0s");
    });

    it("has no eta once the download is already complete", () => {
        expect(
            swarmStatsView({
                peersConnected: 1,
                downloadBps: 1_000_000,
                progressBytes: 60_000_000,
                totalBytes: 60_000_000,
            }).etaLabel,
        ).toBeNull();
    });
});
