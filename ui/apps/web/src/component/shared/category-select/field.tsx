import type { CategoryItem } from "@/lib/api/category";
import "./field.css";

export interface CategorySelectProps {
    categories: CategoryItem[];
    /** The chosen category's id. */
    value: string;
    onChange: (id: string) => void;
    className?: string;
}

/** A category picker. */
export function CategorySelect({
    categories,
    value,
    onChange,
    className,
}: CategorySelectProps) {
    return (
        <select
            className={`category-select ${className ?? ""}`}
            aria-label="Category"
            value={value}
            onChange={(event) => onChange(event.target.value)}
        >
            {categories.map((category) => (
                <option key={category.id} value={category.id}>
                    {category.name}
                </option>
            ))}
        </select>
    );
}
