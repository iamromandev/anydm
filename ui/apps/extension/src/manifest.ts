import { createHash } from "node:crypto";
import pkg from "../package.json";

export type Browser = "chrome" | "firefox";

/**
 * The public half of the key that fixes the Chrome extension's id, so the app can trust that id by default
 * (`Settings.CHROME_EXTENSION_ORIGIN`). The private half was thrown away: this extension is not published, and a store
 * would give it another id.
 */
const CHROME_KEY =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsaWXL7XMivF3673Ax6CJXkA09m3TXWYXeeYsAoWeKApxdkwukJZRpedXMU+AUuBQixZk1Bn8Du7AKbDrI5KobSyBzQCpPsbwsip9TUQttD1vy15DFlobWIhNVQ9CQfdmm/GrYufzTSpmv7sCmvGyyjqyXQcZ1+aIQcoXSiMclu/YZgV7vmnX78eIbjGZ09PSrAKKmVmnChZu1xQlMI89Wc8hVzxFK9zyMQdiuGyPKUJd36eFOlQe9Ac5zD3MbDjKJ4OgbufF8qQ8LhyXfJCzV0kjQ7bINbzAZtfRIBlY4Qb2bqeQ4CCDNmu5M/HXAy6+lpOSAaNx5MJCzhBnyiqfowIDAQAB";

/** The id Chrome gives an extension with [key]: the first 16 bytes of the key's SHA-256, each nibble written as a letter a-p. */
export function chromeIdOf(key: string): string {
    const digest = createHash("sha256")
        .update(Buffer.from(key, "base64"))
        .digest("hex");
    return [
        ...digest.slice(0, 32),
    ]
        .map((c) => String.fromCharCode(97 + Number.parseInt(c, 16)))
        .join("");
}

export function manifestFor(browser: Browser) {
    const common = {
        manifest_version: 3 as const,
        name: "anydm",
        version: pkg.version,
        description: "Send a link, with its cookies, to the anydm app.",
        permissions: [
            "contextMenus",
            "cookies",
            "activeTab",
        ],
        host_permissions: [
            "http://127.0.0.1/*",
            "http://localhost/*",
            "<all_urls>",
        ],
        action: { default_title: "anydm", default_popup: "popup.html" },
    };
    if (browser === "chrome") {
        return {
            ...common,
            key: CHROME_KEY,
            background: { service_worker: "background.js" },
        } as ManifestOf;
    }
    return {
        ...common,
        background: {
            scripts: [
                "background.js",
            ],
        },
        browser_specific_settings: {
            gecko: { id: "extension@anydm.dev", strict_min_version: "128.0" },
        },
    } as ManifestOf;
}

/** Every key a manifest here can have; the ones a browser does not use are absent. */
export interface ManifestOf {
    manifest_version: 3;
    name: string;
    version: string;
    description: string;
    permissions: string[];
    optional_permissions?: string[];
    host_permissions: string[];
    action: { default_title: string; default_popup: string };
    key?: string;
    background: { service_worker: string } | { scripts: string[] };
    browser_specific_settings?: {
        gecko: { id: string; strict_min_version: string };
    };
    content_scripts?: unknown;
    web_accessible_resources?: unknown;
    content_security_policy?: unknown;
}
