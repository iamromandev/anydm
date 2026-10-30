import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { HomePage } from "./home-page";

describe("HomePage", () => {
    test("says hello", () => {
        expect(renderToStaticMarkup(<HomePage />)).toContain("Hello");
    });
});
