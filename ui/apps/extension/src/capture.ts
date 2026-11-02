import type { Access } from "./api";
import { Flow, type Deps, type State, type Target } from "./flow";
import { DEFAULT_SETTINGS, skipReason, type Settings } from "./settings";

/** The part of the browser's `DownloadItem` this reads. The sizes are -1 when unknown (Chrome), 0 or absent in some browsers. */
export interface DownloadInfo {
    id: number;
    /** The address the download was asked for. */
    url: string;
    /** The address it came from after redirects; absent in some browsers. */
    finalUrl?: string;
    referrer?: string;
    filename?: string;
    mime?: string;
    fileSize?: number;
    totalBytes?: number;
    state?: "in_progress" | "interrupted" | "complete";
    incognito?: boolean;
    /** Set when an extension started the download. */
    byExtensionId?: string;
}

/** The part of the browser's downloads API this uses; Chrome and Firefox both answer with promises in Manifest V3. */
export interface DownloadsApi {
    search(query: { id: number }): Promise<DownloadInfo[]>;
    cancel(id: number): Promise<void>;
    erase(query: { id: number }): Promise<unknown>;
}

/**
 * What turning capture on asks the browser for, in one question: the `downloads` permission (optional in the manifest, so
 * the extension holds it only while capture is wanted) and access to sites (to read the cookies and reach anydm).
 */
export const CAPTURE_ACCESS = {
    permissions: [
        "downloads",
    ],
    origins: [
        "<all_urls>",
    ],
};

/** What turning capture off gives back: the `downloads` permission only. Site access stays: the menu entry needs it too. */
export const DOWNLOADS_PERMISSION = {
    permissions: [
        "downloads",
    ],
};

/** What turning capture on or off needs from the browser. */
export interface CaptureSwitchDeps {
    save(settings: Settings): Promise<void>;
    request(access: Access): Promise<boolean>;
    remove(access: Access): Promise<boolean>;
}

export interface TurnedOn {
    /** What the window shows: capture on only if the browser gave the access. */
    settings: Settings;
    granted: boolean;
    /** Whether the settings kept are the ones in [settings]. */
    saved: boolean;
}

// Runs [start] now, and says how it ended without throwing; a throw before it returns a promise is the same as a rejection.
function attempt<T>(
    start: () => Promise<T>,
): Promise<{ ok: true; value: T } | { ok: false }> {
    try {
        return start().then(
            (value) => ({ ok: true as const, value }),
            () => ({ ok: false as const }),
        );
    } catch {
        return Promise.resolve({ ok: false as const });
    }
}

/**
 * Turns capture on. Both the saving and the browser's question are started now, before anything is awaited: the browser asks
 * only for a request made while a click is being answered, and it closes the toolbar window when it asks, so nothing after
 * that question can be relied on to run. The setting is therefore written first (and put back if the answer is no), instead of
 * after: a window closed by the question must not leave the permission granted with capture off.
 */
export async function turnCaptureOn(
    settings: Settings,
    deps: CaptureSwitchDeps,
): Promise<TurnedOn> {
    const on = { ...settings, captureDownloads: true };
    const off = { ...settings, captureDownloads: false };
    const saving = attempt(() => deps.save(on));
    const asked = attempt(() => deps.request(CAPTURE_ACCESS));
    const granted = await asked;
    let saved = (await saving).ok;
    if (granted.ok && granted.value) {
        if (!saved) saved = (await attempt(() => deps.save(on))).ok;
        return { settings: saved ? on : off, granted: true, saved };
    }
    // No: capture was only on for the length of the question.
    return {
        settings: off,
        granted: false,
        saved: (await attempt(() => deps.save(off))).ok,
    };
}

/**
 * Turns capture off: the setting first, and then the `downloads` permission is given back, so the extension holds it only
 * while capture is wanted. A permission that can't be given back is no reason to keep capture on: with the setting off nothing
 * is taken whatever the browser says. If the setting can't be saved the permission is kept, as the setting still says on.
 */
export async function turnCaptureOff(
    settings: Settings,
    deps: CaptureSwitchDeps,
): Promise<{ settings: Settings; saved: boolean; removed: boolean }> {
    const off = { ...settings, captureDownloads: false };
    const saved = (await attempt(() => deps.save(off))).ok;
    if (!saved) return { settings: off, saved: false, removed: false };
    const removed = await attempt(() => deps.remove(DOWNLOADS_PERMISSION));
    return { settings: off, saved: true, removed: removed.ok && removed.value };
}

/** The part of the browser's downloads API that tells a download began. */
export interface DownloadsEvents {
    onCreated: {
        addListener(listener: (item: DownloadInfo) => void): void;
        removeListener?(listener: (item: DownloadInfo) => void): void;
    };
}

