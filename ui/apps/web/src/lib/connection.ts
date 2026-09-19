/**
 * Whether live updates are actually arriving, and what to do when they are not.
 *
 * The task list is fed by one `EventSource`. The browser reopens it on its own
 * and the server replays a full snapshot on every connection, so a dropped
 * stream heals without help — but silently, and a stream that never comes back
 * looks exactly like a system where nothing is happening. These rules are what
 * tell those two apart.
 */

export type Connection = "connecting" | "live" | "reconnecting" | "offline";

/** How long a gap may last before it is worth interrupting someone over. */
export const WARN_AFTER_MS = 10_000;

/** How often to fall back to fetching the list while the stream is down. */
export const FALLBACK_POLL_MS = 5000;

/**
 * What an `error` means, read from the stream's own state.
 *
 * `EventSource` reports an error both for a blip it is about to retry and for
 * a connection it has abandoned; only `readyState` separates them.
 */
export function connectionOnError(readyState: number): Connection {
    // 2 is CLOSED. Named by value because EventSource is not defined on the
    // server, where this module is also imported.
    return readyState === 2 ? "offline" : "reconnecting";
}

/**
 * True whenever live updates are not confirmed to be flowing.
 *
 * Deliberately total rather than a list of bad states: anything that is not a
 * confirmed open stream is a gap that something else has to cover.
 */
export function isDegraded(state: Connection): boolean {
    return state !== "live";
}

/** Whether the gap has lasted long enough to say so out loud. */
export function shouldWarn(
    state: Connection,
    degradedSince: number | null,
    now: number,
): boolean {
    if (!isDegraded(state) || degradedSince === null) return false;
    return now - degradedSince >= WARN_AFTER_MS;
}

// A total map rather than a switch, so a state added later fails the typecheck
// instead of rendering as an empty tooltip.
const LABELS: Record<Connection, string> = {
    connecting: "Connecting…",
    live: "Live",
    reconnecting: "Reconnecting…",
    offline: "Offline",
};

export function connectionLabel(state: Connection): string {
    return LABELS[state];
}
