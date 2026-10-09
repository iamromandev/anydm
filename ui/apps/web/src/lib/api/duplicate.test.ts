import { describe, expect, it } from "bun:test";

import {
    addAnyway,
    canAddAnyway,
    directRequest,
    duplicateOf,
    entryStanding,
    withRowOnTop,
} from "./duplicate";
import { ApiError, unwrap } from "./envelope";
import type { UiTask } from "./download";

const refusal = () => {
    try {
        unwrap({
            status: "error",
            code: 409,
            message: "Already in your list: Clip (completed)",
            details: [
                {
                    subject: "d1",
                    description: "Clip",
                    fields: [
                        "completed",
                    ],
                },
            ],
        });
    } catch (err) {
        return err;
    }
};

describe("duplicateOf", () => {
    it("names the download a 409 refers to", () => {
        expect(duplicateOf(refusal())).toEqual({
            id: "d1",
            title: "Clip",
            status: "completed",
        });
    });

    it("names the playlist when one of its videos is the download", () => {
        let caught: unknown;
        try {
            unwrap({
                status: "error",
                code: 409,
                message: "Already in your list: Clip (completed), in Talks",
                details: [
                    {
                        subject: "v1",
                        description: "Clip",
                        fields: [
                            "completed",
                        ],
                    },
                    {
                        subject: "g1",
                        description: "Talks",
                        fields: [
                            "collection",
                        ],
                    },
                ],
            });
        } catch (err) {
            caught = err;
        }
        expect(duplicateOf(caught)).toEqual({
            id: "v1",
            title: "Clip",
            status: "completed",
            collectionId: "g1",
            collectionTitle: "Talks",
        });
    });

    it("ignores a 409 that names nothing", () => {
        expect(duplicateOf(new ApiError("Task is paused", 409))).toBeNull();
    });

    it("ignores other failures", () => {
        expect(
            duplicateOf(
                new ApiError("no", 400, undefined, [
                    { subject: "x" },
                ]),
            ),
        ).toBeNull();
        expect(duplicateOf(new Error("boom"))).toBeNull();
    });
});

describe("Add anyway", () => {
    it("is offered for pages and files, not torrents", () => {
        expect(canAddAnyway("site")).toBe(true);
        expect(canAddAnyway("url")).toBe(true);
        expect(canAddAnyway("link")).toBe(true);
        expect(canAddAnyway("magnet")).toBe(false);
        expect(canAddAnyway("file")).toBe(false);
    });

    it("resends the same add as a second copy", () => {
        const input = { type: "site" as const, value: "https://v.test/1" };
        expect(addAnyway({ ...input, preset: "720" })).toEqual({
            ...input,
            preset: "720",
            allowDuplicate: true,
        });
    });

    it("sends allow_duplicate on a page and on a file", () => {
        const site = directRequest(
            addAnyway({
                type: "site",
                value: "https://v.test/1",
                preset: "720",
            }),
        );
        expect(site).toEqual({
            path: "/download/media",
            body: {
                url: "https://v.test/1",
                preset: "720",
                allow_duplicate: true,
            },
        });
        expect(
            directRequest(
                addAnyway({ type: "url", value: "https://f.test/a" }),
            ),
        ).toEqual({
            path: "/download/url",
            body: { url: "https://f.test/a", allow_duplicate: true },
        });
    });

    it("leaves the flag off an ordinary add", () => {
        expect(
            directRequest({ type: "url", value: "https://f.test/a" }).body,
        ).toEqual({ url: "https://f.test/a" });
    });
});

describe("entryStanding", () => {
    const row = (id: string) => ({ id }) as UiTask;
    const view = (ids: string[], page: number, totalPages: number) => ({
        rows: ids.map(row),
        page,
        totalPages,
        loading: false,
    });

    it("finds a video the loaded pages hold", () => {
        expect(
            entryStanding(
                view(
                    [
                        "a",
                        "b",
                    ],
                    1,
                    3,
                ),
                "b",
            ),
        ).toBe("found");
    });

    it("asks for more while a page is left", () => {
        expect(
            entryStanding(
                view(
                    [
                        "a",
                    ],
                    1,
                    3,
                ),
                "z",
            ),
        ).toBe("more");
    });

    it("asks for the first page of a list not yet fetched", () => {
        expect(entryStanding(view([], 0, 1), "z")).toBe("more");
    });

    it("says absent once every page is in and the video is not there", () => {
        expect(
            entryStanding(
                view(
                    [
                        "a",
                    ],
                    3,
                    3,
                ),
                "z",
            ),
        ).toBe("absent");
        expect(entryStanding(undefined, "z")).toBe("absent");
    });
});

describe("withRowOnTop", () => {
    const row = (id: string) => ({ id }) as UiTask;

    it("puts a row the list lacks on top", () => {
        const out = withRowOnTop(
            [
                row("a"),
                row("b"),
            ],
            row("c"),
        );
        expect(out.map((t) => t.id)).toEqual([
            "c",
            "a",
            "b",
        ]);
    });

    it("moves a row the list holds rather than doubling it", () => {
        const out = withRowOnTop(
            [
                row("a"),
                row("b"),
            ],
            row("b"),
        );
        expect(out.map((t) => t.id)).toEqual([
            "b",
            "a",
        ]);
    });
});
