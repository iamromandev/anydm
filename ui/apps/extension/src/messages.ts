import type { Refused, Unreachable } from "./bridge";

/** What the user can do about a failure; the popup shows a button for it. */
export type FailureAction =
    "retry" | "pair" | "without-cookies" | "probe-again" | undefined;

export interface Failure {
    text: string;
    action: FailureAction;
}

/** Which step failed: reading the link or adding it. */
export type Step = "add" | "confirm";

const MAX_REASON = 160;

/**
 * Words for a failure. Only a link that could not be read repeats the app's reason (it names a status or a network error,
 * never what was sent); every other text is fixed, so nothing the page or the browser sent can come back into view.
 */
export function describeFailure(
    outcome: Refused | Unreachable,
    step: Step,
): Failure {
    if (outcome.kind === "unreachable") {
        return {
            text: "anydm is not running, or its browser bridge is off. Start anydm and try again.",
            action: "retry",
        };
    }
    switch (outcome.status) {
        case 400:
            return {
                text: "anydm can't use the cookies or headers this page sent.",
                action: step === "add" ? "without-cookies" : undefined,
            };
        case 403:
            return {
                text: "anydm does not answer this browser yet. Add this browser's address in anydm's settings, then try again.",
                action: "pair",
            };
        case 409:
            return {
                text: "This link is already in anydm.",
                action: undefined,
            };
        case 410:
            return {
                text: "The link timed out before it was added. Check it again.",
                action: "probe-again",
            };
        case 413:
        case 415:
            return {
                text: "This extension and anydm do not match. Update both.",
                action: undefined,
            };
        case 422:
            return {
                text: "anydm does not take this kind of link.",
                action: undefined,
            };
        case 502:
            return {
                text: `anydm could not read the link${reason(outcome.message)}`,
                action: undefined,
            };
        case 503:
            return {
                text: "anydm can't take this kind of link here.",
                action: undefined,
            };
        case 504:
            return {
                text: "anydm took too long to answer.",
                action: "retry",
            };
        default:
            return {
                text: "anydm had a problem with this link.",
                action: undefined,
            };
    }
}

function reason(message: string): string {
    const clean = message.replace(/\s+/g, " ").trim();
    if (clean === "") return ".";
    const short =
        clean.length > MAX_REASON ? `${clean.slice(0, MAX_REASON)}…` : clean;
    return `: ${short}`;
}

export function formatSize(size: number | null): string {
    if (size === null) return "unknown size";
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let value = size;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit += 1;
    }
    return unit === 0 ? `${size} B` : `${value.toFixed(1)} ${units[unit]}`;
}
