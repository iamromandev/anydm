import { component$, type QRL } from "@qwik.dev/core";
import type { CategoryItem } from "@/lib/api/category";
import "./field.css";

export interface CategorySelectProps {
    categories: CategoryItem[];
    /** The chosen category's id. */
    value: string;
    onChange$: QRL<(id: string) => void>;
    class?: string;
}

/**
 * A category picker. The chosen option is marked `selected` rather than the
 * select's own `value` is set: the select's value is applied before its
 * options exist, and falls back to the first one.
 */
export const CategorySelect = component$<CategorySelectProps>(
    ({ categories, value, onChange$, class: extra }) => (
        <select
            class={`category-select ${extra ?? ""}`}
            aria-label="Category"
            onChange$={(_, el) => onChange$(el.value)}
        >
            {categories.map((category) => (
                <option
                    key={category.id}
                    value={category.id}
                    selected={category.id === value}
                >
                    {category.name}
                </option>
            ))}
        </select>
    ),
);
