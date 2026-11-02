import { describe, expect, test } from "bun:test";
import {
    cookiesFor,
    hasSeveralStores,
    withoutStrictCrossSite,
    type CookieApi,
} from "./cookies";

function api(
    stores: { id: string; tabIds: number[] }[],
    jars: Record<
        string,
        { name: string; value: string; extra?: string; sameSite?: string }[]
    >,
    asked: { url: string; storeId?: string }[] = [],
): CookieApi {
    return {
        getAllCookieStores: async () => stores,
        getAll: async (details) => {
            asked.push(details);
            return jars[details.storeId ?? "default"] ?? [];
        },
    };
}

describe("cookiesFor", () => {
    test("reads the cookies of the store the tab is in", async () => {
        const asked: { url: string; storeId?: string }[] = [];
        const cookies = await cookiesFor(
            api(
                [
                    {
                        id: "0",
                        tabIds: [
                            1,
                        ],
                    },
                    {
                        id: "firefox-container-2",
                        tabIds: [
                            7,
                            8,
                        ],
                    },
                ],
                {
                    "0": [
                        { name: "plain", value: "1" },
                    ],
                    "firefox-container-2": [
                        { name: "work", value: "2" },
                    ],
                },
                asked,
            ),
            "https://a.test/f",
            8,
        );

        expect(cookies).toEqual([
            { name: "work", value: "2" },
        ]);
        expect(asked).toEqual([
            { url: "https://a.test/f", storeId: "firefox-container-2" },
        ]);
    });

    test("uses the browser's own store when the tab is in none", async () => {
        const asked: { url: string; storeId?: string }[] = [];
        await cookiesFor(
            api(
                [
                    {
                        id: "0",
                        tabIds: [
                            1,
                        ],
                    },
                ],
                {},
                asked,
            ),
            "https://a.test/f",
            99,
        );
        await cookiesFor(
            api(
                [
                    {
                        id: "0",
                        tabIds: [
                            1,
                        ],
                    },
                ],
                {},
                asked,
            ),
            "https://a.test/f",
        );

        expect(asked).toEqual([
            { url: "https://a.test/f", storeId: undefined },
            { url: "https://a.test/f", storeId: undefined },
        ]);
    });

    test("hands over only the name, the value and the SameSite setting", async () => {
        const cookies = await cookiesFor(
            api([], {
                default: [
                    { name: "a", value: "b", extra: "secure" },
                    {
                        name: "c",
                        value: "d",
                        extra: "secure",
                        sameSite: "strict",
                    },
                ],
            }),
            "https://a.test/f",
        );

        expect(cookies).toEqual([
            { name: "a", value: "b" },
            { name: "c", value: "d", sameSite: "strict" },
        ]);
    });
});

describe("withoutStrictCrossSite", () => {
    const cookies = [
        { name: "s", value: "1", sameSite: "strict" },
        { name: "l", value: "2", sameSite: "lax" },
        { name: "n", value: "3", sameSite: "no_restriction" },
        { name: "u", value: "4", sameSite: "unspecified" },
        { name: "x", value: "5" },
    ];
    const names = (list: { name: string }[]) => list.map((c) => c.name);

    test("drops a Strict cookie when the page was on another host, or there was no page", () => {
        expect(
            names(
                withoutStrictCrossSite(
                    cookies,
                    "https://f.test/a",
                    "https://g.test/",
                ),
            ),
        ).toEqual([
            "l",
            "n",
            "u",
            "x",
        ]);
        expect(
            names(withoutStrictCrossSite(cookies, "https://f.test/a")),
        ).toEqual([
            "l",
            "n",
            "u",
            "x",
        ]);
        expect(
            names(
                withoutStrictCrossSite(
                    cookies,
                    "https://f.test/a",
                    "not a url",
                ),
            ),
        ).toEqual([
            "l",
            "n",
            "u",
            "x",
        ]);
    });

    test("keeps every cookie when the page was on the same host, letters and a trailing dot aside", () => {
        for (const referrer of [
            "https://f.test/page",
            "http://F.TEST:8080/page",
            "https://f.test./page",
        ]) {
            expect(
                names(
                    withoutStrictCrossSite(
                        cookies,
                        "https://f.test/a",
                        referrer,
                    ),
                ),
            ).toEqual([
                "s",
                "l",
                "n",
                "u",
                "x",
            ]);
        }
    });

    test("a subdomain or a parent domain is another host", () => {
        for (const referrer of [
            "https://sub.f.test/",
            "https://test/",
        ]) {
            expect(
                names(
                    withoutStrictCrossSite(
                        cookies,
                        "https://f.test/a",
                        referrer,
                    ),
                ),
            ).not.toContain("s");
        }
    });

    test("reads the setting in any letters", () => {
        expect(
            withoutStrictCrossSite(
                [
                    { name: "s", value: "1", sameSite: "STRICT" },
                ],
                "https://f.test/a",
            ),
        ).toEqual([]);
    });
});

describe("hasSeveralStores", () => {
    const stores = (...ids: { id: string; incognito?: boolean }[]) => ({
        getAllCookieStores: async () => ids.map((s) => ({ tabIds: [], ...s })),
        getAll: async () => [],
    });

    test("Firefox with the default store and a container is several", async () => {
        expect(
            await hasSeveralStores(
                stores(
                    { id: "firefox-default" },
                    { id: "firefox-container-1" },
                ),
                true,
            ),
        ).toBe(true);
    });

    test("Firefox with only the default store is one, and a private store is not counted", async () => {
        expect(
            await hasSeveralStores(stores({ id: "firefox-default" }), true),
        ).toBe(false);
        expect(
            await hasSeveralStores(
                stores(
                    { id: "firefox-default" },
                    { id: "firefox-private", incognito: true },
                ),
                true,
            ),
        ).toBe(false);
        expect(
            await hasSeveralStores(
                stores({ id: "firefox-default" }, { id: "x", incognito: true }),
                true,
            ),
        ).toBe(false);
    });

    test("two containers and a private store are several", async () => {
        expect(
            await hasSeveralStores(
                stores(
                    { id: "firefox-container-1" },
                    { id: "firefox-container-2" },
                    { id: "firefox-private", incognito: true },
                ),
                true,
            ),
        ).toBe(true);
    });

    test("Chrome's stores are never several: its second store is the private window's", async () => {
        expect(
            await hasSeveralStores(stores({ id: "0" }, { id: "1" }), false),
        ).toBe(false);
    });

    test("a browser that can't list its stores throws, which the caller reads as several", async () => {
        const failing = {
            getAllCookieStores: async () => {
                throw new Error("no");
            },
            getAll: async () => [],
        };

        await expect(hasSeveralStores(failing, true)).rejects.toThrow();
    });
});
