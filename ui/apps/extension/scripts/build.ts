import { cp, mkdir, rm } from "node:fs/promises";
import { join } from "node:path";
import { manifestFor, type Browser } from "../src/manifest";

const root = join(import.meta.dir, "..");
const dist = join(root, "dist");

await rm(dist, { recursive: true, force: true });

for (const browser of [
    "chrome",
    "firefox",
] as Browser[]) {
    const out = join(dist, browser);
    await mkdir(out, { recursive: true });
    const built = await Bun.build({
        entrypoints: [
            join(root, "src/background.ts"),
            join(root, "src/popup.ts"),
        ],
        outdir: out,
        target: "browser",
        format: "iife",
        minify: false,
    });
    if (!built.success) {
        for (const log of built.logs) console.error(log);
        process.exit(1);
    }
    await cp(join(root, "src/popup.html"), join(out, "popup.html"));
    await cp(join(root, "src/popup.css"), join(out, "popup.css"));
    await Bun.write(
        join(out, "manifest.json"),
        `${JSON.stringify(manifestFor(browser), null, 4)}\n`,
    );
    console.log(`built dist/${browser}`);
}
