import type { Connected } from "./client";
import type { State } from "./flow";
import { formatSize } from "./messages";
import type { PairOutcome } from "./pairing";
import type { Settings } from "./settings";

export type ButtonId =
    | "add"
    | "allow"
    | "retry"
    | "copy"
    | "without-cookies"
    | "probe-again"
    | "close";

export interface View {
    heading: string;
    lines: string[];
    buttons: { id: ButtonId; label: string; primary?: boolean }[];
    /** This browser's address, when the user needs to give it to anydm. */
    origin?: string;
}

const button = (id: ButtonId, label: string, primary = false) =>
    primary ? { id, label, primary } : { id, label };

const FAILURE_BUTTONS = {
    retry: button("retry", "Try again", true),
    "without-cookies": button("without-cookies", "Add without cookies", true),
    "probe-again": button("probe-again", "Check again", true),
} as const;

/** What the popup shows for a state of the flow; plain data, so it can be checked without a page. */
export function viewOf(state: State, origin: string): View {
    switch (state.step) {
        case "finding":
            return { heading: "Checking the link…", lines: [], buttons: [] };
        case "needs-permission":
            return {
                heading: "anydm needs access",
                lines: [
                    "To read this page's cookies and reach the anydm app, let the extension access sites.",
                ],
                buttons: [
                    button("allow", "Allow access", true),
                ],
            };
        case "ready": {
            const lines = [
                `${formatSize(state.size)} · ${state.type.toLowerCase()}`,
            ];
            if (!state.rangesSupported) {
                lines.push("This server can't resume a download.");
            }
            if (state.droppedCookies) {
                lines.push("Too many cookies to send: added without them.");
            }
            if (state.withoutCookies) lines.push("Added without cookies.");
            if (!state.verified) {
                lines.push(
                    "This browser is not paired with anydm, so the page's cookies are not sent.",
                );
            }
            return {
                heading: state.name ?? "Ready to add",
                lines,
                buttons: [
                    button("add", "Add to anydm", true),
                ],
            };
        }
        case "duplicate":
            return {
                heading: "Already in anydm",
                lines: state.name
                    ? [
                          state.name,
                      ]
                    : [],
                buttons: [],
            };
        case "skipped":
            return {
                heading: "Left to the browser",
                lines: [
                    state.reason === "small"
                        ? "This file is smaller than the size you set in anydm's settings."
                        : "This site is in anydm's list of excluded sites.",
                ],
                buttons: [
                    button("close", "Close"),
                ],
            };
        case "adding":
            return { heading: "Adding…", lines: [], buttons: [] };
        case "added":
            return {
                heading: "Added to anydm",
                lines: [],
                buttons: [
                    button("close", "Close"),
                ],
            };
        case "expired":
            return {
                heading: "The link timed out",
                lines: [
                    "anydm no longer holds what it read.",
                ],
                buttons: [
                    FAILURE_BUTTONS["probe-again"],
                ],
            };
        case "failed": {
            if (state.action === "pair") {
                return {
                    heading: "Add this browser to anydm",
                    lines: [
                        state.text,
                    ],
                    buttons: [
                        button("copy", "Copy this browser's address", true),
                        button("retry", "Try again"),
                    ],
                    origin,
                };
            }
            return {
                heading: "Could not add",
                lines: [
                    state.text,
                ],
                buttons: [
                    state.action
                        ? FAILURE_BUTTONS[state.action]
                        : button("close", "Close"),
                ],
            };
        }
    }
}

/** What the toolbar button shows, with no link: whether anydm answers this browser, and whether it is the paired one. */
export function statusView(found: Connected, origin: string): View {
    const use = "Right-click a link and choose “Download with anydm”.";
    switch (found.kind) {
        case "none":
            return {
                heading: "anydm",
                lines: [
                    "anydm is not running, or its browser bridge is off.",
                    use,
                ],
                buttons: [
                    button("retry", "Check again"),
                ],
            };
        case "forbidden":
            return {
                heading: "anydm",
                lines: [
                    "anydm is running but does not answer this browser yet. Add this browser's address in anydm's settings.",
                    use,
                ],
                buttons: [
                    button("copy", "Copy this browser's address", true),
                    button("retry", "Check again"),
                ],
                origin,
            };
        case "no-background":
            return {
                heading: "anydm",
                lines: [
                    "The extension's background part did not answer. Reload the extension.",
                ],
                buttons: [
                    button("retry", "Check again"),
                ],
            };
        case "unverified":
            return {
                heading: "anydm",
                lines: [
                    found.reason === "unpaired"
                        ? "Something answers like anydm, but it says it does not know this browser."
                        : found.reason === "unavailable"
                          ? "anydm is running but can't read its list of paired browsers just now."
                          : "Something answers like anydm, but could not prove it is the anydm this browser is paired with.",
                    "Nothing is sent to it.",
                    use,
                ],
                buttons: [
                    button("retry", "Check again"),
                ],
            };
        case "ready":
            return {
                heading: "anydm",
                lines: [
                    `anydm is running (port ${found.port}).`,
                    use,
                ],
                buttons: [],
            };
    }
}

