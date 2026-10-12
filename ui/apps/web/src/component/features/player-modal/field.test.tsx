import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { PlayerModal, type PlayerModalProps } from "./field";

const props: PlayerModalProps = {
    open: true,
    url: "https://example.test/video",
    kind: "site",
    taskId: "",
    fileIndex: null,
    files: [],
    fromTorrent: false,
    positions: [],
    audioLanguage: "",
    subtitleLanguage: "",
    onClose: () => {},
};

describe("PlayerModal render", () => {
    test("open: a dialog with the video and its controls", () => {
        const html = renderToStaticMarkup(<PlayerModal {...props} />);
        expect(html).toContain('role="dialog"');
        expect(html).toContain("<video");
        expect(html).toContain("player-controls");
    });

    test("closed: nothing at all", () => {
        expect(
            renderToStaticMarkup(<PlayerModal {...props} open={false} />),
        ).toBe("");
    });
});
