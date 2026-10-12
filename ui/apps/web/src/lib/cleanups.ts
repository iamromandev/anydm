/**
 * What an effect run must undo, in the order it was set up.
 *
 * Order matters: a player saves where it was before its media is torn down, and
 * tearing down first would reset the position being saved. A cleanup added
 * after the run was disposed (it was superseded while awaiting) runs at once.
 */
export interface Cleanups {
    readonly disposed: boolean;
    add(fn: () => void): void;
    dispose(): void;
}

export function createCleanups(): Cleanups {
    let fns: Array<() => void> = [];
    let disposed = false;
    return {
        get disposed() {
            return disposed;
        },
        add(fn) {
            if (disposed) fn();
            else fns.push(fn);
        },
        dispose() {
            disposed = true;
            const run = fns;
            fns = [];
            for (const fn of run) fn();
        },
    };
}
