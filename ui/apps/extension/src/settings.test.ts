import { describe, expect, test } from "bun:test";
import {
    addHost,
    DEFAULT_SETTINGS,
    hostExcluded,
    loadSettings,
    normalizeHost,
    removeHost,
    saveSettings,
    SETTINGS_KEY,
    skipReason,
    type SettingsStorage,
} from "./settings";

const MB = 1024 * 1024;

function memory(initial: Record<string, unknown> = {}) {
    const data = { ...initial };
    const storage: SettingsStorage = {
        get: async (key) => (key in data ? { [key]: data[key] } : {}),
        set: async (items) => {
            Object.assign(data, items);
        },
    };
    return { storage, data };
}

describe("normalizeHost", () => {
    test("keeps a plain host, lower-cased", () => {
        expect(normalizeHost("Example.COM")).toBe("example.com");
    });

    test("strips scheme, credentials, port, path, query and fragment", () => {
        expect(
            normalizeHost("https://user:pw@Example.com:8443/a/b?q=1#top"),
        ).toBe("example.com");
        expect(normalizeHost("example.com:8080/x")).toBe("example.com");
    });

    test("strips a trailing dot, a leading dot and a leading wildcard", () => {
        expect(normalizeHost("example.com.")).toBe("example.com");
        expect(normalizeHost(".example.com")).toBe("example.com");
        expect(normalizeHost("*.example.com")).toBe("example.com");
    });

    test("trims spaces and accepts a single-label host and an address", () => {
        expect(normalizeHost("  localhost ")).toBe("localhost");
        expect(normalizeHost("192.168.1.5")).toBe("192.168.1.5");
    });

    test("refuses what is not a host", () => {
        for (const bad of [
            "",
            "   ",
            "not a host",
            "-a.com",
            "a..com",
            "http://",
            "/path/only",
            "[::1]",
            "a".repeat(64) + ".com",
        ]) {
            expect(normalizeHost(bad)).toBeNull();
        }
    });
});

describe("normalizeHost: underscores and what is not a host", () => {
    test("takes an underscore in a label, which names of real hosts have", () => {
        expect(normalizeHost("my_files.example.com")).toBe(
            "my_files.example.com",
        );
        expect(normalizeHost("_dmarc.example.com")).toBe("_dmarc.example.com");
        expect(normalizeHost("a_b")).toBe("a_b");
    });

    test("a label still can't start or end with a hyphen", () => {
        expect(normalizeHost("a.-b.com")).toBeNull();
        expect(normalizeHost("a-.com")).toBeNull();
    });

    test("an e-mail link or another address that is not a site is refused, not read as the host after its colon", () => {
        for (const bad of [
            "mailto:me@example.com",
            "MAILTO:me@example.com",
            "tel:+15551234",
            "javascript:alert(1)",
            "data:text/plain,hello",
            "about:blank",
            "blob:https://example.com/1",
        ]) {
            expect(normalizeHost(bad)).toBeNull();
        }
    });

    test("a host with a port or credentials is still read for its host", () => {
        expect(normalizeHost("localhost:8080")).toBe("localhost");
        expect(normalizeHost("user:pw@example.com")).toBe("example.com");
    });
});

describe("hostExcluded", () => {
    const hosts = [
        "example.com",
    ];

    test("matches the host itself and its subdomains", () => {
        expect(hostExcluded(hosts, "https://example.com/f.zip")).toBe(true);
        expect(hostExcluded(hosts, "https://a.example.com/f.zip")).toBe(true);
        expect(hostExcluded(hosts, "https://a.b.EXAMPLE.com:81/f")).toBe(true);
    });

    test("does not match a host that only ends the same way", () => {
        expect(hostExcluded(hosts, "https://notexample.com/f")).toBe(false);
        expect(hostExcluded(hosts, "https://example.com.evil.test/f")).toBe(
            false,
        );
    });

    test("an excluded subdomain does not exclude its parent", () => {
        expect(
            hostExcluded(
                [
                    "a.example.com",
                ],
                "https://example.com/",
            ),
        ).toBe(false);
    });

    test("a link that is not a web address is not excluded", () => {
        expect(hostExcluded(hosts, "magnet:?xt=urn:btih:abc")).toBe(false);
        expect(hostExcluded(hosts, "not a url")).toBe(false);
    });
});

describe("capturing browser downloads", () => {
    test("is off until the user turns it on", async () => {
        expect(DEFAULT_SETTINGS.captureDownloads).toBe(false);
        expect((await loadSettings(memory().storage)).captureDownloads).toBe(
            false,
        );
    });

    test("an old value with no such field loads as off", async () => {
        const { storage } = memory({
            [SETTINGS_KEY]: { minSizeMb: 4, excludedHosts: [] },
        });

        expect((await loadSettings(storage)).captureDownloads).toBe(false);
    });

    test("only a true turns it on: 1, 'true' and 'yes' stay off", async () => {
        for (const raw of [
            1,
            "true",
            "yes",
            null,
            {},
        ]) {
            const { storage } = memory({
                [SETTINGS_KEY]: { captureDownloads: raw },
            });

            expect((await loadSettings(storage)).captureDownloads).toBe(false);
        }
    });

    test("turned on, it is saved and read back, and the rest stays", async () => {
        const { storage } = memory();
        const on = { ...DEFAULT_SETTINGS, captureDownloads: true };

        await saveSettings(storage, on);

        expect(await loadSettings(storage)).toEqual(on);
    });

    test("saving a value that is not a boolean stores off", async () => {
        const { storage } = memory();

        await saveSettings(storage, {
            ...DEFAULT_SETTINGS,
            captureDownloads: "yes" as unknown as boolean,
        });

        expect((await loadSettings(storage)).captureDownloads).toBe(false);
    });
});

