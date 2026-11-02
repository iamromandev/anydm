import { ext } from "./api";
import type { Fetch } from "./bridge";
import {
    CAPTURE_ACCESS,
    turnCaptureOff,
    turnCaptureOn,
    type CaptureSwitchDeps,
} from "./capture";
import type { Connected } from "./client";
import { cookiesFor } from "./cookies";
import { Flow, type Deps, type State } from "./flow";
import { forgetPairing, loadPairing, pair } from "./pairing";
import { remoteBridge } from "./rpc";
import {
    addHost,
    loadSettings,
    removeHost,
    saveSettings,
    type Settings,
} from "./settings";
import { parseTarget } from "./target";
import {
    captureAccessMessage,
    pairMessage,
    pairingView,
    settingsView,
    statusView,
    viewOf,
    type ButtonId,
    type View,
} from "./view";

const PORT_KEY = "anydm.port";

// Where the last working port is kept. The page may not be allowed storage; the port is only a shortcut.
const lastPort = {
    get(): number | undefined {
        try {
            const port = Number(localStorage.getItem(PORT_KEY));
            return Number.isInteger(port) && port > 0 ? port : undefined;
        } catch {
            return undefined;
        }
    },
    set(port: number) {
        try {
            localStorage.setItem(PORT_KEY, String(port));
        } catch {
            // Not kept: the next search tries every port.
        }
    },
};

const everySite = {
    origins: [
        "<all_urls>",
    ],
};
const fetchFn: Fetch = (url, init) => fetch(url, init);

// The app has one session per paired browser, and a new hello ends the last, so this page never says hello or signs: it asks
// the background worker, which owns the session (`rpc.ts`). Pairing needs no session, so it is done here.
const bridge = remoteBridge((message) => ext.runtime.sendMessage(message));

const deps: Deps = {
    bridge,
    userAgent: navigator.userAgent,
    settings: () => loadSettings(ext.storage.local),
    hasHostAccess: () => ext.permissions.contains(everySite),
    requestHostAccess: () => ext.permissions.request(everySite),
    cookiesFor: (url, tabId) => cookiesFor(ext.cookies, url, tabId),
    lastPort,
    setTimer: (callback, ms) => setTimeout(callback, ms),
    clearTimer: (handle) => clearTimeout(handle as number),
};

// This browser's address, which the user gives to anydm to pair a browser whose address is not fixed.
const origin = location.origin;

const heading = document.getElementById("heading")!;
const lines = document.getElementById("lines")!;
const originBox = document.getElementById("origin")!;
const buttons = document.getElementById("buttons")!;

function show(view: View, onClick: (id: ButtonId) => void) {
    heading.textContent = view.heading;
    lines.replaceChildren(
        ...view.lines.map((text) => {
            const p = document.createElement("p");
            p.textContent = text;
            return p;
        }),
    );
    originBox.hidden = view.origin === undefined;
    originBox.textContent = view.origin ?? "";
    buttons.replaceChildren(
        ...view.buttons.map((spec) => {
            const b = document.createElement("button");
            b.textContent = spec.label;
            if (spec.primary) b.className = "primary";
            b.addEventListener("click", () => onClick(spec.id));
            return b;
        }),
    );
}

async function copyOrigin() {
    try {
        await navigator.clipboard.writeText(origin);
    } catch {
        // The address is shown selected-on-click; the user can copy it by hand.
    }
}

// The pairing section: whether this browser is paired, a field for the code, and Forget.
const pairingSection = document.getElementById("pairing")!;
const pairStatus = document.getElementById("pair-status")!;
const pairHelp = document.getElementById("pair-help")!;
const pairForm = document.getElementById("pair-form") as HTMLFormElement;
const pairCode = document.getElementById("pair-code") as HTMLInputElement;
const pairButton = document.getElementById("pair-button") as HTMLButtonElement;
const pairText = document.getElementById("pair-message")!;
const forgetButton = document.getElementById("forget") as HTMLButtonElement;

function showPairing(found: Connected, recheck: () => Promise<void>) {
    const view = pairingView(paired, found);
    pairStatus.textContent = view.status;
    pairHelp.textContent = view.help;
    pairHelp.hidden = view.help === "";
    pairForm.hidden = !view.canPair;
    forgetButton.hidden = !view.canForget;
    pairingSection.hidden = false;
    recheckAfterPairing = recheck;
}

let recheckAfterPairing: () => Promise<void> = async () => {};

pairForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void (async () => {
        pairButton.disabled = true;
        pairText.textContent = "Pairing…";
        let message: string;
        try {
            // The code is typed here and only a proof of it leaves: it is not kept, shown again or logged.
            const outcome = await pair(
                { fetch: fetchFn, storage: ext.storage.local },
                pairCode.value,
                lastPort.get(),
            );
            if (outcome.kind === "paired") pairCode.value = "";
            message = pairMessage(outcome);
        } catch {
            message = pairMessage({ kind: "failed" });
        }
        pairButton.disabled = false;
        await recheckAfterPairing();
        pairText.textContent = message;
    })();
});

forgetButton.addEventListener("click", () => {
    void (async () => {
        try {
            await forgetPairing(ext.storage.local);
            await recheckAfterPairing();
            pairText.textContent =
                "This browser forgot the pairing. To end it in anydm too, revoke this browser in anydm's Browsers list.";
        } catch {
            pairText.textContent = "Could not forget the pairing.";
        }
    })();
});

const target = parseTarget(location.search);

// Whether this browser holds a pairing, as the toolbar window last looked; the settings section says what capture needs.
let paired = false;
let renderSettings: () => void = () => {};

