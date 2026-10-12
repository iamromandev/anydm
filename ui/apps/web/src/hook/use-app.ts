import { useEffect, useRef } from "react";
import { createLive } from "@/lib/live";
import { createApp, type App } from "@/state/app";
import { createInitialState, type AppState, type Store } from "@/state/store";
import { useLive } from "./use-live";

/**
 * The app's state, and the modules that change it. Both are made once per
 * page, on first render; the event stream and the timers run for as long as
 * the page is mounted.
 */
export function useApp(): { state: AppState; app: App } {
    const made = useRef<{ store: Store; app: App } | null>(null);
    if (made.current === null) {
        const store = createLive(createInitialState());
        made.current = { store, app: createApp(store) };
    }
    const { store, app } = made.current;

    useEffect(
        () => app.stream.start(),
        [
            app,
        ],
    );

    return { state: useLive(store), app };
}
