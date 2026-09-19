import { describe, expect, it } from "bun:test";

import {
    TOAST_LIMIT,
    errorMessage,
    TOAST_TTL_MS,
    createToast,
    dismiss,
    prune,
    raise,
    transitionToast,
    type Toast,
} from "./toast";

const NOW = 1_700_000_000_000;

describe("createToast", () => {
    it("gives an ordinary toast a deadline", () => {
        expect(createToast("success", "Finished", NOW).expiresAt).toBe(
            NOW + TOAST_TTL_MS,
        );
    });

    it("leaves an error on screen until it is dismissed", () => {
        expect(createToast("error", "Boom", NOW).expiresAt).toBeNull();
    });

    it("gives each toast its own id", () => {
        const a = createToast("info", "a", NOW);
        const b = createToast("info", "a", NOW);

        expect(a.id).not.toBe(b.id);
    });
});

describe("raise", () => {
    it("adds the newest toast last", () => {
        const list = raise(
            [
                createToast("info", "first", NOW),
            ],
            createToast("info", "second", NOW),
        );

        expect(list.map((t) => t.message)).toEqual([
            "first",
            "second",
        ]);
    });

    it("drops the oldest once the stack is full", () => {
        let list: Toast[] = [];
        for (let i = 0; i < TOAST_LIMIT + 2; i++) {
            list = raise(list, createToast("info", `n${i}`, NOW));
        }

        expect(list.length).toBe(TOAST_LIMIT);
        expect(list[0].message).toBe("n2");
    });
});

describe("prune", () => {
    it("keeps a toast until its deadline passes", () => {
        const list = [
            createToast("success", "Finished", NOW),
        ];

        expect(prune(list, NOW + TOAST_TTL_MS - 1).length).toBe(1);
        expect(prune(list, NOW + TOAST_TTL_MS).length).toBe(0);
    });

    it("never expires an error", () => {
        const list = [
            createToast("error", "Boom", NOW),
        ];

        expect(prune(list, NOW + TOAST_TTL_MS * 100).length).toBe(1);
    });
});

describe("dismiss", () => {
    it("removes only the toast asked for", () => {
        const keep = createToast("error", "keep", NOW);
        const go = createToast("error", "go", NOW);

        expect(
            dismiss(
                [
                    keep,
                    go,
                ],
                go.id,
            ),
        ).toEqual([
            keep,
        ]);
    });
});

describe("transitionToast", () => {
    const task = { status: "complete", title: "Big Buck Bunny", kind: "file" };

    it("says nothing about a row it is seeing for the first time", () => {
        expect(transitionToast(undefined, task)).toBeNull();
    });

    it("says nothing when the status did not move", () => {
        expect(transitionToast("complete", task)).toBeNull();
    });

    it("celebrates a finished download", () => {
        expect(transitionToast("downloading", task)).toEqual({
            tone: "success",
            message: "Finished: Big Buck Bunny",
        });
    });

    it("reports a failure with the reason the API gave", () => {
        expect(
            transitionToast("downloading", {
                ...task,
                status: "failed",
                error: "connection reset",
            }),
        ).toEqual({
            tone: "error",
            message: "Failed: Big Buck Bunny — connection reset",
        });
    });

    it("reports a failure that came with no reason", () => {
        expect(
            transitionToast("downloading", { ...task, status: "failed" }),
        ).toEqual({ tone: "error", message: "Failed: Big Buck Bunny" });
    });

    it("mentions a torrent that has started sharing", () => {
        expect(
            transitionToast("downloading", { ...task, status: "seeding" }),
        ).toEqual({ tone: "info", message: "Seeding: Big Buck Bunny" });
    });

    it("stays quiet about a removal the user asked for", () => {
        expect(
            transitionToast("downloading", { ...task, status: "canceled" }),
        ).toBeNull();
    });

    it("stays quiet about ordinary progress", () => {
        expect(
            transitionToast("pending", { ...task, status: "downloading" }),
        ).toBeNull();
    });
});

describe("errorMessage", () => {
    it("uses what the API said", () => {
        expect(errorMessage(new Error("Task is paused"))).toBe(
            "Task is paused",
        );
    });

    it("falls back when something that is not an error is thrown", () => {
        expect(errorMessage("nope")).toBe("Something went wrong");
    });

    it("falls back when the error carries no message", () => {
        expect(errorMessage(new Error(""))).toBe("Something went wrong");
    });
});