describe("skipReason", () => {
    const url = "https://files.test/a.zip";

    test("a known size under the minimum is left to the browser", () => {
        const s = { minSizeMb: 5, excludedHosts: [] };

        expect(skipReason(s, url, 5 * MB - 1)).toBe("small");
        expect(skipReason(s, url, 0)).toBe("small");
    });

    test("a size at or over the minimum is sent", () => {
        const s = { minSizeMb: 5, excludedHosts: [] };

        expect(skipReason(s, url, 5 * MB)).toBeNull();
        expect(skipReason(s, url, 50 * MB)).toBeNull();
    });

    test("an unknown size is sent", () => {
        expect(skipReason({ minSizeMb: 5, excludedHosts: [] }, url, null)).toBe(
            null,
        );
    });

    test("a minimum of 0 turns the size check off", () => {
        expect(skipReason(DEFAULT_SETTINGS, url, 1)).toBeNull();
    });

    test("an excluded host is left to the browser whatever the size", () => {
        const s = {
            minSizeMb: 0,
            excludedHosts: [
                "files.test",
            ],
        };

        expect(skipReason(s, url, null)).toBe("excluded");
        expect(skipReason(s, url, 900 * MB)).toBe("excluded");
    });

    test("a fraction of a megabyte works", () => {
        const s = { minSizeMb: 0.5, excludedHosts: [] };

        expect(skipReason(s, url, 0.5 * MB - 1)).toBe("small");
        expect(skipReason(s, url, 0.5 * MB)).toBeNull();
    });
});

describe("addHost and removeHost", () => {
    test("adds a normalized host", () => {
        const r = addHost(DEFAULT_SETTINGS, "https://Example.com/x");

        expect(r.error).toBeUndefined();
        expect(r.settings.excludedHosts).toEqual([
            "example.com",
        ]);
    });

    test("ignores a blank entry without an error", () => {
        const r = addHost(DEFAULT_SETTINGS, "   ");

        expect(r.settings).toEqual(DEFAULT_SETTINGS);
        expect(r.error).toBeUndefined();
    });

    test("ignores a duplicate, however it is written", () => {
        const first = addHost(DEFAULT_SETTINGS, "example.com").settings;
        const r = addHost(first, "HTTP://example.com./");

        expect(r.settings.excludedHosts).toEqual([
            "example.com",
        ]);
        expect(r.error).toBeUndefined();
    });

    test("refuses something that is not a host, and says so", () => {
        const r = addHost(DEFAULT_SETTINGS, "not a host");

        expect(r.settings).toEqual(DEFAULT_SETTINGS);
        expect(r.error).toBeDefined();
    });

    test("removes a host and leaves the rest", () => {
        const s = {
            captureDownloads: true,
            minSizeMb: 3,
            excludedHosts: [
                "a.test",
                "b.test",
            ],
        };

        expect(removeHost(s, "a.test")).toEqual({
            captureDownloads: true,
            minSizeMb: 3,
            excludedHosts: [
                "b.test",
            ],
        });
    });
});

describe("loadSettings and saveSettings", () => {
    test("nothing stored gives the defaults", async () => {
        expect(await loadSettings(memory().storage)).toEqual(DEFAULT_SETTINGS);
    });

    test("a saved value is read back", async () => {
        const { storage } = memory();
        const s = {
            captureDownloads: true,
            minSizeMb: 2.5,
            excludedHosts: [
                "example.com",
            ],
        };

        await saveSettings(storage, s);

        expect(await loadSettings(storage)).toEqual(s);
    });

    test("saving stores under one key", async () => {
        const { storage, data } = memory();

        await saveSettings(storage, DEFAULT_SETTINGS);

        expect(Object.keys(data)).toEqual([
            SETTINGS_KEY,
        ]);
    });

    test("corrupt values fall back field by field", async () => {
        for (const raw of [
            "text",
            42,
            null,
            [],
            { minSizeMb: "big", excludedHosts: "x" },
            {
                minSizeMb: -3,
                excludedHosts: [
                    1,
                    null,
                ],
            },
            { minSizeMb: Number.NaN },
        ]) {
            const { storage } = memory({ [SETTINGS_KEY]: raw });

            expect(await loadSettings(storage)).toEqual(DEFAULT_SETTINGS);
        }
    });

    test("keeps the good parts of a half-broken value, cleaned", async () => {
        const { storage } = memory({
            [SETTINGS_KEY]: {
                minSizeMb: 4,
                excludedHosts: [
                    "Example.com",
                    "example.com",
                    "bad host",
                    7,
                    "https://b.test/x",
                ],
            },
        });

        expect(await loadSettings(storage)).toEqual({
            captureDownloads: false,
            minSizeMb: 4,
            excludedHosts: [
                "example.com",
                "b.test",
            ],
        });
    });

    test("a storage that throws gives the defaults", async () => {
        const storage: SettingsStorage = {
            get: async () => {
                throw new Error("no storage");
            },
            set: async () => {},
        };

        expect(await loadSettings(storage)).toEqual(DEFAULT_SETTINGS);
    });
});
