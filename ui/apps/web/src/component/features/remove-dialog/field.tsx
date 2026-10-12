import { useState } from "react";
import { removePrompt } from "./prompt";
import "./field.css";

export interface RemoveDialogProps {
    /** The task being removed, or `null` when the dialog is closed. */
    task: {
        id: string;
        title: string;
        status: string;
        /** A group's video count (v0.5). */
        videos?: number;
    } | null;
    onCancel: () => void;
    onConfirm: (id: string, deleteFiles: boolean) => void;
}

export function RemoveDialog(props: RemoveDialogProps) {
    // Mounted only while a task is being asked about, so the box never arrives
    // already ticked from the last time.
    return props.task ? (
        <OpenRemoveDialog {...props} task={props.task} />
    ) : null;
}

function OpenRemoveDialog({
    task,
    onCancel,
    onConfirm,
}: RemoveDialogProps & { task: NonNullable<RemoveDialogProps["task"]> }) {
    const [
        deleteFiles,
        setDeleteFiles,
    ] = useState(false);

    const prompt = removePrompt(task.status, task.videos);

    return (
        <div
            className="remove-dialog-overlay"
            role="dialog"
            aria-modal="true"
            aria-label={prompt.heading}
        >
            <div className="remove-dialog">
                <h2 className="remove-dialog-heading">{prompt.heading}</h2>
                <p className="remove-dialog-title">{task.title}</p>
                <p className="remove-dialog-body">{prompt.body}</p>

                {prompt.canKeepFiles && (
                    <label className="remove-dialog-option">
                        <input
                            type="checkbox"
                            checked={deleteFiles}
                            onChange={({ currentTarget: el }) => {
                                setDeleteFiles(el.checked);
                            }}
                        />
                        <span>Also delete the downloaded files</span>
                    </label>
                )}

                <div className="remove-dialog-actions">
                    <button
                        type="button"
                        className="remove-dialog-btn"
                        onClick={onCancel}
                    >
                        Cancel
                    </button>
                    <button
                        type="button"
                        className="remove-dialog-btn remove-dialog-btn--danger"
                        onClick={() =>
                            onConfirm(
                                task.id,
                                // Nothing to keep means nothing to ask
                                // about: those removals always take the
                                // partial file with them.
                                prompt.canKeepFiles ? deleteFiles : true,
                            )
                        }
                    >
                        {prompt.confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
