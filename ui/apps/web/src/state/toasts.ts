import {
    createToast,
    dismiss,
    prune,
    raise,
    type ToastTone,
} from "@/lib/toast";
import type { Store } from "./store";

export function createToasts(store: Store) {
    return {
        notify(tone: ToastTone, message: string): void {
            store.patch((s) => ({
                toasts: raise(s.toasts, createToast(tone, message, Date.now())),
            }));
        },

        dismiss(id: string): void {
            store.patch((s) => ({ toasts: dismiss(s.toasts, id) }));
        },

        /**
         * One clock for every toast, rather than a timer per toast: an expiry
         * is a deadline, and a sweep is how a deadline is noticed. It writes
         * only when something actually expired, so an idle page does not
         * re-render twice a second.
         */
        sweep(now: number): void {
            const current = store.get().toasts;
            const kept = prune(current, now);
            if (kept.length !== current.length) store.patch({ toasts: kept });
        },
    };
}