if (target) {
    const flow: Flow = new Flow(deps, target, (state: State) =>
        show(viewOf(state, origin), (id) => {
            if (id === "add") void flow.confirm();
            else if (id === "allow") void flow.allowAccess();
            else if (id === "without-cookies") void flow.withoutCookies();
            else if (id === "retry" || id === "probe-again") void flow.start();
            else if (id === "copy") void copyOrigin();
            else window.close();
        }),
    );
    void flow.start();
} else {
    const check = async () => {
        show(
            {
                heading: "anydm",
                lines: [
                    "Checking…",
                ],
                buttons: [],
            },
            () => {},
        );
        const found = await bridge.connect(lastPort.get());
        if (found.kind === "ready") lastPort.set(found.port);
        paired = (await loadPairing(ext.storage.local)) !== null;
        show(statusView(found, origin), (id) => {
            if (id === "copy") void copyOrigin();
            else void check();
        });
        showPairing(found, check);
        renderSettings();
    };
    void check();
}

async function showSettings() {
    const section = document.getElementById("settings")!;
    const captureBox = document.getElementById("capture") as HTMLInputElement;
    const captureLabel = document.getElementById("capture-label")!;
    const captureHint = document.getElementById("capture-hint")!;
    const captureNote = document.getElementById("capture-note")!;
    const pairingNote = document.getElementById("pairing-note")!;
    const limitsNote = document.getElementById("limits-note")!;
    const sizeLabel = document.getElementById("size-label")!;
    const sizeInput = document.getElementById("min-size") as HTMLInputElement;
    const sizeHint = document.getElementById("size-hint")!;
    const hostsLabel = document.getElementById("hosts-label")!;
    const hostForm = document.getElementById("host-form") as HTMLFormElement;
    const hostInput = document.getElementById("host-input") as HTMLInputElement;
    const hostError = document.getElementById("host-error")!;
    const hostsEmpty = document.getElementById("hosts-empty")!;
    const hostsList = document.getElementById("hosts")!;

    let settings: Settings = await loadSettings(ext.storage.local);

    const render = () => {
        const view = settingsView(settings, paired);
        captureLabel.textContent = view.captureLabel;
        captureBox.checked = view.captureValue;
        captureHint.textContent = view.captureHint;
        pairingNote.hidden = view.pairingNote === null;
        pairingNote.textContent = view.pairingNote ?? "";
        sizeLabel.textContent = view.sizeLabel;
        sizeInput.value = view.sizeValue;
        sizeHint.textContent = view.sizeHint;
        limitsNote.hidden = view.limitsNote === null;
        limitsNote.textContent = view.limitsNote ?? "";
        hostsLabel.textContent = view.hostsLabel;
        hostsEmpty.hidden = view.empty === null;
        hostsEmpty.textContent = view.empty ?? "";
        hostsList.replaceChildren(
            ...view.hosts.map((entry) => {
                const li = document.createElement("li");
                const name = document.createElement("span");
                name.textContent = entry.host;
                const remove = document.createElement("button");
                remove.type = "button";
                remove.textContent = "Remove";
                remove.setAttribute("aria-label", entry.removeLabel);
                remove.addEventListener("click", () => {
                    settings = removeHost(settings, entry.host);
                    void change();
                });
                li.append(name, remove);
                return li;
            }),
        );
    };
    renderSettings = render;
    const change = async () => {
        render();
        try {
            await saveSettings(ext.storage.local, settings);
        } catch {
            hostError.hidden = false;
            hostError.textContent = "Could not save the settings.";
        }
    };

    // A click, not a change: the browser shows its question only for a request made while answering one, so the request
    // is started here, before anything is awaited (`turnCaptureOn` starts it, and saves the setting, in its first moment).
    const capture: CaptureSwitchDeps = {
        save: (next) => saveSettings(ext.storage.local, next),
        request: (access) => ext.permissions.request(access),
        remove: (access) => ext.permissions.remove(access),
    };
    captureBox.addEventListener("click", () => {
        if (!captureBox.checked) {
            captureNote.textContent = "";
            void (async () => {
                const result = await turnCaptureOff(settings, capture);
                // Only the switch: the size and the sites may have been changed while this waited.
                settings = {
                    ...settings,
                    captureDownloads: result.settings.captureDownloads,
                };
                render();
                if (!result.saved) {
                    hostError.hidden = false;
                    hostError.textContent = "Could not save the settings.";
                }
            })();
            return;
        }
        void (async () => {
            const result = await turnCaptureOn(settings, capture);
            settings = {
                ...settings,
                captureDownloads: result.settings.captureDownloads,
            };
            captureNote.textContent = captureAccessMessage(
                result.granted,
                paired,
            );
            render();
            if (!result.saved) {
                hostError.hidden = false;
                hostError.textContent = "Could not save the settings.";
            }
        })();
    });
    sizeInput.addEventListener("change", () => {
        const value = Number(sizeInput.value);
        settings = {
            ...settings,
            minSizeMb: Number.isFinite(value) && value > 0 ? value : 0,
        };
        void change();
    });
    hostForm.addEventListener("submit", (event) => {
        event.preventDefault();
        const result = addHost(settings, hostInput.value);
        hostError.hidden = result.error === undefined;
        hostError.textContent = result.error ?? "";
        if (result.error !== undefined) return;
        settings = result.settings;
        hostInput.value = "";
        void change();
    });

    render();
    section.hidden = false;

    // Capture that is on but has lost what it needs (the person took the access back in the browser) does nothing: say so.
    if (settings.captureDownloads) {
        try {
            if (!(await ext.permissions.contains(CAPTURE_ACCESS))) {
                captureNote.textContent =
                    "Capture is on, but the browser no longer gives the extension the access it needs. Turn it off and on again to ask again.";
            }
        } catch {
            // Nothing to say.
        }
    }
}

if (!target) void showSettings();
