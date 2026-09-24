import { describe, expect, it } from "bun:test";

import { ApiError } from "./envelope";
import { addLink, choosePreset, lookupLink, siteName, toPreview } from "./site";

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
            "unsupported_operation",
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

    it("refuses a page with nothing downloadable instead of guessing", async () => {
        const { post } = fakePost({ "/extract": { ...VIMEO, presets: [] } });

        await expect(
            addLink("https://vimeo.com/1", "best", post),
        ).rejects.toThrow();
    });
});
