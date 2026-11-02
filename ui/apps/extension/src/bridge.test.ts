import { describe, expect, spyOn, test } from "bun:test";
import contract from "../contract/bridge.json";
import {
    CALL_MS,
    HANDSHAKE_MS,
    PORTS,
    addLink,
    confirmLink,
    findBridge,
    findBridges,
    postText,
    type Fetch,
} from "./bridge";
import { pair, sayHello } from "./pairing";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });

const portOf = (url: string) => Number(new URL(url).port);

// A machine where `answers` says what each port does; the rest refuse the connection.
function machine(
    answers: Record<number, () => Promise<Response> | Response>,
    seen: number[] = [],
): Fetch {
    return async (url, init) => {
        const port = portOf(url);
        seen.push(port);
        const answer = answers[port];
        if (!answer) throw new TypeError("connection refused");
        const hang = answer as unknown as { hangs?: boolean };
        if (hang.hangs) {
            return new Promise((_, reject) => {
                init?.signal?.addEventListener("abort", () =>
                    reject(new DOMException("aborted", "AbortError")),
                );
            });
        }
        return answer();
    };
}

const hanging = Object.assign(() => new Response(), { hangs: true });
const anydm = () => json(200, contract.ping);
const forbidden = () => json(403, contract.refusals[0]!.body);

describe("findBridge", () => {
    test("scans 18400-18409", () => {
        expect([
            ...PORTS,
        ]).toEqual([
            18400,
            18401,
            18402,
            18403,
            18404,
            18405,
            18406,
            18407,
            18408,
            18409,
        ]);
    });

    test("finds anydm on the port that answers as anydm", async () => {
        const found = await findBridge(machine({ 18402: anydm }));

        expect(found).toEqual({ port: 18402, allowed: true });
    });

    test("asks only 127.0.0.1, only in the range", async () => {
        const urls: string[] = [];
        await findBridge(async (url) => {
            urls.push(url);
            throw new TypeError("refused");
        });

        expect(urls.length).toBe(10);
        for (const url of urls) {
            expect(url).toMatch(/^http:\/\/127\.0\.0\.1:184\d\d\/ping$/);
        }
    });

    test("skips a program on a port that does not answer as anydm", async () => {
        const found = await findBridge(
            machine({
                18400: () => json(200, { app: "something-else" }),
                18401: () => new Response("<html>", { status: 200 }),
                18402: () => json(500, { error: "x" }),
                18403: anydm,
            }),
        );

        expect(found).toEqual({ port: 18403, allowed: true });
    });

    test("tells anydm that refuses this browser from one that does not", async () => {
        const found = await findBridge(machine({ 18405: forbidden }));

        expect(found).toEqual({ port: 18405, allowed: false });
    });

    test("prefers a bridge that allows the browser", async () => {
        const found = await findBridge(
            machine({ 18400: forbidden, 18401: anydm }),
        );

        expect(found).toEqual({ port: 18401, allowed: true });
    });

    test("prefers the port that worked last when several are anydm", async () => {
        const found = await findBridge(
            machine({ 18400: anydm, 18404: anydm }),
            18404,
        );

        expect(found?.port).toBe(18404);
    });

    test("finds nothing when nothing answers", async () => {
        expect(await findBridge(machine({}))).toBeNull();
    });

    test("gives up on a port that hangs, within the time it is given", async () => {
        const started = Date.now();
        const found = await findBridge(
            machine({ 18400: hanging, 18401: anydm }),
            undefined,
            { timeoutMs: 60 },
        );

        expect(found?.port).toBe(18401);
        expect(Date.now() - started).toBeLessThan(1000);
    });

    test("finds nothing when every port hangs, within the time it is given", async () => {
        const hangs = Object.fromEntries(
            [
                ...PORTS,
            ].map((p) => [
                p,
                hanging,
            ]),
        );
        const started = Date.now();

        expect(
            await findBridge(machine(hangs), undefined, { timeoutMs: 60 }),
        ).toBeNull();
        expect(Date.now() - started).toBeLessThan(1000);
    });
});

