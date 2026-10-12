import { describe, expect, test } from "bun:test";
import { createLive } from "@/lib/live";
import { createInitialState } from "./store";
import { createToasts } from "./toasts";

function setup() {
    const store = createLive(createInitialState());
    let notified = 0;
    store.subscribe(() => notified++);
    return { store, toasts: createToasts(store), notified: () => notified };
}

describe("toasts", () => {
    test("notify raises a toast", () => {
        const { store, toasts } = setup();
        toasts.notify("info", "hello");
        expect(store.get().toasts.map((t) => t.message)).toEqual([
            "hello",
        ]);
    });

    test("sweep with nothing expired writes nothing", () => {
        const { store, toasts, notified } = setup();
        toasts.notify("info", "hello");
        const before = store.get();
        const seen = notified();

        toasts.sweep(Date.now());

        expect(notified()).toBe(seen);
        expect(store.get()).toBe(before);
    });

    test("sweep drops an expired toast in one write", () => {
        const { store, toasts, notified } = setup();
        toasts.notify("info", "hello");
        const seen = notified();

        toasts.sweep(Date.now() + 60_000);

        expect(store.get().toasts).toEqual([]);
        expect(notified()).toBe(seen + 1);
    });

    test("dismiss removes one toast by id", () => {
        const { store, toasts } = setup();
        toasts.notify("error", "a");
        toasts.notify("error", "b");
        toasts.dismiss(store.get().toasts[0]!.id);
        expect(store.get().toasts.map((t) => t.message)).toEqual([
            "b",
        ]);
    });
});
