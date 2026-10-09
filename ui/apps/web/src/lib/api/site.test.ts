import { describe, expect, it } from "bun:test";

import { ApiError } from "./envelope";
import {
    addLink,
    choosePreset,
    lookupLink,
    playlistMeta,
    siteName,
    toPlaylistPreview,
    toPreview,
} from "./site";

const VIMEO = {
    extractor: "Vimeo",
    id: "75629013",
    title: "Key & Peele",
    uploader: "Comedy Central",
    duration: 187,
    thumbnail: "https://img.test/v.jpg",
    webpage_url: "https://vimeo.com/75629013",
    formats: [],
    presets: [
        "best",
        "1080",
        "720",
        "480",
    ],
};

type Call = { path: string; body: unknown };

/** A stand-in for ``postApi``: answers from a table, records what was asked. */
function fakePost(answers: Record<string, unknown>) {
    const calls: Call[] = [];
    const post = async <T>(path: string, body: unknown): Promise<T> => {
        calls.push({ path, body });
        const answer = answers[path];
        if (answer instanceof Error) throw answer;
        return answer as T;
    };
    return { post, calls };
}

const unsupported = new ApiError(
    "No site supports this link",
    400,
    "unsupported_url",
);

describe("siteName", () => {
    it("spells the well-known sites the way they spell themselves", () => {
        expect(siteName("Youtube")).toBe("YouTube");
        expect(siteName("Soundcloud")).toBe("SoundCloud");
        expect(siteName("Twitter")).toBe("X");
        expect(siteName("TwitchVod")).toBe("Twitch");
    });

    it("leaves any other site as yt-dlp names it", () => {
        expect(siteName("Vimeo")).toBe("Vimeo");
        expect(siteName("Bandcamp")).toBe("Bandcamp");
    });

    it("is empty when there is no site", () => {
        expect(siteName(undefined)).toBe("");
        expect(siteName(null)).toBe("");
    });
});

describe("choosePreset", () => {
    it("keeps the preferred preset when the page offers it", () => {
        expect(
            choosePreset(
                [
                    "best",
                    "1080",
                    "720",
                ],
                "720",
            ),
        ).toBe("720");
    });

    it("falls back to the first offered when it does not", () => {
        expect(
            choosePreset(
                [
                    "mp3",
                ],
                "1080",
            ),
        ).toBe("mp3");
    });

    it("is null when nothing is offered", () => {
        expect(choosePreset([], "best")).toBeNull();
    });
});

describe("toPreview", () => {
    it("reads the extract answer, with the site's display name", () => {
        expect(toPreview(VIMEO)).toEqual({
            extractor: "Vimeo",
            site: "Vimeo",
            id: "75629013",
            title: "Key & Peele",
            uploader: "Comedy Central",
            duration: 187,
            thumbnail: "https://img.test/v.jpg",
            presets: [
                "best",
                "1080",
                "720",
                "480",
            ],
            playlistUrl: null,
        });
    });

    it("drops presets it does not know", () => {
        expect(
            toPreview({
                ...VIMEO,
                presets: [
                    "best",
                    "8k",
                ],
            }).presets,
        ).toEqual([
            "best",
        ]);
    });
});

describe("lookupLink", () => {
    it("is a site preview when the API recognises the page", async () => {
        const { post } = fakePost({ "/extract": VIMEO });

        const found = await lookupLink("https://vimeo.com/75629013", post);

        expect(found.kind).toBe("site");
        expect(found.kind === "site" && found.preview.title).toBe(
            "Key & Peele",
        );
    });

    it("is a file when no site supports the link", async () => {
        const { post } = fakePost({ "/extract": unsupported });

        expect(await lookupLink("https://files.test/a.bin", post)).toEqual({
            kind: "file",
        });
    });

    it("lets any other refusal through, to be shown", async () => {
        const live = new ApiError(
            "Live streams cannot be downloaded",
            422,
            "live_not_supported",
        );
        const { post } = fakePost({ "/extract": live });

        await expect(lookupLink("https://twitch.tv/x", post)).rejects.toBe(
            live,
        );
    });
});

describe("addLink", () => {
    it("downloads a page from its site, at the preferred preset", async () => {
        const { post, calls } = fakePost({
            "/extract": VIMEO,
            "/download/media": {},
        });

        await addLink("https://vimeo.com/75629013", "720", post);

        expect(calls.at(-1)).toEqual({
            path: "/download/media",
            body: { url: "https://vimeo.com/75629013", preset: "720" },
        });
    });

    it("falls back to a direct download for a link no site supports", async () => {
        const { post, calls } = fakePost({
            "/extract": unsupported,
            "/download/url": {},
        });

        await addLink("https://files.test/a.bin", "best", post);

        expect(calls.map((c) => c.path)).toEqual([
            "/extract",
            "/download/url",
        ]);
        expect(calls[1].body).toEqual({ url: "https://files.test/a.bin" });
    });

    it("asks for a second copy when told to", async () => {
        const page = fakePost({ "/extract": VIMEO, "/download/media": {} });
        await addLink("https://vimeo.com/1", "720", page.post, true);
        expect(page.calls.at(-1)?.body).toEqual({
            url: "https://vimeo.com/1",
            preset: "720",
            allow_duplicate: true,
        });

        const file = fakePost({
            "/extract": unsupported,
            "/download/url": {},
        });
        await addLink("https://files.test/a", "best", file.post, true);
        expect(file.calls.at(-1)?.body).toEqual({
            url: "https://files.test/a",
            allow_duplicate: true,
        });
    });

    it("refuses a page with nothing downloadable instead of guessing", async () => {
        const { post } = fakePost({ "/extract": { ...VIMEO, presets: [] } });

        await expect(
            addLink("https://vimeo.com/1", "best", post),
        ).rejects.toThrow();
    });
});