/**
 * Listens for new downloads once the `downloads` permission is there: at once if it is, and when the person grants it later
 * ([permissionAdded]). [downloads] is looked up each time, because without the permission the browser has no `downloads` at
 * all. Registered once, however often the permission is added; when the permission is taken back ([permissionRemoved], which
 * [turnCaptureOff] does) the listener is dropped, so granting it again registers it again.
 */
export function hookDownloads(
    downloads: () => DownloadsEvents | undefined,
    handle: (item: DownloadInfo) => void,
    permissionAdded: { addListener(listener: () => void): void },
    permissionRemoved?: {
        addListener(listener: (removed: Access) => void): void;
    },
) {
    let hooked: DownloadsEvents | null = null;
    const hook = () => {
        if (hooked) return;
        const api = downloads();
        if (!api?.onCreated) return;
        hooked = api;
        api.onCreated.addListener(handle);
    };
    const unhook = (removed: Access) => {
        if (!hooked || !removed.permissions?.includes("downloads")) return;
        const api = hooked;
        hooked = null;
        try {
            api.onCreated.removeListener?.(handle);
        } catch {
            // The browser dropped it with the permission, or can't: a second registration later would do no harm (a download
            // announced twice is handled once).
        }
    };
    hook();
    permissionAdded.addListener(hook);
    permissionRemoved?.addListener(unhook);
}

/**
 * The downloads API looked up when it is used. Without the permission every call fails, which the capturer takes as "the
 * browser can't say": it leaves the download alone.
 */
export function lazyDownloads(
    get: () => DownloadsApi | undefined,
): DownloadsApi {
    const api = (): DownloadsApi => {
        const found = get();
        if (!found) throw new Error("the downloads permission is not held");
        return found;
    };
    return {
        search: async (query) => api().search(query),
        cancel: async (id) => api().cancel(id),
        erase: async (query) => api().erase(query),
    };
}

/** Why a download is not even offered to anydm. */
export type Ignored =
    | "off"
    | "seen"
    /** This browser is not paired with anydm: capture needs a pairing. */
    | "unpaired"
    | "extension"
    | "private"
    | "not-running"
    /** A page saved as HTML (Save page as): anydm would fetch the address, not the page as it is. */
    | "page"
    /** The file already has a name and place: the person chose it (Save page as, Save link as). */
    | "named"
    | "scheme"
    | "excluded"
    | "small";

export type Decision =
    | { capture: true; target: Target }
    | { capture: false; reason: Exclude<Ignored, "seen"> };

const isWeb = (url: string) => {
    try {
        const scheme = new URL(url).protocol;
        return scheme === "http:" || scheme === "https:";
    } catch {
        return false;
    }
};

// A size the browser does not know is -1, but 0 and a missing value are also seen; none of them is "small".
function knownSize(item: DownloadInfo): number | null {
    for (const size of [
        item.totalBytes,
        item.fileSize,
    ]) {
        if (typeof size === "number" && size > 0) return size;
    }
    return null;
}

/**
 * Whether a download that has just begun goes to anydm, and as what. Everything here is read off the item, so the
 * browser is never waited for: the name and the kind come from anydm's own look at the link, not from the item, which
 * has no file name yet when it begins.
 */
export function decide(item: DownloadInfo, settings: Settings): Decision {
    if (!settings.captureDownloads) return { capture: false, reason: "off" };
    // Another extension's download is its own business, and cancelling it behind its back would break that extension.
    if (item.byExtensionId) return { capture: false, reason: "extension" };
    // The cookies of a private window can't be told apart from the rest here: the item has no tab.
    if (item.incognito) return { capture: false, reason: "private" };
    if (item.state !== undefined && item.state !== "in_progress") {
        return { capture: false, reason: "not-running" };
    }
    // A saved page (Ctrl+S) is the page as the browser has it, which anydm can't fetch from its address; and a file that is
    // already named was named by the person, not by the server: neither is a link to a file.
    if (item.mime?.split(";")[0]?.trim().toLowerCase() === "text/html") {
        return { capture: false, reason: "page" };
    }
    if (item.filename) return { capture: false, reason: "named" };
    const from = [
        item.url,
        ...(item.finalUrl
            ? [
                  item.finalUrl,
              ]
            : []),
    ];
    if (!from.every(isWeb)) return { capture: false, reason: "scheme" };
    if (from.some((url) => skipReason(settings, url, null) === "excluded")) {
        return { capture: false, reason: "excluded" };
    }
    if (skipReason(settings, item.url, knownSize(item)) === "small") {
        return { capture: false, reason: "small" };
    }
    return {
        capture: true,
        target: {
            // The link as it was asked for: anydm follows the redirects itself.
            url: item.url,
            pageUrl:
                item.referrer && isWeb(item.referrer)
                    ? item.referrer
                    : undefined,
            automatic: true,
        },
    };
}

