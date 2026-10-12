import { useSyncExternalStore } from "react";
import type { Live } from "@/lib/live";

/** The store's current state; the caller re-renders whenever it changes. */
export function useLive<T extends object>(live: Live<T>): T {
    // The same snapshot on the server, where nothing changes between renders.
    return useSyncExternalStore(live.subscribe, live.get, live.get);
}
