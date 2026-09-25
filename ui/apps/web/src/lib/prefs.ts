/**
 * The choices that belong to a person rather than to the server.
 *
 * Kept in this browser, because they are about how one person likes to use
 * the app. Anything the whole deployment shares — how many workers, where
 * files land — comes from the API and is read-only here.
 */

/** Presets the API accepts for a YouTube download. */
export const PRESETS = [
    "best",
    "2160",
    "1440",
    "1080",
    "720",
    "480",
    "mp3",
] as const;

export type Preset = (typeof PRESETS)[number];

/** The same presets, as a person would name them. */
export const PRESET_OPTIONS: { value: Preset; label: string }[] = [
    { value: "best", label: "Best" },
    { value: "2160", label: "4K" },
    { value: "1440", label: "1440p" },
    { value: "1080", label: "1080p" },
    { value: "720", label: "720p" },
    { value: "480", label: "480p" },
    { value: "mp3", label: "Audio" },
];

export type Prefs = {
    /** What the hero input starts on for a YouTube link. */
    defaultPreset: Preset;
    /** Whether removing a download asks first. */
    confirmBeforeRemove: boolean;
    /**
     * The audio track the player opens with, by language (#99): an ISO 639-1
     * code, or "" for whichever the file marks.
     */
    audioLanguage: string;
};

export const DEFAULT_PREFS: Prefs = {
    defaultPreset: "best",
    confirmBeforeRemove: true,
    audioLanguage: "",
};

const STORAGE_KEY = "anydm.prefs";

function isPreset(value: unknown): value is Preset {
    return PRESETS.includes(value as Preset);
}

/**
 * The saved preferences, with the defaults filling any gap.
 *
 * Merged field by field rather than taken wholesale: a copy written by an
 * older build is missing whatever was added since, and a copy edited by hand
 * can say anything at all.
 */
export function loadPrefs(
    storage: Storage | undefined = globalThis.localStorage,
): Prefs {
    try {
        const raw = storage?.getItem(STORAGE_KEY);
        if (!raw) return DEFAULT_PREFS;

        const saved = JSON.parse(raw) as Partial<Prefs>;
        return {
            defaultPreset: isPreset(saved.defaultPreset)
                ? saved.defaultPreset
                : DEFAULT_PREFS.defaultPreset,
            confirmBeforeRemove:
                typeof saved.confirmBeforeRemove === "boolean"
                    ? saved.confirmBeforeRemove
                    : DEFAULT_PREFS.confirmBeforeRemove,
            audioLanguage:
                typeof saved.audioLanguage === "string" &&
                /^[A-Za-z]{0,8}(-[A-Za-z0-9]{1,8})*$/.test(saved.audioLanguage)
                    ? saved.audioLanguage
                    : DEFAULT_PREFS.audioLanguage,
        };
    } catch {
        // Unparseable, or storage that refuses to be read at all.
        return DEFAULT_PREFS;
    }
}

export function savePrefs(
    prefs: Prefs,
    storage: Storage | undefined = globalThis.localStorage,
): void {
    try {
        storage?.setItem(STORAGE_KEY, JSON.stringify(prefs));
    } catch {
        // Remembering is a convenience; failing to is not worth an error.
    }
}