/** Why the browser's own download was left to run. */
export type Kept =
    | Exclude<State["step"], "ready" | "added">
    // The browser finished (or lost) the download before it could be cancelled.
    | "finished"
    | "unknown"
    | "cancel-failed"
    | "error"
    // The app that said it has the link could not be proved to be the app, once more, just before the cancel.
    | "unverified"
    // The cookies were too many to send, so anydm read the link as a stranger and might get another file.
    | "cookies-dropped"
    // The size anydm found is not the size the browser knows: it would be another file.
    | "size-differs"
    // The person turned capture off while this was being read.
    | "off"
    // More than one cookie store (Firefox containers): the download can't say which one its cookies are in.
    | "containers"
    // Too many downloads were waiting to be looked at.
    | "busy";

export type Outcome =
    | { kind: "ignored"; reason: Ignored }
    /** anydm may or may not have the link, but the browser's download was not touched. */
    | { kind: "kept"; reason: Kept }
    /** anydm has the link and the browser's download was cancelled. */
    | { kind: "taken" };

export interface CaptureDeps {
    /** What the flow needs; its `settings` is replaced by the settings this decided with. */
    flow: Deps;
    settings(): Promise<Settings>;
    downloads: DownloadsApi;
    /** Whether this browser has a pairing; capture needs one. */
    paired(): Promise<boolean>;
    /** Proves once more that the program on [port] is the app this browser is paired with (a fresh hello). */
    verify(port: number): Promise<boolean>;
    /** Whether the browser has more than one cookie store a download could belong to; when it can't say, the answer is yes. */
    ambiguousStores(): Promise<boolean>;
    /** How many downloads are read at once (default 3). */
    limit?: number;
    /** How many more may wait for their turn (default 30); the rest are left to the browser. */
    maxWaiting?: number;
}

/** How many download ids are remembered, so one that is announced twice is handled once. */
const MAX_SEEN = 1000;

const LIMIT = 3;
const MAX_WAITING = 30;

/** What the browser says of a download now. The state is "unknown" when it can't say, which is never a reason to act. */
interface Look {
    state: NonNullable<DownloadInfo["state"]> | "gone" | "unknown";
    totalBytes: number | null;
}

const stepOf = (flow: Flow): State["step"] => flow.state.step;

/**
 * Takes over the browser's downloads for anydm, one at a time, without ever losing one: the browser's own download is
 * cancelled and erased only after anydm has answered that it added the link, and only if the browser has not finished
 * it by then. Anything else (anydm off, a refusal, an error, a slow answer) leaves the browser's download as it is.
 */
export class Capturer {
    private readonly seen = new Set<number>();
    private active = 0;
    private readonly waiting: (() => void)[] = [];

    constructor(private readonly deps: CaptureDeps) {}

    /** Never throws. A second call for the same download does nothing. */
    async handle(item: DownloadInfo): Promise<Outcome> {
        if (this.seen.has(item.id)) return { kind: "ignored", reason: "seen" };
        this.remember(item.id);
        try {
            return await this.take(item);
        } catch {
            return { kind: "kept", reason: "error" };
        }
    }

    private async take(item: DownloadInfo): Promise<Outcome> {
        const settings = await this.settings();
        const decision = decide(item, settings);
        if (!decision.capture) {
            return { kind: "ignored", reason: decision.reason };
        }
        // Capture is for a browser that is paired: nothing about the download is sent to anything before that.
        if (!(await this.isPaired())) {
            return { kind: "ignored", reason: "unpaired" };
        }
        if (await this.severalStores()) {
            return { kind: "kept", reason: "containers" };
        }

        if (!(await this.enter())) return { kind: "kept", reason: "busy" };
        try {
            return await this.read(item, decision.target);
        } finally {
            this.leave();
        }
    }

    private async read(item: DownloadInfo, target: Target): Promise<Outcome> {
        // The turn may have been a while coming: the switch may have been turned off since.
        const settings = await this.settings();
        if (!settings.captureDownloads) return { kind: "kept", reason: "off" };

        // The same flow as the context menu's: cookies, User-Agent and referrer go the same way, once the app has proved
        // itself. The settings it filters by are the ones this decided with.
        const flow = new Flow(
            { ...this.deps.flow, settings: async () => settings },
            target,
            () => {},
        );
        try {
            return await this.run(item, flow);
        } finally {
            // Nobody will look at this flow again: its expiry timer must not outlive it.
            flow.close();
        }
    }

