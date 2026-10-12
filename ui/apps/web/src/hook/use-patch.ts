import { useCallback, useState } from "react";

/** What `patch` takes: the keys to change, or a function of the latest state. */
export type Patch<T> = Partial<T> | ((held: T) => Partial<T>);

/**
 * State that is an object, changed a few keys at a time: the shape a form
 * takes. `patch` merges, so a handler names only what it changes. A handler
 * that writes after an `await` passes a function, so it merges into the state
 * as it is then and not as it was when the handler started.
 */
export function usePatch<T extends object>(
    initial: T | (() => T),
): [
    T,
    (changes: Patch<T>) => void,
] {
    const [
        state,
        setState,
    ] = useState(initial);
    const patch = useCallback(
        (changes: Patch<T>) =>
            setState((held) => ({
                ...held,
                ...(typeof changes === "function" ? changes(held) : changes),
            })),
        [],
    );
    return [
        state,
        patch,
    ];
}
