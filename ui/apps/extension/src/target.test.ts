import { describe, expect, test } from "bun:test";
import { parseTarget, popupPath, targetOf } from "./target";

describe("targetOf", () => {
    test("takes the link of a link", () => {
        expect(
            targetOf(
                { linkUrl: "https://a.test/f.zip", pageUrl: "https://a.test/" },
                { id: 3 },
            ),
        ).toEqual({
            url: "https://a.test/f.zip",
            pageUrl: "https://a.test/",
            tabId: 3,
        });
    });

    test("takes the source of an image, video or audio", () => {
        expect(
            targetOf(
                { srcUrl: "https://a.test/v.mp4", pageUrl: "https://a.test/" },
                { id: 3 },
            )?.url,
        ).toBe("https://a.test/v.mp4");
    });

    test("prefers the link when the image is a link", () => {
        expect(
            targetOf(
                {
                    linkUrl: "https://a.test/big.png",
                    srcUrl: "https://a.test/small.png",
                },
                undefined,
            )?.url,
        ).toBe("https://a.test/big.png");
    });

    test("has nothing to take from a click on neither", () => {
        expect(
            targetOf({ pageUrl: "https://a.test/" }, { id: 1 }),
        ).toBeUndefined();
    });
});

describe("popupPath and parseTarget", () => {
    test("a target survives the trip through an address, whatever is in it", () => {
        const target = {
            url: "https://a.test/f?x=1&y=é#frag&z",
            pageUrl: "https://a.test/page?q=a b",
            tabId: 12,
        };

        const path = popupPath(target);

        expect(path.startsWith("popup.html?")).toBe(true);
        expect(parseTarget(path.slice(path.indexOf("?")))).toEqual(target);
    });

    test("leaves out what is not known", () => {
        const path = popupPath({ url: "https://a.test/f" });

        expect(parseTarget(path.slice(path.indexOf("?")))).toEqual({
            url: "https://a.test/f",
        });
    });

    test("is no target without an address", () => {
        expect(parseTarget("")).toBeUndefined();
        expect(parseTarget("?page=https%3A%2F%2Fa.test")).toBeUndefined();
    });

    test("ignores a tab that is not a number", () => {
        expect(parseTarget("?url=https%3A%2F%2Fa.test%2Ff&tab=")).toEqual({
            url: "https://a.test/f",
        });
        expect(parseTarget("?url=https%3A%2F%2Fa.test%2Ff&tab=abc")).toEqual({
            url: "https://a.test/f",
        });
    });
});
