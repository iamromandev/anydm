import { component$, $, useSignal, useTask$, type QRL } from "@qwik.dev/core";
import type { CategoryItem } from "@/lib/api/category";
import type { UiTask } from "@/lib/api";
import "./field.css";

export interface CategoryMoveProps {
    /** The row being moved; null hides the dialog. */
    task: UiTask | null;
    categories: CategoryItem[];
    onClose: QRL<() => void>;
    /** Move the row to the chosen category; resolves once it has answered. */
    onMove: QRL<(categoryId: string) => Promise<void>>;
}

/**
 * Picks the category a row moves to. The row is already in its own category,
 * so Move stays off until a different one is chosen.
 */
export const CategoryMove = component$<CategoryMoveProps>(
    ({ task, categories, onClose, onMove }) => {
        const chosen = useSignal("");
        const busy = useSignal(false);

        // Start on the row's own category each time a different row opens.
        useTask$(({ track }) => {
            track(() => task?.id);
            chosen.value = task?.category?.id ?? "";
        });

        if (!task) return null;

        return (
            <div
                class="category-move-overlay"
                onClick$={$(() => onClose())}
                onKeyDown$={$((event: KeyboardEvent) => {
                    if (event.key === "Escape") onClose();
                })}
            >
                <div
                    class="category-move-panel"
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="category-move-title"
                    onClick$={$((event: MouseEvent) => event.stopPropagation())}
                >
                    <h2 id="category-move-title" class="category-move-title">
                        Move to category
                    </h2>
                    <p class="category-move-subject">{task.title}</p>
                    <ul class="category-move-list">
                        {categories.map((category) => (
                            <li key={category.id}>
                                <label class="category-move-choice">
                                    <input
                                        type="radio"
                                        name="category-move"
                                        value={category.id}
                                        checked={chosen.value === category.id}
                                        onChange$={$(() => {
                                            chosen.value = category.id;
                                        })}
                                    />
                                    <span>{category.name}</span>
                                    <span class="category-move-folder">
                                        {category.folder || "download folder"}
                                    </span>
                                </label>
                            </li>
                        ))}
                    </ul>
                    <footer class="category-move-actions">
                        <button
                            type="button"
                            class="category-move-btn"
                            onClick$={$(() => onClose())}
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            class="category-move-btn category-move-btn--primary"
                            disabled={
                                busy.value ||
                                !chosen.value ||
                                chosen.value === (task.category?.id ?? "")
                            }
                            onClick$={$(async () => {
                                busy.value = true;
                                try {
                                    await onMove(chosen.value);
                                } finally {
                                    busy.value = false;
                                }
                            })}
                        >
                            {busy.value ? "Moving…" : "Move"}
                        </button>
                    </footer>
                </div>
            </div>
        );
    },
);
