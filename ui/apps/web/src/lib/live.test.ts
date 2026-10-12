import { describe, expect, test } from "bun:test";
import { createLive } from "./live";

describe("createLive", () => {
    test("get() sees a patch made earlier in the same tick", () => {
        const live = createLive({ a: 1, b: "x" });
        live.patch({ a: 2 });
        expect(live.get().a).toBe(2);
        expect(live.get().b).toBe("x");
    });

    test("a function patch receives the state the previous patch left", () => {
        const live = createLive({ n: 0 });
        live.patch({ n: 1 });
        live.patch((s) => ({ n: s.n + 10 }));
        expect(live.get().n).toBe(11);
    });

    test("a patch that changes nothing notifies no one and keeps the snapshot", () => {
        const live = createLive({ a: 1, list: [] as number[] });
        const before = live.get();
        let calls = 0;
        live.subscribe(() => calls++);
        live.patch({ a: 1, list: before.list });
        live.patch(() => ({}));
        expect(calls).toBe(0);
        expect(live.get()).toBe(before);
    });

    test("an effective patch notifies once and yields a new snapshot", () => {
        const live = createLive({ a: 1, b: 1 });
        const before = live.get();
        let calls = 0;
        live.subscribe(() => calls++);
        live.patch({ a: 2, b: 2 });
        expect(calls).toBe(1);
        expect(live.get()).not.toBe(before);
    });

    test("unsubscribe stops notifications", () => {
        const live = createLive({ a: 1 });
        let calls = 0;
        const stop = live.subscribe(() => calls++);
        live.patch({ a: 2 });
        stop();
        live.patch({ a: 3 });
        expect(calls).toBe(1);
    });
});
