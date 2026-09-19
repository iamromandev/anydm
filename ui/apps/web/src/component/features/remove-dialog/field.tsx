import { component$, useSignal } from "@qwik.dev/core";
import { removePrompt } from "./prompt";
import "./field.css";

export interface RemoveDialogProps {
    /** The task being removed, or `null` when the dialog is closed. */
    task: { id: string; title: string; status: string } | null;
    onCancel: () => void;
    onConfirm: (id: string, deleteFiles: boolean) => void;
}

export const RemoveDialog = component$<RemoveDialogProps>(
    ({ task, onCancel, onConfirm }) => {
        // Re-created with the dialog, so the box never arrives already ticked
        // from the last time.
        const deleteFiles = useSignal(false);

        if (!task) return null;

        const prompt = removePrompt(task.status);

        return (
            <div
                class="remove-dialog-overlay"
                role="dialog"
                aria-modal="true"
                aria-label={prompt.heading}
            >
                <div class="remove-dialog">
                    <h2 class="remove-dialog-heading">{prompt.heading}</h2>
                    <p class="remove-dialog-title">{task.title}</p>
                    <p class="remove-dialog-body">{prompt.body}</p>

                    {prompt.canKeepFiles && (
                        <label class="remove-dialog-option">
                            <input
                                type="checkbox"
                                checked={deleteFiles.value}
                                onChange$={(_, el) => {
                                    deleteFiles.value = el.checked;
                                }}
                            />
                            <span>Also delete the downloaded files</span>
                        </label>
                    )}

                    <div class="remove-dialog-actions">
                        <button
                            type="button"
                            class="remove-dialog-btn"
                            onClick$={onCancel}
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            class="remove-dialog-btn remove-dialog-btn--danger"
                            onClick$={() =>
                                onConfirm(
                                    task.id,
                                    // Nothing to keep means nothing to ask
                                    // about: those removals always take the
                                    // partial file with them.
                                    prompt.canKeepFiles
                                        ? deleteFiles.value
                                        : true,
                                )
                            }
                        >
                            {prompt.confirmLabel}
                        </button>
                    </div>
                </div>
            </div>
        );
    },
);
