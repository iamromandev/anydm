import { useEffect, useState } from "react";
import type { CategoryItem } from "@/lib/api/category";
import type { UiTask } from "@/lib/api";
import "./field.css";

export interface CategoryMoveProps {
    /** The row being moved; null hides the dialog. */
    task: UiTask | null;
    categories: CategoryItem[];
    onClose: () => void;
    /** Move the row to the chosen category; resolves once it has answered. */
    onMove: (categoryId: string) => Promise<void>;
}

/**
 * Picks the category a row moves to. The row is already in its own category,
 * so Move stays off until a different one is chosen.
 */
export function CategoryMove({
    task,
    categories,
    onClose,
    onMove,
}: CategoryMoveProps) {
    const [
        chosen,
        setChosen,
    ] = useState("");
    const [
        busy,
        setBusy,
    ] = useState(false);

    // Start on the row's own category each time a different row opens.
    const openId = task?.id;
    const ownCategory = task?.category?.id ?? "";
    useEffect(() => {
        setChosen(ownCategory);
    }, [
        openId,
    ]);

    if (!task) return null;

    return (
        <div
            className="category-move-overlay"
            onClick={() => onClose()}
            onKeyDown={(event) => {
                if (event.key === "Escape") onClose();
            }}
        >
            <div
                className="category-move-panel"
                role="dialog"
                aria-modal="true"
                aria-labelledby="category-move-title"
                onClick={(event) => event.stopPropagation()}
            >
                <h2 id="category-move-title" className="category-move-title">
                    Move to category
                </h2>
                <p className="category-move-subject">{task.title}</p>
                <ul className="category-move-list">
                    {categories.map((category) => (
                        <li key={category.id}>
                            <label className="category-move-choice">
                                <input
                                    type="radio"
                                    name="category-move"
                                    value={category.id}
                                    checked={chosen === category.id}
                                    onChange={() => setChosen(category.id)}
                                />
                                <span>{category.name}</span>
                                <span className="category-move-folder">
                                    {category.folder || "download folder"}
                                </span>
                            </label>
                        </li>
                    ))}
                </ul>
                <footer className="category-move-actions">
                    <button
                        type="button"
                        className="category-move-btn"
                        onClick={() => onClose()}
                    >
                        Cancel
                    </button>
                    <button
                        type="button"
                        className="category-move-btn category-move-btn--primary"
                        disabled={
                            busy ||
                            !chosen ||
                            chosen === (task.category?.id ?? "")
                        }
                        onClick={async () => {
                            setBusy(true);
                            try {
                                await onMove(chosen);
                            } finally {
                                setBusy(false);
                            }
                        }}
                    >
                        {busy ? "Moving…" : "Move"}
                    </button>
                </footer>
            </div>
        </div>
    );
}
