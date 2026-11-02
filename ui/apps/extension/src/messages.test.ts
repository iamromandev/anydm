import { describe, expect, test } from "bun:test";
import contract from "../contract/bridge.json";
import { describeFailure, formatSize } from "./messages";
import type { Refused } from "./bridge";

const refusedBy = (status: number, error: string, message = ""): Refused => ({
    kind: "refused",
    status,
    error,
    message,
});

describe("describeFailure", () => {
    test("says anydm is not running when nothing answers", () => {
        const failure = describeFailure({ kind: "unreachable" }, "add");

        expect(failure.text).toContain("not running");
        expect(failure.action).toBe("retry");
    });

    test("a browser that is not paired is told how to pair it", () => {
        const failure = describeFailure(refusedBy(403, "forbidden"), "add");

        expect(failure.text).toContain("settings");
        expect(failure.action).toBe("pair");
    });

    test("cookies the app can't send can be left out", () => {
        const failure = describeFailure(refusedBy(400, "bad_request"), "add");

        expect(failure.action).toBe("without-cookies");
    });

    test("a token that is gone asks for the link to be checked again", () => {
        const failure = describeFailure(
            refusedBy(410, "unknown_token"),
            "confirm",
        );

        expect(failure.action).toBe("probe-again");
    });

    test("a link that is already there is not an error to retry", () => {
        const failure = describeFailure(refusedBy(409, "duplicate"), "confirm");

        expect(failure.text).toContain("already");
        expect(failure.action).toBeUndefined();
    });

    test("the other refusals each have their own words", () => {
        const texts = new Set(
            [
                400,
                413,
                415,
                422,
                502,
                503,
                504,
                500,
            ].map(
                (status) => describeFailure(refusedBy(status, "x"), "add").text,
            ),
        );

        // 413 and 415 mean the extension and the app differ: one text for both.
        expect(texts.size).toBe(7);
    });

    test("every refusal in the contract has a text and none is empty", () => {
        for (const reply of contract.replies) {
            if (reply.status === 200) continue;
            const body = reply.body as { error: string; message: string };
            const failure = describeFailure(
                refusedBy(reply.status, body.error, body.message),
                reply.path === "/add" ? "add" : "confirm",
            );

            expect(failure.text.length).toBeGreaterThan(5);
        }
    });

    test("repeats the app's reason for an unreadable link, short, and nothing else it was sent", () => {
        const reason = describeFailure(
            refusedBy(502, "unreadable", "the server answered 404"),
            "add",
        );
        expect(reason.text).toContain("the server answered 404");

        const long = describeFailure(
            refusedBy(502, "unreadable", "x".repeat(1000)),
            "add",
        );
        expect(long.text.length).toBeLessThan(300);

        const other = describeFailure(
            refusedBy(400, "bad_request", "session=SECRET"),
            "add",
        );
        expect(other.text).not.toContain("SECRET");
    });
});

describe("describeFailure: the pairing", () => {
    test("an app that can't read its paired browsers asks to try again, not to pair", () => {
        const failure = describeFailure(
            refusedBy(503, "store_unavailable"),
            "add",
        );

        expect(failure.text).toContain("can't read");
        expect(failure.action).toBe("retry");
    });

    test("another 503 keeps its own words", () => {
        expect(
            describeFailure(refusedBy(503, "unavailable"), "add").text,
        ).toContain("can't take this kind of link");
    });

    test("an app that does not know this browser says so, with no button that loops", () => {
        const failure = describeFailure(refusedBy(401, "unpaired"), "add");

        expect(failure.text).toContain("does not know");
        expect(failure.action).toBeUndefined();
    });

    test("something that could not prove itself is told, and can be tried again", () => {
        const failure = describeFailure(refusedBy(401, "unverified"), "add");

        expect(failure.text).toContain("prove");
        expect(failure.action).toBe("retry");
    });

    test("a session that stayed lost after the one hello is asked to be tried again", () => {
        for (const error of [
            "session_expired",
            "unauthorized",
            "replay",
        ]) {
            const failure = describeFailure(refusedBy(401, error), "confirm");

            expect(failure.text).toContain("signature");
            expect(failure.action).toBe("retry");
        }
    });

    test("another 401 is a plain problem", () => {
        expect(
            describeFailure(refusedBy(401, "whatever"), "add").text,
        ).toContain("had a problem");
    });

    test("none of it repeats what the app said", () => {
        const failure = describeFailure(
            refusedBy(401, "unpaired", "secret: abc123"),
            "add",
        );

        expect(failure.text).not.toContain("abc123");
    });
});

describe("formatSize", () => {
    test("says unknown size when the server did not tell", () => {
        expect(formatSize(null)).toBe("unknown size");
    });

    test("uses the largest unit that keeps the number small", () => {
        expect(formatSize(0)).toBe("0 B");
        expect(formatSize(1023)).toBe("1023 B");
        expect(formatSize(5000)).toBe("4.9 KiB");
        expect(formatSize(4_400_000)).toBe("4.2 MiB");
        expect(formatSize(3 * 1024 ** 3)).toBe("3.0 GiB");
    });
});
