import { describe, expect, test } from "bun:test";
import { cookiesFor, type CookieApi } from "./cookies";

function api(
    stores: { id: string; tabIds: number[] }[],
    jars: Record<string, { name: string; value: string; extra?: string }[]>,
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

    test("hands over only the name and the value", async () => {
        const cookies = await cookiesFor(
            api([], {
                default: [
                    { name: "a", value: "b", extra: "secure" },
                ],
            }),
            "https://a.test/f",
        );

        expect(cookies).toEqual([
            { name: "a", value: "b" },
        ]);
    });
});
