import { ext } from "./api";
import { findBridge, type Fetch } from "./bridge";
import { cookiesFor } from "./cookies";
import { Flow, type Deps, type State } from "./flow";
import { parseTarget } from "./target";
import { statusView, viewOf, type ButtonId, type View } from "./view";

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

const deps: Deps = {
    fetch: fetchFn,
    userAgent: navigator.userAgent,
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

const target = parseTarget(location.search);

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
        const found = await findBridge(fetchFn, lastPort.get());
        if (found) lastPort.set(found.port);
        show(statusView(found, origin), (id) => {
            if (id === "copy") void copyOrigin();
            else void check();
        });
    };
    void check();
}