/** What the toolbar window shows of the pairing. */
export interface PairingView {
    status: string;
    /** More to say under the status: what to do next. */
    help: string;
    canPair: boolean;
    canForget: boolean;
}

/**
 * [paired]: this browser has a secret. [found]: what finding anydm just came to, which says whether the app proved itself.
 * Nothing about the secret, the id or the code is shown.
 */
export function pairingView(paired: boolean, found: Connected): PairingView {
    if (!paired) {
        return {
            status: "Not paired.",
            help: "To send cookies, and to take over downloads, pair this browser: in anydm open Browsers and Pair a browser, then type the code here.",
            canPair: true,
            canForget: false,
        };
    }
    const base = { canPair: true, canForget: true };
    switch (found.kind) {
        case "ready":
            return { ...base, status: "Paired with anydm.", help: "" };
        case "none":
            return {
                ...base,
                status: "Paired. anydm is not running.",
                help: "",
            };
        case "forbidden":
            return {
                ...base,
                status: "Paired, but anydm does not answer this browser yet.",
                help: "",
            };
        case "no-background":
            return {
                ...base,
                status: "Paired, but the extension's background part did not answer.",
                help: "Reload the extension.",
            };
        case "unverified":
            switch (found.reason) {
                case "unpaired":
                    return {
                        ...base,
                        status: "Paired, but anydm does not know this browser (any more).",
                        help: "Pair again with a new code, or forget this pairing. Nothing is sent meanwhile.",
                    };
                case "unavailable":
                    return {
                        ...base,
                        status: "Paired. anydm can't read its list of paired browsers just now.",
                        help: "Try again in a moment.",
                    };
                case "no-proof":
                    return {
                        ...base,
                        status: "Paired, but what answers on anydm's port could not prove it is anydm.",
                        help: "Nothing is sent to it.",
                    };
            }
    }
}

/** What to tell the person of how pairing went. It never holds the code. */
export function pairMessage(outcome: PairOutcome): string {
    switch (outcome.kind) {
        case "paired":
            return "Paired with anydm.";
        case "bad-code":
            return "That is not a pairing code. It is 16 letters and digits, like XXXX-XXXX-XXXX-XXXX.";
        case "none":
            return "anydm is not running, or its browser bridge is off.";
        case "forbidden":
            return "anydm does not answer this browser yet. Add this browser's address in anydm's settings first.";
        case "wrong-code":
            return "Wrong code, or not the anydm app. Check the code shown in anydm and try again.";
        case "no-code":
            return "anydm is not waiting for a code. In anydm open Browsers and choose Pair a browser.";
        case "throttled":
            return `Too many tries. Wait ${outcome.retryAfterSeconds} seconds and try again.`;
        case "not-saved":
            return "anydm could not save the pairing, so nothing changed. The code still works: try again.";
        case "not-kept":
            return "anydm accepted the pairing, but this browser could not keep it, so it is not paired. The code is used up: choose Pair a browser in anydm again for a new code. You can revoke the unused entry for this browser in anydm's Browsers list.";
        case "unavailable":
            return "anydm can't read its list of paired browsers just now. Wait a moment and try again.";
        case "mismatch":
            return "This extension and anydm do not match. Update both.";
        case "failed":
            return "Pairing did not work. Try again.";
    }
}

/** What to tell the person once the browser answered the request for what capture needs. */
export function captureAccessMessage(
    granted: boolean,
    paired: boolean,
): string {
    if (!granted) {
        return "The browser did not give the extension access to downloads and to all sites, which capture needs. Capture stays off.";
    }
    return paired
        ? "Capture is on."
        : "Capture is on, and starts once this browser is paired with anydm.";
}

/** What the settings section of the toolbar popup shows. */
export interface SettingsView {
    captureLabel: string;
    captureValue: boolean;
    captureHint: string;
    sizeLabel: string;
    sizeValue: string;
    sizeHint: string;
    /** Said when capture is off: the size and site settings do nothing then. */
    limitsNote: string | null;
    /** Said when this browser is not paired: capture waits for it. */
    pairingNote: string | null;
    hostsLabel: string;
    hosts: { host: string; removeLabel: string }[];
    /** Shown instead of the list when it is empty. */
    empty: string | null;
}

export function settingsView(settings: Settings, paired = true): SettingsView {
    return {
        captureLabel: "Capture browser downloads",
        captureValue: settings.captureDownloads,
        captureHint:
            "Off: the browser keeps all its downloads. On: a download the browser starts goes to anydm, and the browser keeps it if anydm can't take it.",
        sizeLabel: "Skip files smaller than N MiB",
        sizeValue: String(settings.minSizeMb),
        sizeHint:
            "0 sends every file. A file of unknown size is always sent. 1 MiB is 1,048,576 bytes.",
        limitsNote: settings.captureDownloads
            ? null
            : "The size and site settings apply only to captured downloads, so they do nothing while capture is off.",
        pairingNote: paired
            ? null
            : "Capture takes downloads only once this browser is paired with anydm.",
        hostsLabel: "Excluded sites (and their subdomains)",
        hosts: settings.excludedHosts.map((host) => ({
            host,
            removeLabel: `Remove ${host}`,
        })),
        empty:
            settings.excludedHosts.length === 0 ? "No excluded sites." : null,
    };
}
