import { describe, expect, it } from "bun:test";

import {
    type AudioTrack,
    audioTrackLabel,
    languageKey,
    needsAudioSession,
    normalizeAudioTracks,
    pickAudioTrack,
    segmentAt,
} from "./audio";

const track = (
    index: number,
    fields: Partial<AudioTrack> = {},
): AudioTrack => ({
    index,
    language: null,
    title: null,
    channels: null,
    codec: null,
    default: false,
    ...fields,
});

// #93's movie.mkv: English first, the Russian one marked default.
const MOVIE = [
    track(0, { language: "eng", channels: 2 }),
    track(1, { language: "rus", channels: 6, default: true }),
];

describe("audio tracks (#99)", () => {
    it("reads the API's tracks, the envelope's missing fields as null", () => {
        expect(
            normalizeAudioTracks([
                { index: 0, language: "eng", default: true },
                { index: 1 },
            ]),
        ).toEqual([
            track(0, { language: "eng", default: true }),
            track(1),
        ]);
        expect(normalizeAudioTracks(undefined)).toEqual([]);
    });

    it("folds a file's and a site's language codes together", () => {
        expect(languageKey("eng")).toBe("en");
        expect(languageKey("en-US")).toBe("en");
        expect(languageKey("fre")).toBe("fr");
        expect(languageKey("fra")).toBe("fr");
        expect(languageKey("kat")).toBe("kat");
        expect(languageKey("und")).toBeNull();
        expect(languageKey(null)).toBeNull();
    });

    it("opens with the preferred language, else the marked track, else the first", () => {
        expect(pickAudioTrack(MOVIE, "en")).toBe(0);
        expect(pickAudioTrack(MOVIE, "de")).toBe(1);
        expect(pickAudioTrack(MOVIE, null)).toBe(1);
        expect(
            pickAudioTrack(
                [
                    track(0),
                    track(1),
                ],
                null,
            ),
        ).toBe(0);
        expect(pickAudioTrack([], "en")).toBeNull();
    });

    it("sends a file to a session only when the preference picks a track the browser wouldn't", () => {
        expect(needsAudioSession(MOVIE, "en")).toBe(true);
        expect(needsAudioSession(MOVIE, "ru")).toBe(false);
        expect(needsAudioSession(MOVIE, null)).toBe(false);
        expect(needsAudioSession([], "en")).toBe(false);
    });

    it("names a track by its language, title and channels", () => {
        expect(audioTrackLabel(MOVIE[0], MOVIE)).toBe("English · stereo");
        expect(audioTrackLabel(MOVIE[1], MOVIE)).toBe("Russian · 5.1");
        expect(
            audioTrackLabel(
                track(0, { language: "spa", title: "Latino", channels: 3 }),
            ),
        ).toBe("Spanish · Latino · 3 ch");
    });

    it("tells apart tracks that would read the same, and names one with nothing to say", () => {
        const twins = [
            track(0, { language: "en" }),
            track(1, { language: "en-US" }),
        ];
        expect(audioTrackLabel(twins[0], twins)).toBe("English (1)");
        expect(audioTrackLabel(twins[1], twins)).toBe("English (2)");
        expect(audioTrackLabel(track(2))).toBe("Track 3");
    });
});

describe("segmentAt", () => {
    const playlist = [
        "#EXTM3U",
        "#EXT-X-TARGETDURATION:6",
        "#EXTINF:6.000,",
        "segment_0.ts",
        "#EXT-X-DISCONTINUITY",
        "#EXTINF:6.000,",
        "segment_1.ts",
        "#EXT-X-DISCONTINUITY",
        "#EXTINF:2.500,",
        "segment_2.ts",
        "#EXT-X-ENDLIST",
    ].join("\n");

    it("finds the segment that holds a time", () => {
        expect(segmentAt(playlist, 0)).toBe(0);
        expect(segmentAt(playlist, 5.99)).toBe(0);
        expect(segmentAt(playlist, 6)).toBe(1);
        expect(segmentAt(playlist, 13)).toBe(2);
    });

    it("takes the last past the end, and the first of an empty playlist", () => {
        expect(segmentAt(playlist, 99)).toBe(2);
        expect(segmentAt("#EXTM3U", 3)).toBe(0);
    });
});