    private async run(item: DownloadInfo, flow: Flow): Promise<Outcome> {
        await flow.start();
        const read = flow.state;
        if (read.step !== "ready") {
            return { kind: "kept", reason: keptFor(read.step) };
        }
        // Not the app this browser is paired with: nothing goes on. (The flow does not get here without it.)
        if (!read.verified) return { kind: "kept", reason: "unverified" };
        // Without its cookies anydm read the link as a stranger would, and may have been shown another file.
        if (read.droppedCookies) {
            return { kind: "kept", reason: "cookies-dropped" };
        }

        // A tiny download can be over while anydm was reading the link; then there is nothing to take over.
        const before = await this.look(item.id);
        if (before.state !== "in_progress") {
            return { kind: "kept", reason: unlessRunning(before.state) };
        }
        // The browser may have learned the size since it announced the download. A size it knows that anydm did not see is
        // not the same file.
        const known = before.totalBytes ?? positive(item.totalBytes);
        if (known !== null && read.size !== null && read.size !== known) {
            return { kind: "kept", reason: "size-differs" };
        }
        // Turned off while the link was being read: it is not taken.
        if (!(await this.settings()).captureDownloads) {
            return { kind: "kept", reason: "off" };
        }

        await flow.confirm();
        const confirmed = flow.state;
        if (confirmed.step !== "added") {
            return { kind: "kept", reason: keptFor(confirmed.step) };
        }

        // anydm says it has it. Before the browser's copy goes, the app is proved once more: the answer to /confirm is not
        // signed, and something else may have taken the port since the hello.
        const port = flow.foundPort;
        if (port === undefined || !(await this.verifies(port))) {
            return { kind: "kept", reason: "unverified" };
        }
        // Only now does the browser's copy go, and only if the browser is still downloading it.
        const running = await this.look(item.id);
        if (running.state !== "in_progress") {
            return { kind: "kept", reason: unlessRunning(running.state) };
        }
        try {
            await this.deps.downloads.cancel(item.id);
        } catch {
            return { kind: "kept", reason: "cancel-failed" };
        }
        // A cancel of a download that has just finished does nothing; erasing it would drop it from the browser's list.
        const after = (await this.look(item.id)).state;
        if (after === "complete") return { kind: "kept", reason: "finished" };
        if (after !== "gone" && after !== "unknown") {
            try {
                await this.deps.downloads.erase({ id: item.id });
            } catch {
                // Cancelled but still listed: harmless.
            }
        }
        return { kind: "taken" };
    }

    private async settings(): Promise<Settings> {
        try {
            return await this.deps.settings();
        } catch {
            return DEFAULT_SETTINGS;
        }
    }

    private async isPaired(): Promise<boolean> {
        try {
            return await this.deps.paired();
        } catch {
            return false;
        }
    }

    // When it can't be told, it is taken to be several: the browser keeps the download.
    private async severalStores(): Promise<boolean> {
        try {
            return await this.deps.ambiguousStores();
        } catch {
            return true;
        }
    }

    private async verifies(port: number): Promise<boolean> {
        try {
            return await this.deps.verify(port);
        } catch {
            return false;
        }
    }

    // What the browser says the download is doing now; "unknown" when it can't say, which is never a reason to act.
    private async look(id: number): Promise<Look> {
        try {
            const [
                found,
            ] = await this.deps.downloads.search({ id });
            if (!found) return { state: "gone", totalBytes: null };
            return {
                state: found.state ?? "unknown",
                totalBytes: positive(found.totalBytes),
            };
        } catch {
            return { state: "unknown", totalBytes: null };
        }
    }

    // A place among the downloads being read, or false when too many are already waiting for one.
    private async enter(): Promise<boolean> {
        if (this.active < (this.deps.limit ?? LIMIT)) {
            this.active += 1;
            return true;
        }
        if (this.waiting.length >= (this.deps.maxWaiting ?? MAX_WAITING)) {
            return false;
        }
        // The place is handed over by [leave] with the count unchanged.
        await new Promise<void>((resolve) => this.waiting.push(resolve));
        return true;
    }

    private leave() {
        const next = this.waiting.shift();
        if (next) next();
        else this.active -= 1;
    }

    private remember(id: number) {
        this.seen.add(id);
        if (this.seen.size <= MAX_SEEN) return;
        const oldest = this.seen.values().next().value;
        if (oldest !== undefined) this.seen.delete(oldest);
    }
}

const positive = (size: number | undefined): number | null =>
    typeof size === "number" && size > 0 ? size : null;

function keptFor(step: State["step"]): Kept {
    return step === "ready" || step === "added" ? "error" : step;
}

function unlessRunning(state: string): Kept {
    return state === "unknown" ? "unknown" : "finished";
}
