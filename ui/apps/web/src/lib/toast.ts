/**
 * The transient messages the app shows in the corner, and the rules for them.
 *
 * Everything here is pure so the awkward parts — what expires, what survives,
 * which status change is worth interrupting someone for — are testable without
 * a browser. The route owns the list and the clock that prunes it.
 */

export type ToastTone = "info" | "success" | "error";

export type Toast = {
    id: string;
    tone: ToastTone;
    message: string;
    /**
     * When the toast disappears on its own, or `null` for one that waits to be
     * dismissed. An error waits: it is the only kind that can arrive while the
     * person is looking elsewhere and still need reading afterwards.
     */
    expiresAt: number | null;
};

/** Toasts on screen at once. Older ones are dropped rather than stacked. */
export const TOAST_LIMIT = 4;

/** How long a toast that expires stays up. */
export const TOAST_TTL_MS = 5000;

let sequence = 0;

export function createToast(
    tone: ToastTone,
    message: string,
    now: number,
): Toast {
    sequence += 1;
    return {
        id: `${now}-${sequence}`,
        tone,
        message,
        expiresAt: tone === "error" ? null : now + TOAST_TTL_MS,
    };
}

/** The list with `toast` added, newest last, capped at `TOAST_LIMIT`. */
export function raise(list: Toast[], toast: Toast): Toast[] {
    return [
        ...list,
        toast,
    ].slice(-TOAST_LIMIT);
}

/** The list without anything whose deadline has arrived. */
export function prune(list: Toast[], now: number): Toast[] {
    return list.filter(
        (toast) => toast.expiresAt === null || toast.expiresAt > now,
    );
}

export function dismiss(list: Toast[], id: string): Toast[] {
    return list.filter((toast) => toast.id !== id);
}

/** The little of a task this module needs to describe what happened to it. */
export type TaskLike = {
    status: string;
    title: string;
    error?: string;
};

/**
 * What to say about a task that just changed status, or `null` for silence.
 *
 * A row with no previous status is one being seen for the first time, which is
 * every row in the snapshot that arrives on connect — announcing those would
 * replay the whole history on every reload.
 *
 * `canceled` is deliberately silent: the only way to reach it is for someone to
 * ask, and they do not need telling what they just did.
 */
export function transitionToast(
    previous: string | undefined,
    task: TaskLike,
): { tone: ToastTone; message: string } | null {
    if (previous === undefined || previous === task.status) return null;

    switch (task.status) {
        case "complete":
            return { tone: "success", message: `Finished: ${task.title}` };
        case "seeding":
            return { tone: "info", message: `Seeding: ${task.title}` };
        case "failed":
            return {
                tone: "error",
                message: task.error
                    ? `Failed: ${task.title} — ${task.error}`
                    : `Failed: ${task.title}`,
            };
        default:
            return null;
    }
}

/**
 * Something thrown, as a line a person can read.
 *
 * `ApiError` already carries the service's own wording, which is almost always
 * better than anything this layer could invent; the fallback is for the rest.
 */
export function errorMessage(error: unknown): string {
    if (error instanceof Error && error.message) return error.message;
    return "Something went wrong";
}
