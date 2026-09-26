import { describe, expect, it } from "bun:test";

import { normalizeStreamSession, normalizeStreamStatusEvent } from "./api";
import {
    defaultQualityLabel,
    fileQualityMenu,
    NO_QUALITY_MENU,
    normalizeQualityMenu,
} from "./quality";

describe("the quality menu (#103)", () => {
    it("reads a site's menu: its heights, its 1080p default, what's playing", () => {
        const menu = normalizeQualityMenu({
            qualities: [
                2160,
                1080,
                720,
            ],
            playing_height: 1080,
            quality_default: "auto",
            default_height: 1080,
        });
        expect(menu).toEqual({
            heights: [
                2160,
                1080,
                720,
            ],
            chosen: null,
            playing: 1080,
            default: "auto",
            defaultHeight: 1080,
        });
        expect(defaultQualityLabel(menu)).toBe("Auto (1080p)");
    });

    it("reads a file's menu, and no menu when there's nothing to offer", () => {
        const menu = normalizeQualityMenu({
            qualities: [
                1080,
                720,
            ],
            quality: 720,
            playing_height: 720,
            quality_default: "original",
            default_height: 2160,
        });
        expect(menu.chosen).toBe(720);
        expect(defaultQualityLabel(menu)).toBe("Original (2160p)");
        expect(normalizeQualityMenu({})).toEqual(NO_QUALITY_MENU);
        expect(normalizeQualityMenu({ qualities: [] })).toEqual(
            NO_QUALITY_MENU,
        );
    });

    it("works out a file's menu from its height, as the API does", () => {
        expect(fileQualityMenu(2160).heights).toEqual([
            1080,
            720,
            480,
        ]);
        expect(fileQualityMenu(1080).heights).toEqual([
            720,
            480,
        ]);
        expect(fileQualityMenu(720)).toMatchObject({
            heights: [
                480,
            ],
            playing: 720,
            default: "original",
        });
        expect(fileQualityMenu(480)).toEqual(NO_QUALITY_MENU);
        expect(fileQualityMenu(null)).toEqual(NO_QUALITY_MENU);
    });

    it("comes with a session, and with a torrent's ready event", () => {
        expect(
            normalizeStreamSession({
                qualities: [
                    480,
                ],
                default_height: 720,
            }).quality.heights,
        ).toEqual([
            480,
        ]);
        expect(normalizeStreamSession({}).quality).toEqual(NO_QUALITY_MENU);
        const ready = normalizeStreamStatusEvent({
            id: "s1",
            status: "ready",
            qualities: [
                720,
            ],
        });
        expect(ready.quality?.heights).toEqual([
            720,
        ]);
        expect(
            "quality" in
                normalizeStreamStatusEvent({ id: "s1", status: "ready" }),
        ).toBe(false);
    });
});