const PLAYLIST = {
    type: "playlist",
    extractor: "YoutubeTab",
    id: "PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC",
    title: "29C3: Not my department",
    uploader: "Christiaan008",
    thumbnail: "https://img.test/pl.jpg",
    webpage_url:
        "https://www.youtube.com/playlist?list=PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC",
    count: 96,
    channel_tab: false,
    tabs: [],
    presets: [
        "best",
        "1080",
        "mp3",
    ],
};

describe("lookupLink, for lists", () => {
    it("answers a playlist with its header", async () => {
        const { post } = fakePost({ "/extract": PLAYLIST });

        expect(await lookupLink("https://youtube.test/list", post)).toEqual({
            kind: "playlist",
            preview: {
                extractor: "YoutubeTab",
                site: "YouTube",
                id: PLAYLIST.id,
                title: "29C3: Not my department",
                uploader: "Christiaan008",
                thumbnail: "https://img.test/pl.jpg",
                url: PLAYLIST.webpage_url,
                count: 96,
                channelTab: false,
            },
        });
    });

    it("answers a channel with its tabs", async () => {
        const { post } = fakePost({
            "/extract": {
                ...PLAYLIST,
                type: "channel",
                title: "3Blue1Brown",
                count: undefined,
                tabs: [
                    { name: "Videos", url: "https://y.test/@x/videos" },
                ],
            },
        });

        const found = await lookupLink("https://y.test/@x", post);

        expect(found.kind).toBe("channel");
        if (found.kind !== "channel") throw new Error("not a channel");
        expect(found.tabs).toEqual([
            { name: "Videos", url: "https://y.test/@x/videos" },
        ]);
        expect(found.preview.count).toBeNull();
    });

    it("carries a watch link's playlist on the video's preview", async () => {
        const { post } = fakePost({
            "/extract": {
                ...VIMEO,
                type: "media",
                playlist_url: "https://www.youtube.com/playlist?list=PL1",
            },
        });

        const found = await lookupLink("https://youtube.test/watch", post);

        if (found.kind !== "site") throw new Error("not a site");
        expect(found.preview.playlistUrl).toBe(
            "https://www.youtube.com/playlist?list=PL1",
        );
    });
});

describe("toPlaylistPreview", () => {
    it("falls back to the link that was asked about", () => {
        expect(
            toPlaylistPreview(
                { ...PLAYLIST, webpage_url: "" },
                "https://asked.test",
            ).url,
        ).toBe("https://asked.test");
    });
});

describe("playlistMeta", () => {
    it("says what the list is, how long, and whose", () => {
        const preview = toPlaylistPreview(PLAYLIST, "");
        expect(playlistMeta(preview)).toBe(
            "Playlist · 96 videos · Christiaan008",
        );
        expect(playlistMeta({ ...preview, count: 1 })).toBe(
            "Playlist · 1 video · Christiaan008",
        );
        expect(playlistMeta({ ...preview, count: 1240 })).toBe(
            "Playlist · 1,240 videos · Christiaan008",
        );
    });

    it("calls a channel's tab a channel, with no count until listed", () => {
        const preview = {
            ...toPlaylistPreview(PLAYLIST, ""),
            count: null,
            channelTab: true,
        };
        expect(playlistMeta(preview)).toBe("Channel · Christiaan008");
    });
});

describe("addLink, for lists", () => {
    it("refuses a playlist rather than queueing it as one video", async () => {
        const { post, calls } = fakePost({ "/extract": PLAYLIST });

        await expect(
            addLink("https://youtube.test/list", "best", post),
        ).rejects.toThrow(
            "This link is a playlist: paste it in the add box to choose its videos",
        );
        expect(calls.map((c) => c.path)).toEqual([
            "/extract",
        ]);
    });
});

describe("a category on a link", () => {
    it("is sent with a direct download", async () => {
        const { post, calls } = fakePost({
            "/extract": unsupported,
            "/download/url": {},
        });

        await addLink("https://files.test/a.bin", "best", post, false, "c1");

        expect(calls[1].body).toEqual({
            url: "https://files.test/a.bin",
            category_id: "c1",
        });
    });

    it("is sent with a site download, after the second-copy flag", async () => {
        const { post, calls } = fakePost({
            "/extract": VIMEO,
            "/download/media": {},
        });

        await addLink("https://vimeo.com/1", "720", post, true, "c1");

        expect(calls[1].body).toEqual({
            url: "https://vimeo.com/1",
            preset: "720",
            allow_duplicate: true,
            category_id: "c1",
        });
    });
});
