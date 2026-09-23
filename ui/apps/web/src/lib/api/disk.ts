/** Space on the API's download disk, as its `disk` event reports it. */
export interface Disk {
    totalBytes: number;
    freeBytes: number;
    /** The floor the API refuses to download below. `0` means it is off. */
    minFreeBytes: number;
}

/** Under this the status bar starts to warn, whatever the API's floor is. */
export const LOW_DISK_BYTES = 5 * 1024 ** 3;

export type DiskTone = "ok" | "low" | "critical";

/** A `disk` frame, or `null` when it is not one: a wrong number is worse than none. */
export function parseDisk(raw: unknown): Disk | null {
    if (typeof raw !== "object" || raw === null) return null;
    const frame = raw as Record<string, unknown>;
    const numbers = [
        frame.total_bytes,
        frame.free_bytes,
        frame.min_free_bytes,
    ];
    if (!numbers.every((n) => typeof n === "number" && Number.isFinite(n))) {
        return null;
    }
    const [
        totalBytes,
        freeBytes,
        minFreeBytes,
    ] = numbers as number[];
    return { totalBytes, freeBytes, minFreeBytes };
}

/**
 * Red once downloads are being refused, amber a while before that.
 *
 * The API's floor is checked first so that one set above 5 GiB still reads
 * as red, not as a mere warning.
 */
export function diskTone(disk: Disk): DiskTone {
    if (disk.minFreeBytes > 0 && disk.freeBytes < disk.minFreeBytes) {
        return "critical";
    }
    return disk.freeBytes < LOW_DISK_BYTES ? "low" : "ok";
}
