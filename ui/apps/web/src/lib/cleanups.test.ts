import { describe, expect, test } from "bun:test";
import { createCleanups } from "./cleanups";

describe("createCleanups", () => {
    test("disposing runs the cleanups in the order they were added", () => {
        const ran: string[] = [];
        const cleanups = createCleanups();
        cleanups.add(() => ran.push("save position"));
        cleanups.add(() => ran.push("destroy player"));
        cleanups.dispose();
        expect(ran).toEqual([
            "save position",
            "destroy player",
        ]);
    });

    test("each cleanup runs once", () => {
        let count = 0;
        const cleanups = createCleanups();
        cleanups.add(() => count++);
        cleanups.dispose();
        cleanups.dispose();
        expect(count).toBe(1);
    });

    test("one added after disposal runs at once", () => {
        const cleanups = createCleanups();
        cleanups.dispose();
        let ran = false;
        cleanups.add(() => {
            ran = true;
        });
        expect(ran).toBe(true);
        expect(cleanups.disposed).toBe(true);
    });
});
