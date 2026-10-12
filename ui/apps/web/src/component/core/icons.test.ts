import { describe, expect, test } from "bun:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import * as lucide from "lucide-react";
import * as icons from "./icons";

// A React component is a function, or a forwardRef/memo object.
const isComponent = (value: unknown) =>
    typeof value === "function" ||
    (typeof value === "object" && value !== null && "$$typeof" in value);

describe("icons facade", () => {
    const entries = Object.entries(icons);

    test("exports every icon the app imports", () => {
        expect(entries.length).toBeGreaterThanOrEqual(54);
    });

    test("every export is a React component", () => {
        const broken = entries
            .filter(([, value]) => !isComponent(value))
            .map(([name]) => name);
        expect(broken).toEqual([]);
    });
});

describe("icons facade rendering", () => {
    test("a Lucide icon renders an svg carrying the props it is given", () => {
        const html = renderToStaticMarkup(
            createElement(icons.LuPlay, { width: "16", height: "16" }),
        );
        expect(html).toContain("<svg");
        expect(html).toContain('width="16"');
    });

    test("a brand icon renders an svg", () => {
        expect(
            renderToStaticMarkup(createElement(icons.SiYoutube, { size: 16 })),
        ).toContain("<svg");
    });

    test("Lucide exports are lucide-react's own components", () => {
        expect(icons.LuDownload).toBe(lucide.Download);
    });
});
