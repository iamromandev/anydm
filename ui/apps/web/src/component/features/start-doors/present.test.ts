import { describe, expect, it } from "bun:test";

import { doorsFor, showDoors } from "./present";

describe("doorsFor", () => {
    it("offers paste, drop and browse when Search is on", () => {
        expect(doorsFor(true).map((d) => d.id)).toEqual([
            "paste",
            "drop",
            "browse",
        ]);
    });

    it("leaves browse out when Search is off", () => {
        expect(doorsFor(false).map((d) => d.id)).toEqual([
            "paste",
            "drop",
        ]);
    });

    it("makes only paste and browse buttons; drop is a hint", () => {
        expect(
            doorsFor(true).map((d) => [
                d.id,
                d.action,
            ]),
        ).toEqual([
            [
                "paste",
                true,
            ],
            [
                "drop",
                false,
            ],
            [
                "browse",
                true,
            ],
        ]);
    });

    it("says in plain words what each does", () => {
        expect(doorsFor(true).map((d) => d.title)).toEqual([
            "Paste a link",
            "Drop a .torrent file",
            "Browse latest torrents",
        ]);
    });
});

describe("showDoors", () => {
    it("shows them for an empty list with nothing searched", () => {
        expect(showDoors(0, "")).toBe(true);
    });

    it("does not, when there are downloads or a search is running", () => {
        expect(showDoors(3, "")).toBe(false);
        expect(showDoors(0, "frieren")).toBe(false);
    });
});
