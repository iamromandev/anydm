/** What the user chose: whether anydm takes over the browser's downloads, and what it leaves to the browser (requirements 14 and 15). */
export interface Settings {
    /** The browser's own downloads are taken over by anydm. Off until the user turns it on. */
    captureDownloads: boolean;
    /** Downloads of a known size under this many MiB (1,048,576 bytes) are left to the browser; 0 turns the check off. */
    minSizeMb: number;
    /** Downloads from these hosts, and their subdomains, are left to the browser. */
    excludedHosts: string[];
}

export const DEFAULT_SETTINGS: Settings = {
    captureDownloads: false,
    minSizeMb: 0,
    excludedHosts: [],
};

export const SETTINGS_KEY = "anydm.settings";

/** The part of `storage.local` this uses; Chrome and Firefox both answer with promises in Manifest V3. */
export interface SettingsStorage {
    get(key: string): Promise<Record<string, unknown>>;
    set(items: Record<string, unknown>): Promise<void>;
}

const MIB = 1024 * 1024;
// An underscore is not allowed in a host name by the old rules, but real hosts have one (`my_files.example.com`).
const LABEL = /^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$/;
// Addresses that are not a site, and must not be read as the host after their colon (`mailto:me@example.com`).
const NOT_A_SITE =
    /^(?:mailto|tel|sms|javascript|data|about|blob|urn|magnet):/i;

/** The host a user's entry names, or null when it does not look like one. Scheme, credentials, port and path are dropped. */
export function normalizeHost(input: string): string | null {
    let text = input.trim();
    if (text === "" || NOT_A_SITE.test(text)) return null;
    text = text.replace(/^[a-z][a-z0-9+.-]*:\/\//i, "");
    // Nothing before the first slash is no host ("/path", "http:///path").
    if (text.startsWith("/")) return null;
    text = `http://${text}`;
    let host: string;
    try {
        host = new URL(text).hostname.toLowerCase();
    } catch {
        return null;
    }
    host = host.replace(/^(\*\.|\.)+/, "").replace(/\.$/, "");
    if (host === "" || host.length > 253) return null;
    return host.split(".").every((label) => LABEL.test(label)) ? host : null;
}

/** Whether the address is on one of [hosts] or a subdomain of one. */
export function hostExcluded(hosts: string[], url: string): boolean {
    let host: string;
    try {
        host = new URL(url).hostname.toLowerCase().replace(/\.$/, "");
    } catch {
        return false;
    }
    if (host === "") return false;
    return hosts.some((h) => host === h || host.endsWith(`.${h}`));
}

/**
 * Why a download is left to the browser, or null when it goes to the app. An unknown size ([size] null) is never "small".
 */
export function skipReason(
    settings: Pick<Settings, "minSizeMb" | "excludedHosts">,
    url: string,
    size: number | null,
): "excluded" | "small" | null {
    if (hostExcluded(settings.excludedHosts, url)) return "excluded";
    if (
        settings.minSizeMb > 0 &&
        size !== null &&
        size < settings.minSizeMb * MIB
    ) {
        return "small";
    }
    return null;
}

/** Adds an entry as a host; a blank or repeated one changes nothing, one that is not a host is refused with a reason. */
export function addHost(
    settings: Settings,
    input: string,
): { settings: Settings; error?: string } {
    if (input.trim() === "") return { settings };
    const host = normalizeHost(input);
    if (host === null) {
        return { settings, error: "That does not look like a site address." };
    }
    if (settings.excludedHosts.includes(host)) return { settings };
    return {
        settings: {
            ...settings,
            excludedHosts: [
                ...settings.excludedHosts,
                host,
            ],
        },
    };
}

export function removeHost(settings: Settings, host: string): Settings {
    return {
        ...settings,
        excludedHosts: settings.excludedHosts.filter((h) => h !== host),
    };
}

/** Settings from whatever was stored: each broken part falls back to its default. */
function clean(raw: unknown): Settings {
    if (typeof raw !== "object" || raw === null) return DEFAULT_SETTINGS;
    const { captureDownloads, minSizeMb, excludedHosts } = raw as Record<
        string,
        unknown
    >;
    let result: Settings = {
        // Only a real true turns it on: anything odd in the store means the browser keeps its downloads.
        captureDownloads: captureDownloads === true,
        minSizeMb:
            typeof minSizeMb === "number" &&
            Number.isFinite(minSizeMb) &&
            minSizeMb >= 0
                ? minSizeMb
                : 0,
        excludedHosts: [],
    };
    if (Array.isArray(excludedHosts)) {
        for (const entry of excludedHosts) {
            if (typeof entry === "string") {
                result = addHost(result, entry).settings;
            }
        }
    }
    return result;
}

export async function loadSettings(
    storage: SettingsStorage,
): Promise<Settings> {
    try {
        return clean((await storage.get(SETTINGS_KEY))[SETTINGS_KEY]);
    } catch {
        return DEFAULT_SETTINGS;
    }
}

export async function saveSettings(
    storage: SettingsStorage,
    settings: Settings,
): Promise<void> {
    await storage.set({ [SETTINGS_KEY]: clean(settings) });
}