describe("findBridges", () => {
    test("lists every port that answers, the ones that answer this browser first", async () => {
        const found = await findBridges(
            machine({ 18400: forbidden, 18402: anydm, 18405: anydm }),
        );

        expect(found).toEqual([
            { port: 18402, allowed: true },
            { port: 18405, allowed: true },
            { port: 18400, allowed: false },
        ]);
    });

    test("puts the port that worked last first among the ones that answer", async () => {
        const found = await findBridges(
            machine({ 18400: anydm, 18404: anydm, 18406: anydm }),
            18404,
        );

        expect(found.map((f) => f.port)).toEqual([
            18404,
            18400,
            18406,
        ]);
    });

    test("is empty when nothing answers, and findBridge agrees with its first", async () => {
        expect(await findBridges(machine({}))).toEqual([]);
        expect(
            await findBridge(machine({ 18403: anydm, 18401: anydm })),
        ).toEqual({ port: 18401, allowed: true });
    });
});

describe("signed calls", () => {
    const body = contract.requests[0]!.body;

    test("the signer sees the path and the exact text that is sent, and its headers go with it", async () => {
        let seen: { url: string; init?: RequestInit } | undefined;
        const signed: { path: string; text: string }[] = [];
        await addLink(
            async (url, init) => {
                seen = { url, init };
                return json(200, contract.replies[0]!.body);
            },
            18407,
            body,
            async (path, text) => {
                signed.push({ path, text });
                return { "X-Anydm-Id": "a", "X-Anydm-Counter": "1" };
            },
        );

        expect(signed).toEqual([
            { path: "/add", text: seen!.init!.body as string },
        ]);
        expect(seen!.init!.headers).toEqual({
            "Content-Type": "application/json",
            "X-Anydm-Id": "a",
            "X-Anydm-Counter": "1",
        });
    });

    test("a confirm is signed over its own path and body", async () => {
        const signed: { path: string; text: string }[] = [];
        await confirmLink(
            async () => json(200, contract.replies[6]!.body),
            18407,
            "tok",
            async (path, text) => {
                signed.push({ path, text });
                return {};
            },
        );

        expect(signed).toEqual([
            { path: "/confirm", text: JSON.stringify({ token: "tok" }) },
        ]);
    });

    test("a signer that fails sends nothing and is unreachable, not a crash", async () => {
        let sent = false;
        const outcome = await addLink(
            async () => {
                sent = true;
                return json(200, {});
            },
            18407,
            body,
            async () => {
                throw new Error("no key");
            },
        );

        expect(sent).toBe(false);
        expect(outcome).toEqual({ kind: "unreachable" });
    });
});

describe("addLink and confirmLink read the answers in the contract", () => {
    const body = contract.requests[0]!.body;

    for (const reply of contract.replies) {
        test(`"${reply.name}"`, async () => {
            const fetchFn: Fetch = async () => json(reply.status, reply.body);
            const outcome =
                reply.path === "/add"
                    ? await addLink(fetchFn, 18400, body)
                    : await confirmLink(fetchFn, 18400, "x");

            if (reply.status === 200 && reply.path === "/add") {
                const expected = reply.body as Record<string, unknown>;
                expect(outcome).toEqual({
                    kind: "probed",
                    token: expected.token as string,
                    name: expected.name as string | null,
                    size: expected.size as number | null,
                    type: expected.kind as string,
                    rangesSupported: expected.rangesSupported as boolean,
                    duplicate: expected.duplicate as boolean,
                });
            } else if (reply.status === 200) {
                expect(outcome).toEqual({
                    kind: "added",
                    id: (reply.body as { id: number }).id,
                });
            } else {
                const expected = reply.body as {
                    error: string;
                    message: string;
                };
                expect(outcome).toEqual({
                    kind: "refused",
                    status: reply.status,
                    error: expected.error,
                    message: expected.message,
                });
            }
        });
    }

    test("posts JSON to the port it was given and nothing else", async () => {
        let seen: { url: string; init?: RequestInit } | undefined;
        await addLink(
            async (url, init) => {
                seen = { url, init };
                return json(200, contract.replies[0]!.body);
            },
            18407,
            body,
        );

        expect(seen!.url).toBe("http://127.0.0.1:18407/add");
        expect(seen!.init!.method).toBe("POST");
        expect(
            (seen!.init!.headers as Record<string, string>)["Content-Type"],
        ).toBe("application/json");
        expect(seen!.init!.credentials).toBe("omit");
        expect(JSON.parse(seen!.init!.body as string)).toEqual(body);
    });

    test("is unreachable when the connection fails", async () => {
        const failing: Fetch = async () => {
            throw new TypeError("refused");
        };

        expect(await addLink(failing, 18400, body)).toEqual({
            kind: "unreachable",
        });
        expect(await confirmLink(failing, 18400, "x")).toEqual({
            kind: "unreachable",
        });
    });

    test("is refused, not crashed, by an answer that is not the bridge's JSON", async () => {
        const odd: Fetch = async () => new Response("oops", { status: 502 });

        expect(await addLink(odd, 18400, body)).toEqual({
            kind: "refused",
            status: 502,
            error: "unknown",
            message: "",
        });
    });
});

