import { describe, expect, it } from "bun:test";

import {
    cueKey,
    cueSegmentsAt,
    normalizeSubtitleTracks,
    parseVtt,
    pickSubtitleTrack,
    type SubtitleTrack,
    subtitleToToggleOn,
    subtitleTrackLabel,
} from "./subtitles";

const track = (
    index: number,
    fields: Partial<SubtitleTrack> = {},
): SubtitleTrack => ({
    index,
    language: null,
    title: null,
    codec: "subrip",
    default: false,
    forced: false,
    text: true,
    external: false,
    ...fields,
});

// #93's movie.mkv: SubRip, styled ASS, a forced SubRip; plus a picture track.
const MOVIE = [
    track(0, { language: "eng", title: "English" }),
    track(1, { language: "eng", title: "English (styled)", codec: "ass" }),
    track(2, { language: "eng", title: "English (forced)", forced: true }),
    track(3, {
        language: "fre",
        codec: "hdmv_pgs_subtitle",
        text: false,
    }),
];

describe("subtitle tracks (#100)", () => {
    it("reads the API's tracks, the envelope's missing fields as null", () => {
        expect(
            normalizeSubtitleTracks([
                { index: 0, codec: "subrip", text: true, forced: true },
                { index: 1 },
            ]),
        ).toEqual([
            track(0, { forced: true }),
            track(1, { codec: null, text: false }),
        ]);
        expect(normalizeSubtitleTracks(undefined)).toEqual([]);
    });

    it("reads which tracks are subtitle files beside the video (#101)", () => {
        const [
            file,
        ] = normalizeSubtitleTracks([
            {
                index: 3,
                language: "en",
                title: "Movie.en.srt",
                codec: "subrip",
                text: true,
                external: true,
            },
        ]);
        expect(file.external).toBe(true);
        expect(subtitleTrackLabel(file)).toBe("English · Movie.en.srt");
    });

    it("picks a subtitle file like any other track", () => {
        const file = track(3, {
            language: "fr",
            title: "Movie.fr.srt",
            external: true,
        });
        expect(
            pickSubtitleTrack(
                [
                    ...MOVIE,
                    file,
                ],
                "fr",
            ),
        ).toBe(3);
    });

    it("shows the preferred language, a full track before a forced one", () => {
        expect(pickSubtitleTrack(MOVIE, "en")).toBe(0);
        const forcedFirst = [
            MOVIE[2],
            MOVIE[0],
        ];
        expect(pickSubtitleTrack(forcedFirst, "en")).toBe(0);
    });

    it("turns a forced track on by itself, and otherwise stays off", () => {
        expect(pickSubtitleTrack(MOVIE, null)).toBe(2);
        expect(pickSubtitleTrack(MOVIE, "de")).toBe(2);
        expect(
            pickSubtitleTrack(
                [
                    MOVIE[0],
                    MOVIE[1],
                ],
                null,
            ),
        ).toBeNull();
    });

    it("never picks a track that can't be shown", () => {
        expect(pickSubtitleTrack(MOVIE, "fr")).toBe(2);
        expect(
            pickSubtitleTrack(
                [
                    MOVIE[3],
                ],
                "fr",
            ),
        ).toBeNull();
    });

    it("brings back the last track on C, else the preferred, else the first", () => {
        expect(subtitleToToggleOn(MOVIE, 1, null)).toBe(1);
        expect(subtitleToToggleOn(MOVIE, null, "en")).toBe(0);
        expect(
            subtitleToToggleOn(
                [
                    MOVIE[0],
                    MOVIE[1],
                ],
                null,
                null,
            ),
        ).toBe(0);
        expect(subtitleToToggleOn(MOVIE, 3, null)).toBe(2);
        expect(
            subtitleToToggleOn(
                [
                    MOVIE[3],
                ],
                null,
                null,
            ),
        ).toBeNull();
    });

    it("names a track by its language and title, and says which can't be shown", () => {
        expect(subtitleTrackLabel(MOVIE[0], MOVIE)).toBe("English · English");
        expect(subtitleTrackLabel(MOVIE[2], MOVIE)).toBe(
            "English · English (forced) · forced",
        );
        expect(subtitleTrackLabel(MOVIE[3], MOVIE)).toBe(
            "French (can't be shown)",
        );
        expect(subtitleTrackLabel(track(4))).toBe("Track 5");
    });
});

describe("cues", () => {
    const segment = [
        "WEBVTT",
        "",
        "00:00:05.000 --> 00:00:07.000",
        "cue 2 across 6",
        "",
        "00:09.000 --> 00:10.500 align:start",
        "<b>cue 3</b>",
        "second line",
        "",
        "NOTE a comment",
        "",
    ].join("\n");

    it("reads each cue's times and text, settings left out", () => {
        expect(parseVtt(segment)).toEqual([
            { start: 5, end: 7, text: "cue 2 across 6" },
            { start: 9, end: 10.5, text: "<b>cue 3</b>\nsecond line" },
        ]);
        expect(
            parseVtt("WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nhi"),
        ).toEqual([
            { start: 1, end: 2, text: "hi" },
        ]);
        expect(parseVtt("WEBVTT\n")).toEqual([]);
    });

    it("knows a cue across a seam when the next segment brings it again", () => {
        const [
            first,
        ] = parseVtt(segment);
        const [
            again,
        ] = parseVtt(
            "WEBVTT\n\n00:00:05.000 --> 00:00:07.000\ncue 2 across 6\n",
        );
        expect(cueKey(first)).toBe(cueKey(again));
        expect(cueKey(first)).not.toBe(
            cueKey({ ...first, text: "something else" }),
        );
    });

    it("wants the segment playing and the next, none past the end", () => {
        expect(cueSegmentsAt(0, 6, 20)).toEqual([
            0,
            1,
        ]);
        expect(cueSegmentsAt(6.5, 6, 20)).toEqual([
            1,
            2,
        ]);
        expect(cueSegmentsAt(19, 6, 20)).toEqual([
            3,
        ]);
        expect(cueSegmentsAt(50, 6, 20)).toEqual([
            3,
        ]);
        expect(cueSegmentsAt(3, 6, Number.NaN)).toEqual([]);
    });
});
