import { useCallback, useState } from "react";

/**
 * State that is an object, changed a few keys at a time: the shape a form
 * takes. `patch` merges, so a handler names only what it changes.
 */
export function usePatch<T extends object>(
    initial: T | (() => T),
): [
    T,
    (changes: Partial<T>) => void,
] {
    const [
        state,
        setState,
    ] = useState(initial);
    const patch = useCallback(
        (changes: Partial<T>) => setState((held) => ({ ...held, ...changes })),
        [],
    );
    return [
        state,
        patch,
    ];
}