describe("how long a call gets", () => {
    // Records the time each request was given; the real timer is kept.
    function timeouts() {
        const given: number[] = [];
        const real = AbortSignal.timeout.bind(AbortSignal);
        const spy = spyOn(AbortSignal, "timeout").mockImplementation((ms) => {
            given.push(ms);
            return real(ms);
        });
        return { given, restore: () => spy.mockRestore() };
    }
    const answers: Fetch = async () => json(200, {});

    test("/hello and /pair get the short deadline, /add and /confirm the long one", async () => {
        const t = timeouts();
        try {
            for (const path of [
                "/hello",
                "/pair",
                "/add",
                "/confirm",
            ]) {
                await postText(answers, 18400, path, "{}");
            }
        } finally {
            t.restore();
        }

        expect(t.given).toEqual([
            HANDSHAKE_MS,
            HANDSHAKE_MS,
            CALL_MS,
            CALL_MS,
        ]);
        expect(HANDSHAKE_MS).toBeLessThanOrEqual(4_000);
        expect(CALL_MS).toBeGreaterThan(30_000);
    });

    test("a program that stalls on /hello or /pair is given up on at the short deadline", async () => {
        // Time runs a hundred times faster for deadlines, so the test shows which deadline was used without waiting for it.
        const real = AbortSignal.timeout.bind(AbortSignal);
        const spy = spyOn(AbortSignal, "timeout").mockImplementation((ms) =>
            real(ms / 100),
        );
        // Answers /ping like anydm, then says nothing to anything else until it is given up on.
        const stalls: Fetch = (url, init) => {
            if (portOf(url) !== 18400) throw new TypeError("refused");
            if (new URL(url).pathname === "/ping") {
                return Promise.resolve(anydm());
            }
            return new Promise((_, reject) =>
                init?.signal?.addEventListener("abort", () =>
                    reject(new DOMException("aborted", "AbortError")),
                ),
            );
        };
        const pairing = {
            id: "1".repeat(32),
            secret: "2".repeat(64),
        };
        const started = Date.now();
        try {
            expect(await sayHello(stalls, 18400, pairing)).toEqual({
                kind: "unreachable",
            });
            expect(
                await pair(
                    { fetch: stalls, storage: undefined as never },
                    "7K3M9QX2VBHT4N6R",
                    18400,
                ),
            ).toEqual({ kind: "none" });
        } finally {
            spy.mockRestore();
        }

        // 2 x 40 ms; at the long deadline this would be 2 x 350 ms.
        expect(Date.now() - started).toBeLessThan(500);
    });
});

describe("a redirect is never followed", () => {
    const body = contract.requests[0]!.body;

    test("every request the bridge makes says so", async () => {
        const inits: (RequestInit | undefined)[] = [];
        const records: Fetch = async (url, init) => {
            inits.push(init);
            return new URL(url).pathname === "/ping"
                ? json(200, contract.ping)
                : json(200, {});
        };
        const pairing = { id: "1".repeat(32), secret: "2".repeat(64) };

        await findBridges(records);
        await addLink(records, 18400, body);
        await confirmLink(records, 18400, "t");
        await sayHello(records, 18400, pairing);
        await pair(
            {
                fetch: records,
                storage: {
                    get: async () => ({}),
                    set: async () => {},
                    remove: async () => {},
                },
            },
            "7K3M9QX2VBHT4N6R",
            18400,
        );

        // Pings, an add, a confirm, a hello and the posts of a pair: all of them.
        expect(inits.length).toBeGreaterThanOrEqual(24);
        for (const init of inits) expect(init?.redirect).toBe("error");
    });
});
