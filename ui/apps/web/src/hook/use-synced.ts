import { useCallback, useRef, useState } from "react";

/**
 * State that can be read at once, as well as rendered.
 *
 * `ref.current` is the latest value the moment `set` returns, so an event
 * handler that reads, changes and writes in a burst (a stream's frames, one
 * after another) sees its own earlier writes, which a render's closure does
 * not until the next render.
 */
export function useSynced<T>(initial: T): [
    T,
    (next: T | ((held: T) => T)) => void,
    { readonly current: T },
] {
    const [
        value,
        setValue,
    ] = useState(initial);
    const ref = useRef(value);
    const set = useCallback((next: T | ((held: T) => T)) => {
        const resolved =
            typeof next === "function"
                ? (next as (held: T) => T)(ref.current)
                : next;
        ref.current = resolved;
        setValue(resolved);
    }, []);
    return [
        value,
        set,
        ref,
    ];
}
