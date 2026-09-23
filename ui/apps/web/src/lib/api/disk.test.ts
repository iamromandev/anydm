import { describe, expect, it } from "bun:test";
import { LOW_DISK_BYTES, diskTone, parseDisk } from "./disk";

const GIB = 1024 ** 3;

describe("parseDisk", () => {
    it("reads the API's disk frame", () => {
        expect(
            parseDisk({
                path: "./download",
                total_bytes: 100 * GIB,
                free_bytes: 40 * GIB,
                min_free_bytes: GIB,
            }),
        ).toEqual({
            totalBytes: 100 * GIB,
            freeBytes: 40 * GIB,
            minFreeBytes: GIB,
        });
    });

    it("refuses a frame missing a number rather than showing a wrong one", () => {
        expect(parseDisk({ total_bytes: 1, free_bytes: "lots" })).toBeNull();
        expect(parseDisk(null)).toBeNull();
    });
});

describe("diskTone", () => {
    const disk = (freeBytes: number, minFreeBytes = GIB) => ({
        totalBytes: 100 * GIB,
        freeBytes,
        minFreeBytes,
    });

    it("is quiet with room to spare", () => {
        expect(diskTone(disk(LOW_DISK_BYTES))).toBe("ok");
    });

    it("warns under 5 GiB", () => {
        expect(LOW_DISK_BYTES).toBe(5 * GIB);
        expect(diskTone(disk(LOW_DISK_BYTES - 1))).toBe("low");
    });

    it("alarms under the minimum the API enforces", () => {
        expect(diskTone(disk(GIB - 1))).toBe("critical");
    });

    it("alarms under a minimum set above 5 GiB too", () => {
        expect(diskTone(disk(9 * GIB, 10 * GIB))).toBe("critical");
    });

    it("only warns when the guard is off, since nothing is refused", () => {
        expect(diskTone(disk(0, 0))).toBe("low");
    });
});
