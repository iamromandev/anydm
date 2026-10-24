import { describe, expect, test } from "bun:test";
import contract from "../contract/bridge.json";
import { MAX_BODY, buildAddBody, checkUrl } from "./request";

describe("buildAddBody", () => {
    for (const example of contract.requests) {
        test(`builds the body of "${example.name}" in the contract`, () => {
            const built = buildAddBody(example.input);

            expect(built.body).toEqual(example.body as typeof built.body);
            expect(built.droppedCookies).toBe(false);
        });
    }

    test("sends only the User-Agent as a header, never a login", () => {
        const { body } = buildAddBody({
            url: "https://a.test/f.zip",
            userAgent: "UA/1",
            cookies: [],
        });

        expect(Object.keys(body.headers!)).toEqual([
            "User-Agent",
        ]);
        expect(JSON.stringify(body).toLowerCase()).not.toContain(
            "authorization",
        );
    });

    test("keeps the value of a cookie as the browser has it", () => {
        const { body } = buildAddBody({
            url: "https://a.test/f",
            userAgent: "UA",
            cookies: [
                { name: "a", value: "b;c=d" },
                { name: "ü", value: "ß" },
            ],
        });

        expect(body.cookies).toBe("a=b;c=d; ü=ß");
    });

    test("drops the cookies, and says so, when the body would pass the cap", () => {
        const big = "x".repeat(MAX_BODY);
        const built = buildAddBody({
            url: "https://a.test/f",
            userAgent: "UA",
            cookies: [
                { name: "big", value: big },
            ],
        });

        expect(built.droppedCookies).toBe(true);
        expect(built.body.cookies).toBeUndefined();
        expect(JSON.stringify(built.body).length).toBeLessThan(MAX_BODY);
    });

    test("counts bytes, not characters, against the cap", () => {
        const wide = "ü".repeat(MAX_BODY / 2 - 20);
        const built = buildAddBody({
            url: "https://a.test/f",
            userAgent: "UA",
            cookies: [
                { name: "w", value: wide },
            ],
        });

        expect(built.droppedCookies).toBe(true);
    });
});

describe("checkUrl", () => {
    test("takes web, FTP and torrent addresses", () => {
        for (const url of [
            "https://a.test/f.zip",
            "http://a.test/f",
            "ftp://a.test/f",
            "sftp://a.test/f",
            "magnet:?xt=urn:btih:abc",
        ]) {
            expect(checkUrl(url)).toBe("ok");
        }
    });

    test("refuses what anydm can't take or the page shouldn't hand over", () => {
        expect(checkUrl("javascript:alert(1)")).toBe("unsupported");
        expect(checkUrl("data:text/plain,hello")).toBe("unsupported");
        expect(checkUrl("blob:https://a.test/1234")).toBe("unsupported");
        expect(checkUrl("file:///etc/passwd")).toBe("unsupported");
        expect(checkUrl("chrome://settings")).toBe("unsupported");
        expect(checkUrl("not a url")).toBe("unsupported");
        expect(checkUrl("")).toBe("unsupported");
    });

    test("refuses an address that is too long", () => {
        expect(checkUrl("https://a.test/" + "x".repeat(9000))).toBe("too-long");
    });
});
