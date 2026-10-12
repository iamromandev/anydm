import { describe, expect, test } from "bun:test";
import { Glob } from "bun";

/**
 * React state is replaced, not assigned to. A `store.x = ...` left over from a
 * port lands on the render's own copy and is lost, with no type error to say
 * so. Components name their state `store` or `form`; none may assign to it.
 */
describe("component state", () => {
    test("no component assigns to its state object", async () => {
        const root = new URL(".", import.meta.url).pathname;
        const offenders: string[] = [];
        for await (const file of new Glob("**/*.tsx").scan(root)) {
            const text = await Bun.file(root + file).text();
            for (const match of text.matchAll(
                /\b(?:store|form)\.\w+(?:\.\w+)*\s*(?:\+|-)?=(?!=)/g,
            )) {
                const line = text.slice(0, match.index).split("\n").length;
                offenders.push(`${file}:${line}: ${match[0]}`);
            }
        }
        expect(offenders).toEqual([]);
    });
});
