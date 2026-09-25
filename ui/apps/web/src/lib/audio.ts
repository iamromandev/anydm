/**
 * A source's audio tracks, and which one the player opens with (#99).
 *
 * The same rules as the API's `lib/media/audio.py`, because a file the
 * browser plays itself is decided here: it opens with the track the file
 * marks, so any other needs a session.
 */

export type AudioTrack = {
    /** Among the source's audio tracks; what a switch names. */
    index: number;
    language: string | null;
    title: string | null;
    channels: number | null;
    codec: string | null;
    /** The one the source marks to open with. */
    default: boolean;
};

export function normalizeAudioTracks(raw: any): AudioTrack[] {
    if (!Array.isArray(raw)) return [];
    return raw.map((track: any) => ({
        index: track?.index ?? 0,
        // The envelope leaves out what is null.
        language: track?.language ?? null,
        title: track?.title ?? null,
        channels: track?.channels ?? null,
        codec: track?.codec ?? null,
        default: Boolean(track?.default),
    }));
}

/** The languages Settings offers, by the ISO 639-1 code a file's and a site's both fold to. */
export const AUDIO_LANGUAGES: { value: string; label: string }[] = [
    [
        "ar",
        "Arabic",
    ],
    [
        "bn",
        "Bengali",
    ],
    [
        "zh",
        "Chinese",
    ],
    [
        "cs",
        "Czech",
    ],
    [
        "da",
        "Danish",
    ],
    [
        "nl",
        "Dutch",
    ],
    [
        "en",
        "English",
    ],
    [
        "fi",
        "Finnish",
    ],
    [
        "fr",
        "French",
    ],
    [
        "de",
        "German",
    ],
    [
        "el",
        "Greek",
    ],
    [
        "he",
        "Hebrew",
    ],
    [
        "hi",
        "Hindi",
    ],
    [
        "hu",
        "Hungarian",
    ],
    [
        "id",
        "Indonesian",
    ],
    [
        "it",
        "Italian",
    ],
    [
        "ja",
        "Japanese",
    ],
    [
        "ko",
        "Korean",
    ],
    [
        "no",
        "Norwegian",
    ],
    [
        "fa",
        "Persian",
    ],
    [
        "pl",
        "Polish",
    ],
    [
        "pt",
        "Portuguese",
    ],
    [
        "ro",
        "Romanian",
    ],
    [
        "ru",
        "Russian",
    ],
    [
        "es",
        "Spanish",
    ],
    [
        "sv",
        "Swedish",
    ],
    [
        "th",
        "Thai",
    ],
    [
        "tr",
        "Turkish",
    ],
    [
        "uk",
        "Ukrainian",
    ],
    [
        "vi",
        "Vietnamese",
    ],
].map(
    ([
        value,
        label,
    ]) => ({ value, label }),
);

/** ISO 639-2 codes, bibliographic and terminological, by the ISO 639-1 code they mean. */
const TWO_LETTER: Record<string, string> = {
    ara: "ar",
    ben: "bn",
    chi: "zh",
    zho: "zh",
    cze: "cs",
    ces: "cs",
    dan: "da",
    dut: "nl",
    nld: "nl",
    eng: "en",
    fin: "fi",
    fre: "fr",
    fra: "fr",
    ger: "de",
    deu: "de",
    gre: "el",
    ell: "el",
    heb: "he",
    hin: "hi",
    hun: "hu",
    ind: "id",
    ita: "it",
    jpn: "ja",
    kor: "ko",
    may: "ms",
    msa: "ms",
    nor: "no",
    nob: "no",
    nno: "no",
    per: "fa",
    fas: "fa",
    pol: "pl",
    por: "pt",
    rum: "ro",
    ron: "ro",
    rus: "ru",
    spa: "es",
    swe: "sv",
    tam: "ta",
    tel: "te",
    tha: "th",
    tur: "tr",
    ukr: "uk",
    urd: "ur",
    vie: "vi",
    fil: "tl",
    tgl: "tl",
};

const UNKNOWN = new Set([
    "und",
    "unk",
    "mis",
    "mul",
    "zxx",
    "",
]);

/** A code's language alone, comparable across ISO 639-1, 639-2 and BCP 47; `null` when unknown. */
export function languageKey(code: string | null | undefined): string | null {
    if (!code) return null;
    const primary = code.trim().replace("_", "-").split("-")[0].toLowerCase();
    if (UNKNOWN.has(primary)) return null;
    return TWO_LETTER[primary] ?? primary;
}

/**
 * The track to open with: the preferred language's, else the one the source
 * marks, else the first. `null` when there are none.
 */
export function pickAudioTrack(
    tracks: AudioTrack[],
    language: string | null = null,
): number | null {
    if (tracks.length === 0) return null;
    const key = languageKey(language);
    if (key !== null) {
        const match = tracks.find(
            (track) => languageKey(track.language) === key,
        );
        if (match) return match.index;
    }
    return (tracks.find((track) => track.default) ?? tracks[0]).index;
}

/**
 * Whether a file the browser could play itself needs a session anyway: the
 * browser opens with the track the file marks, and the preference picks
 * another.
 */
export function needsAudioSession(
    tracks: AudioTrack[],
    language: string | null,
): boolean {
    return pickAudioTrack(tracks, language) !== pickAudioTrack(tracks, null);
}

const CHANNELS: Record<number, string> = {
    1: "mono",
    2: "stereo",
    6: "5.1",
    8: "7.1",
};

function languageName(code: string | null): string | null {
    const key = languageKey(code);
    if (key === null) return null;
    try {
        return (
            new Intl.DisplayNames(
                [
                    "en",
                ],
                { type: "language" },
            ).of(key) ?? key
        );
    } catch {
        return key;
    }
}

/**
 * How the menu names a track: its language, its title when the file gives
 * one, and its channels. Tracks that share all of that are told apart by
 * number, as is one with nothing to say.
 */
export function audioTrackLabel(
    track: AudioTrack,
    tracks: AudioTrack[] = [
        track,
    ],
): string {
    const describe = (t: AudioTrack) =>
        [
            languageName(t.language),
            t.title,
            t.channels !== null
                ? (CHANNELS[t.channels] ?? `${t.channels} ch`)
                : null,
        ]
            .filter(Boolean)
            .join(" · ");
    const label = describe(track);
    const twins = tracks.filter((other) => describe(other) === label);
    if (!label) return `Track ${track.index + 1}`;
    return twins.length > 1 ? `${label} (${track.index + 1})` : label;
}

/**
 * Which segment of an HLS media playlist holds `time`, by adding up its
 * `#EXTINF` durations. The one to have ready before swapping to a new
 * session, so the picture carries on from there. The last when `time` is
 * past the end.
 */
export function segmentAt(playlist: string, time: number): number {
    let start = 0;
    let index = -1;
    for (const line of playlist.split("\n")) {
        const match = /^#EXTINF:([\d.]+)/.exec(line.trim());
        if (!match) continue;
        index += 1;
        start += Number(match[1]);
        if (time < start) return index;
    }
    return Math.max(0, index);
}
