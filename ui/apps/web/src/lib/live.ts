/**
 * A store that lives outside React.
 *
 * Handlers read `get()` after every `await`, so they always see the state as it
 * is when they resume rather than as it was when they were created; a closure
 * over React state cannot promise that. React reads the same store through
 * `useLive`.
 */
export interface Live<T extends object> {
    /** The latest state, including patches made earlier in the same tick. */
    get(): T;
    /** Shallow-merge `next`. A patch that changes no key notifies no one. */
    patch(next: Partial<T> | ((state: T) => Partial<T>)): void;
    subscribe(listener: () => void): () => void;
}

export function createLive<T extends object>(initial: T): Live<T> {
    let state = initial;
    const listeners = new Set<() => void>();

    return {
        get: () => state,
        patch(next) {
            const changes = typeof next === "function" ? next(state) : next;
            const keys = Object.keys(changes) as (keyof T)[];
            if (keys.every((key) => Object.is(state[key], changes[key]))) {
                return;
            }
            // A fresh object each time, so `useSyncExternalStore` sees a new
            // snapshot exactly when something changed.
            state = { ...state, ...changes };
            for (const listener of [
                ...listeners,
            ])
                listener();
        },
        subscribe(listener) {
            listeners.add(listener);
            return () => {
                listeners.delete(listener);
            };
        },
    };
}
