import type { Found } from "./bridge";
import type { State } from "./flow";
import { formatSize } from "./messages";

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

/** What the toolbar button shows, with no link: whether anydm answers this browser. */
export function statusView(found: Found | null, origin: string): View {
    const use = "Right-click a link and choose “Download with anydm”.";
    if (found === null) {
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
    }
    if (!found.allowed) {
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
    }
    return {
        heading: "anydm",
        lines: [
            `anydm is running (port ${found.port}).`,
            use,
        ],
        buttons: [],
    };
}
