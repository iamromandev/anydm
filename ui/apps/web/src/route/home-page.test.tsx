import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { HomePage } from "./home-page";

describe("HomePage render", () => {
    test("the first paint is the whole shell with an empty list", () => {
        const html = renderToStaticMarkup(<HomePage />);

        expect(html).toContain("app-shell");
        expect(html).toContain("top-toolbar");
        expect(html).toContain("sidebar");
        expect(html).toContain("hero-input");
        // Nothing has loaded yet: the empty list offers the ways to start.
        expect(html).toContain("start-doors");
        // Dialogs that are closed draw nothing.
        expect(html).not.toContain("settings-overlay");
        expect(html).not.toContain("player-modal-overlay");
    });
});
