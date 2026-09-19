import { component$ } from "@qwik.dev/core";
import {
    LuAlertCircle,
    LuAlertTriangle,
    LuCheckCircle,
    LuX,
} from "@/component/core/icons";
import type { Toast, ToastTone } from "@/lib/toast";
import "./field.css";

export interface ToasterProps {
    toasts: Toast[];
    onDismiss: (id: string) => void;
}

const ICONS: Record<ToastTone, any> = {
    success: LuCheckCircle,
    error: LuAlertTriangle,
    info: LuAlertCircle,
};

/**
 * The corner stack.
 *
 * The region is always in the DOM, empty or not: a live region inserted at the
 * same moment as its first message is announced unreliably, and this one exists
 * precisely to be heard.
 */
export const Toaster = component$<ToasterProps>(({ toasts, onDismiss }) => {
    return (
        <div class="toaster" role="status" aria-live="polite">
            {toasts.map((toast) => {
                const Icon = ICONS[toast.tone];
                return (
                    <div
                        key={toast.id}
                        class={`toast toast--${toast.tone}`}
                        data-testid="toast"
                    >
                        <span class="toast-icon" aria-hidden="true">
                            <Icon width="16" height="16" />
                        </span>
                        <p class="toast-message">{toast.message}</p>
                        <button
                            type="button"
                            class="toast-dismiss"
                            aria-label="Dismiss notification"
                            onClick$={() => onDismiss(toast.id)}
                        >
                            <LuX width="14" height="14" aria-hidden="true" />
                        </button>
                    </div>
                );
            })}
        </div>
    );
});
