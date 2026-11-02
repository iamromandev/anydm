import { ext, isFirefox } from "./api";
import { Capturer, hookDownloads, lazyDownloads } from "./capture";
import { BridgeClient } from "./client";
import { cookiesFor, hasSeveralStores } from "./cookies";
import type { Deps } from "./flow";
import { loadPairing } from "./pairing";
import { serveBridge } from "./rpc";
import { loadSettings } from "./settings";
import { popupPath, targetOf } from "./target";

const MENU = "anydm-download";

ext.runtime.onInstalled.addListener(() => {
    // The menu outlives the worker; an update would otherwise make it twice.
    void ext.contextMenus.removeAll().then(() =>
        ext.contextMenus.create({
            id: MENU,
            title: "Download with anydm",
            contexts: [
                "link",
                "image",
                "video",
                "audio",
            ],
        }),
    );
});

// Nothing is read or kept here: the page that opens does the work, from what is in its address.
ext.contextMenus.onClicked.addListener((info, tab) => {
    if (info.menuItemId !== MENU) return;
    const target = targetOf(info, tab);
    if (!target) return;
    void ext.windows.create({
        url: ext.runtime.getURL(popupPath(target)),
        type: "popup",
        width: 420,
        height: 340,
    });
});

const everySite = {
    origins: [
        "<all_urls>",
    ],
};

// The port that worked last, kept only while this worker lives; a worker the browser stopped searches every port again.
let lastPort: number | undefined;

// This worker is the one owner of the session with the app (the app has one session per paired browser, and a new hello ends
// the last): it says hello, signs, and answers the pages that ask. See `client.ts` and `rpc.ts`.
const bridge = new BridgeClient({
    fetch: (url, init) => fetch(url, init),
    storage: ext.storage.local,
});
serveBridge(bridge, ext.runtime);

// The same flow the window runs, with the browser's own pieces. It can't ask for site access (that needs a click), so a
// browser that has not given it leaves the download to itself.
const flow: Deps = {
    bridge,
    userAgent: navigator.userAgent,
    hasHostAccess: () => ext.permissions.contains(everySite),
    requestHostAccess: async () => false,
    cookiesFor: (url, tabId) => cookiesFor(ext.cookies, url, tabId),
    lastPort: {
        get: () => lastPort,
        set: (port) => {
            lastPort = port;
        },
    },
    setTimer: (callback, ms) => setTimeout(callback, ms),
    clearTimer: (handle) => clearTimeout(handle as number),
};

const capturer = new Capturer({
    flow,
    settings: () => loadSettings(ext.storage.local),
    // Looked up when used: without the optional `downloads` permission the browser has no `ext.downloads`.
    downloads: lazyDownloads(() => ext.downloads),
    paired: async () => (await loadPairing(ext.storage.local)) !== null,
    verify: (port) => bridge.verify(port),
    ambiguousStores: () => hasSeveralStores(ext.cookies, isFirefox),
});

// Taking over a download: see `capture.ts`. The listener does not wait for anything, so the browser's download goes on
// while anydm is asked, and is cancelled only once anydm has added it. Nothing is read from the item beyond its address,
// its referrer and its size, and none of it is kept. The `downloads` permission is optional, so this listens only once the
// person has granted it (when they turn capture on) and stops when they turn it off and the permission is given back; with it
// absent the extension does not touch downloads at all.
hookDownloads(
    () => ext.downloads,
    (item) => {
        void capturer.handle(item);
    },
    ext.permissions.onAdded,
    // Turning capture off gives the permission back (`popup.ts`): the listener goes with it, and comes back if it is granted again.
    ext.permissions.onRemoved,
);
