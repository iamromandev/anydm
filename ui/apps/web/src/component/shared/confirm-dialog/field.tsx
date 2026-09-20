import { component$ } from "@qwik.dev/core";
import "./field.css";

export interface ConfirmDialogProps {
    /** The question, or `null` when nothing is being asked. */
    prompt: { heading: string; body: string; confirmLabel: string } | null;
    onCancel: () => void;
    onConfirm: () => void;
}

/**
 * A plain yes-or-no question.
 *
 * Deliberately separate from the remove dialog, which asks about one task and
 * carries a checkbox about its files. If a third of these appears, the chrome
 * the two share is worth extracting; two is not yet a pattern.
 */
export const ConfirmDialog = component$<ConfirmDialogProps>(
    ({ prompt, onCancel, onConfirm }) => {
        if (!prompt) return null;

        return (
            <div
                class="confirm-dialog-overlay"
                role="dialog"
                aria-modal="true"
                aria-label={prompt.heading}
            >
                <div class="confirm-dialog">
                    <h2 class="confirm-dialog-heading">{prompt.heading}</h2>
                    <p class="confirm-dialog-body">{prompt.body}</p>

                    <div class="confirm-dialog-actions">
                        <button
                            type="button"
                            class="confirm-dialog-btn"
                            onClick$={onCancel}
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            class="confirm-dialog-btn confirm-dialog-btn--danger"
                            onClick$={onConfirm}
                        >
                            {prompt.confirmLabel}
                        </button>
                    </div>
                </div>
            </div>
        );
    },
);
