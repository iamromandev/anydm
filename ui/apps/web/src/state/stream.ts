import { apiUrl, normalizeApiTask, normalizeCollection } from "@/lib/api";
import { parseDisk } from "@/lib/api/disk";
import {
    FALLBACK_POLL_MS,
    connectionOnError,
    isDegraded,
    shouldWarn,
} from "@/lib/connection";
import { createToast, dismiss, raise } from "@/lib/toast";
import type { App } from "./app";
import type { Store } from "./store";

export interface StreamOptions {
    /** Replaceable so a test can drive the frames by hand. */
    EventSource?: typeof EventSource;
}

/**
 * The app's life while it is on screen: the first loads, the clocks, and the
 * event stream that carries every change after them.
 */
export function createStream(
    store: Store,
    app: App,
    options: StreamOptions = {},
) {
    const Source = options.EventSource ?? globalThis.EventSource;

    /** One frame's JSON, or nothing for a frame that is not JSON. */
    function frame(event: Event, use: (data: any) => void): void {
        try {
            use(JSON.parse((event as MessageEvent).data));
        } catch {
            // malformed event
        }
    }

    /**
     * Starts everything, and returns the one function that stops it all.
     * Calling it twice in a row (StrictMode mounts effects twice) is safe: each
     * call owns its own timers and its own stream.
     */
    function start(): () => void {
        // The remembered order, applied before the first fetch so the list
        // does not arrive newest-first and then reshuffle.
        app.settings.loadStored();
        // Registered before the first request, so its answer is heard.
        const stopListening = app.settings.listenUnauthorized();
        void app.tasks.sync();
        void app.categories.load();
        void app.settings.refreshSearchAvailability();

        const sweeper = setInterval(() => app.toasts.sweep(Date.now()), 500);

        // The retry countdown's clock. It writes only while a deadline is
        // live, so a list with nothing retrying re-renders at the poll's pace
        // rather than every second. The two second grace lets the last tick
        // land, turning "Retrying in 1s" into "Retrying…" instead of freezing
        // on the final second.
        const clock = setInterval(() => {
            const at = Date.now();
            const waiting = store
                .get()
                .tasks.some(
                    (task) =>
                        task.status === "pending" &&
                        task.nextAttemptAt !== undefined &&
                        task.nextAttemptAt > at - 2000,
                );
            if (waiting) store.patch({ now: at });
        }, 1000);

        // The stream carries every change and replays a full snapshot on each
        // connection, so fetching the list on a timer buys nothing. Fetching
        // happens only while the stream is not confirmed live, which also
        // covers a proxy that strips `text/event-stream`: a degraded app, not
        // a frozen one.
        let lastFallbackAt = 0;
        const watch = setInterval(() => {
            const at = Date.now();
            const state = store.get();
            if (!isDegraded(state.connection)) return;

            if (at - lastFallbackAt >= FALLBACK_POLL_MS) {
                lastFallbackAt = at;
                void app.tasks.sync();
            }

            if (
                state.outageToastId === null &&
                // A refused key already has the modal saying so; "lost
                // contact" would send someone to check the wrong thing.
                state.apiKeyMessage === null &&
                shouldWarn(state.connection, state.degradedSince, at)
            ) {
                const toast = createToast(
                    "error",
                    "Lost contact with the API. Still trying, and the list may be out of date.",
                    at,
                );
                store.patch((s) => ({
                    outageToastId: toast.id,
                    toasts: raise(s.toasts, toast),
                }));
            }
        }, 1000);

        let events: EventSource | null = null;
        try {
            const source = new Source(
                apiUrl("/download/events", { withKey: true }),
            );
            events = source;
            source.addEventListener("downloads", (event) =>
                frame(event, (rows: any[]) =>
                    app.tasks.merge(rows.map(normalizeApiTask)),
                ),
            );
            source.addEventListener("download", (event) =>
                frame(event, (row) =>
                    app.tasks.merge([
                        normalizeApiTask(row),
                    ]),
                ),
            );
            source.addEventListener("collection", (event) =>
                frame(event, (row) =>
                    app.tasks.merge([
                        normalizeCollection(row),
                    ]),
                ),
            );
            source.addEventListener("progress", (event) =>
                frame(event, (data) => app.tasks.applyProgress(data)),
            );
            source.addEventListener("disk", (event) =>
                frame(event, (data) => {
                    const disk = parseDisk(data);
                    if (disk) store.patch({ disk });
                }),
            );
            source.onopen = () => {
                const warned = store.get().outageToastId;
                store.patch({ connection: "live", degradedSince: null });
                // The snapshot that follows an open replaces whatever went
                // stale during the gap, so nothing else has to be undone.
                if (warned) {
                    store.patch((s) => ({
                        toasts: raise(
                            dismiss(s.toasts, warned),
                            createToast("info", "Back in contact", Date.now()),
                        ),
                        outageToastId: null,
                    }));
                }
            };
            source.onerror = () => {
                // The browser reopens on its own; this only records that it is
                // currently not open, so the footer can say so.
                store.patch((s) => ({
                    connection: connectionOnError(source.readyState),
                    degradedSince: s.degradedSince ?? Date.now(),
                }));
            };
        } catch {
            events = null;
            store.patch((s) => ({
                connection: "offline",
                degradedSince: s.degradedSince ?? Date.now(),
            }));
        }

        return () => {
            stopListening();
            clearInterval(watch);
            clearInterval(sweeper);
            clearInterval(clock);
            events?.close();
        };
    }

    return { start };
}
