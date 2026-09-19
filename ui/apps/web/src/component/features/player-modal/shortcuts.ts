/**
 * What a key press means while the player is open.
 *
 * Pure, and deliberately ignorant of the player's state: it says what was
 * asked for, and the modal decides what that means right now. Escape is the
 * clearest case — whether it leaves fullscreen or closes the player depends on
 * which of those is true at the time, which is not a property of the key.
 */

/** One arrow key's worth of seeking. */
export const SEEK_STEP_S = 5;

/** The longer step, on the keys either side of k. */
export const SEEK_LONG_S = 10;

/** One arrow key's worth of volume, as a fraction of full. */
export const VOLUME_STEP = 0.1;

export type PlayerAction =
    | { type: "togglePlay" }
    | { type: "seekBy"; seconds: number }
    | { type: "volumeBy"; delta: number }
    | { type: "toggleMute" }
    | { type: "toggleFullscreen" }
    | { type: "toggleHelp" }
    | { type: "escape" };

/**
 * The parts of a `KeyboardEvent` these rules read.
 *
 * `target` is `unknown` so a real event satisfies it structurally while a test
 * can hand over a plain object; the narrowing happens below.
 */
export type ShortcutEvent = {
    key: string;
    ctrlKey?: boolean;
    metaKey?: boolean;
    altKey?: boolean;
    target?: unknown;
};

/** Somewhere a key press means a character, not a command. */
function isTyping(target: unknown): boolean {
    if (typeof target !== "object" || target === null) return false;
    const element = target as {
        tagName?: unknown;
        isContentEditable?: unknown;
    };
    if (element.isContentEditable === true) return true;
    const tagName =
        typeof element.tagName === "string"
            ? element.tagName.toUpperCase()
            : "";
    return (
        tagName === "INPUT" || tagName === "TEXTAREA" || tagName === "SELECT"
    );
}

export function resolveShortcut(event: ShortcutEvent): PlayerAction | null {
    // Shift is not checked: "?" needs it. The other three belong to the
    // browser, and stealing cmd+F from someone searching a page is worse than
    // any shortcut is worth.
    if (event.ctrlKey || event.metaKey || event.altKey) return null;
    if (isTyping(event.target)) return null;

    switch (event.key) {
        case " ":
        case "k":
        case "K":
            return { type: "togglePlay" };
        case "ArrowRight":
            return { type: "seekBy", seconds: SEEK_STEP_S };
        case "ArrowLeft":
            return { type: "seekBy", seconds: -SEEK_STEP_S };
        case "l":
        case "L":
            return { type: "seekBy", seconds: SEEK_LONG_S };
        case "j":
        case "J":
            return { type: "seekBy", seconds: -SEEK_LONG_S };
        case "ArrowUp":
            return { type: "volumeBy", delta: VOLUME_STEP };
        case "ArrowDown":
            return { type: "volumeBy", delta: -VOLUME_STEP };
        case "m":
        case "M":
            return { type: "toggleMute" };
        case "f":
        case "F":
            return { type: "toggleFullscreen" };
        case "?":
            return { type: "toggleHelp" };
        case "Escape":
            return { type: "escape" };
        default:
            return null;
    }
}

/** The list the help overlay draws, in the order it draws them. */
export const SHORTCUT_HINTS: { keys: string; description: string }[] = [
    { keys: "Space / K", description: "Play or pause" },
    { keys: "← / →", description: `Back or forward ${SEEK_STEP_S}s` },
    { keys: "J / L", description: `Back or forward ${SEEK_LONG_S}s` },
    { keys: "↑ / ↓", description: "Volume up or down" },
    { keys: "M", description: "Mute" },
    { keys: "F", description: "Fullscreen" },
    { keys: "?", description: "This list" },
    { keys: "Esc", description: "Leave fullscreen, or close" },
];
