/**
 * A source's subtitle tracks, which one shows, and their cues (#100).
 *
 * The API cuts a session's cues by the segment, at the source's own times,
 * and a file played as it is gets each track whole. Either way the player
 * adds the cues itself, to one text track on the video: a cue across a seam
 * comes in both segments, so each is added once, by `cueKey`.
 */

import { languageKey, languageName } from "./audio";

export type SubtitleTrack = {
    /** Among the source's subtitle tracks; what the subtitle routes take. */
    index: number;
    language: string | null;
    title: string | null;
    codec: string | null;
    default: boolean;
    /** Shown whether or not subtitles are on: signs, lines in another language. */
    forced: boolean;
    /** Whether it can be shown: a picture track (PGS, VobSub) can't. */
    text: boolean;
    /**
     * A subtitle file beside the video rather than a track inside it (#101):
     * fetched whole, even in a session. Its title is its file name.
     */
    external: boolean;
};

export function normalizeSubtitleTracks(raw: any): SubtitleTrack[] {
    if (!Array.isArray(raw)) return [];
    return raw.map((track: any) => ({
        index: track?.index ?? 0,
        // The envelope leaves out what is null.
        language: track?.language ?? null,
        title: track?.title ?? null,
        codec: track?.codec ?? null,
        default: Boolean(track?.default),
        forced: Boolean(track?.forced),
        text: Boolean(track?.text),
        external: Boolean(track?.external),
    }));
}

/**
 * The track to show from the start: one in the preferred language (a full
 * one before a forced one), else a forced track, which turns on by itself.
 * `null` is off. Only text tracks count.
 */
export function pickSubtitleTrack(
    tracks: SubtitleTrack[],
    language: string | null,
): number | null {
    const shown = tracks.filter((track) => track.text);
    const key = languageKey(language);
    if (key !== null) {
        const inLanguage = shown.filter(
            (track) => languageKey(track.language) === key,
        );
        const full = inLanguage.find((track) => !track.forced);
        if (full ?? inLanguage[0]) return (full ?? inLanguage[0]).index;
    }
    return shown.find((track) => track.forced)?.index ?? null;
}

/**
 * What C turns on when subtitles are off: the track last shown, else the
 * one the preference picks, else the first that can be shown.
 */
export function subtitleToToggleOn(
    tracks: SubtitleTrack[],
    last: number | null,
    language: string | null,
): number | null {
    const shown = tracks.filter((track) => track.text);
    if (last !== null && shown.some((track) => track.index === last)) {
        return last;
    }
    return pickSubtitleTrack(tracks, language) ?? shown[0]?.index ?? null;
}

/** How the menu names a track: its language, its title, and whether it's forced or can't be shown. */
export function subtitleTrackLabel(
    track: SubtitleTrack,
    tracks: SubtitleTrack[] = [
        track,
    ],
): string {
    const describe = (t: SubtitleTrack) =>
        [
            languageName(t.language),
            t.title,
            t.forced ? "forced" : null,
        ]
            .filter(Boolean)
            .join(" · ");
    let label = describe(track) || `Track ${track.index + 1}`;
    if (
        tracks.filter((other) => describe(other) === describe(track)).length > 1
    ) {
        label = `${label} (${track.index + 1})`;
    }
    return track.text ? label : `${label} (can't be shown)`;
}

export type ParsedCue = { start: number; end: number; text: string };

function seconds(stamp: string): number {
    const parts = stamp.trim().split(":").map(Number);
    while (parts.length < 3) parts.unshift(0);
    return parts[0] * 3600 + parts[1] * 60 + parts[2];
}

/** The cues of a WebVTT file: their times and text, settings left out. */
export function parseVtt(text: string): ParsedCue[] {
    const cues: ParsedCue[] = [];
    for (const block of text.replace(/\r\n?/g, "\n").split(/\n{2,}/)) {
        const lines = block.split("\n");
        const at = lines.findIndex((line) => line.includes("-->"));
        if (at === -1) continue;
        const [
            start,
            rest,
        ] = lines[at].split("-->");
        const end = rest.trim().split(/\s+/)[0];
        const body = lines
            .slice(at + 1)
            .join("\n")
            .trim();
        if (!body) continue;
        cues.push({ start: seconds(start), end: seconds(end), text: body });
    }
    return cues;
}

/** What makes two cues the same cue: a cue across a seam comes in both segments. */
export function cueKey(cue: ParsedCue): string {
    return `${cue.start.toFixed(3)}|${cue.end.toFixed(3)}|${cue.text}`;
}

/**
 * The segments whose cues the player wants at `time`: its own and the next,
 * so a cue is there before it's due. None past the last.
 */
export function cueSegmentsAt(
    time: number,
    segmentSeconds: number,
    duration: number,
): number[] {
    if (segmentSeconds <= 0 || !Number.isFinite(duration) || duration <= 0) {
        return [];
    }
    const count = Math.ceil(duration / segmentSeconds);
    const at = Math.min(
        count - 1,
        Math.max(0, Math.floor(time / segmentSeconds)),
    );
    return [
        at,
        at + 1,
    ].filter((index) => index < count);
}
