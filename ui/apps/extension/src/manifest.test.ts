import { describe, expect, test } from "bun:test";
import contract from "../contract/bridge.json";
import pkg from "../package.json";
import { CAPTURE_ACCESS } from "./capture";
import { chromeIdOf, manifestFor } from "./manifest";

describe("manifestFor", () => {
    for (const browser of [
        "chrome",
        "firefox",
    ] as const) {
        const manifest = manifestFor(browser);

        test(`${browser}: asks for four permissions at install, and downloads only when capture is turned on`, () => {
            expect(
                [
                    ...manifest.permissions,
                ].sort(),
            ).toEqual([
                "activeTab",
                "contextMenus",
                "cookies",
                "storage",
            ]);
            expect(manifest.optional_permissions).toEqual([
                "downloads",
            ]);
            expect(manifest.host_permissions).toEqual([
                "http://127.0.0.1/*",
                "http://localhost/*",
                "<all_urls>",
            ]);
        });

        test(`${browser}: what capture asks for is what the manifest lets it ask for, and nothing it holds already`, () => {
            for (const permission of CAPTURE_ACCESS.permissions) {
                expect(manifest.optional_permissions).toContain(permission);
                expect(manifest.permissions).not.toContain(permission);
            }
            for (const origin of CAPTURE_ACCESS.origins) {
                expect(manifest.host_permissions).toContain(origin);
            }
        });

        test(`${browser}: is a Manifest V3 extension at the package's version`, () => {
            expect(manifest.manifest_version).toBe(3);
            expect(manifest.version).toBe(pkg.version);
            expect(manifest.action.default_popup).toBe("popup.html");
        });

        test(`${browser}: has no remote code and no content scripts`, () => {
            expect(manifest.content_scripts).toBeUndefined();
            expect(manifest.web_accessible_resources).toBeUndefined();
            expect(manifest.content_security_policy).toBeUndefined();
        });
    }

    test("chrome: carries the key whose id the app trusts by default", () => {
        const manifest = manifestFor("chrome");

        expect(manifest.key).toBeDefined();
        expect(`chrome-extension://${chromeIdOf(manifest.key!)}`).toBe(
            contract.chromeOrigin,
        );
        expect(manifest.background).toEqual({
            service_worker: "background.js",
        });
        expect(manifest.browser_specific_settings).toBeUndefined();
    });

    test("firefox: has a gecko id and a minimum version, runs the background as a script, and has no key", () => {
        const manifest = manifestFor("firefox");

        expect(manifest.key).toBeUndefined();
        expect(manifest.background).toEqual({
            scripts: [
                "background.js",
            ],
        });
        expect(manifest.browser_specific_settings?.gecko.id).toMatch(/@/);
        expect(
            Number.parseInt(
                manifest.browser_specific_settings!.gecko.strict_min_version,
            ),
        ).toBeGreaterThanOrEqual(128);
    });
});

describe("chromeIdOf", () => {
    test("is 32 letters from a to p", () => {
        expect(chromeIdOf(manifestFor("chrome").key!)).toMatch(/^[a-p]{32}$/);
    });
});
