import { describe, expect, it } from "bun:test";
import { canSave, draftOf, isDirty, swapped } from "./state";

const music = {
    id: "c1",
    name: "Music",
    slug: "music",
    folder: "music",
    position: 1,
    builtin: false,
    count: 0,
};

describe("a row's draft", () => {
    it("starts as the category and is dirty once either field changes", () => {
        expect(draftOf(music)).toEqual({ name: "Music", folder: "music" });
        expect(isDirty(music, { name: "Music", folder: "music" })).toBe(false);
        expect(isDirty(music, { name: "Songs", folder: "music" })).toBe(true);
        expect(isDirty(music, { name: "Music", folder: "audio" })).toBe(true);
    });

    it("needs a name to save; the folder may be blank", () => {
        expect(canSave({ name: "  ", folder: "x" })).toBe(false);
        expect(canSave({ name: "Inbox", folder: "" })).toBe(true);
    });
});

describe("swapped", () => {
    it("moves one id up or down, and refuses past either end", () => {
        expect(
            swapped(
                [
                    "a",
                    "b",
                    "c",
                ],
                1,
                -1,
            ),
        ).toEqual([
            "b",
            "a",
            "c",
        ]);
        expect(
            swapped(
                [
                    "a",
                    "b",
                    "c",
                ],
                1,
                1,
            ),
        ).toEqual([
            "a",
            "c",
            "b",
        ]);
        expect(
            swapped(
                [
                    "a",
                    "b",
                    "c",
                ],
                0,
                -1,
            ),
        ).toBeNull();
        expect(
            swapped(
                [
                    "a",
                    "b",
                    "c",
                ],
                2,
                1,
            ),
        ).toBeNull();
    });
});
