import { useState } from "react";
import type { CategoryItem } from "@/lib/api/category";
import { LuTrash, LuChevronDown } from "@/component/core/icons";
import { canSave, draftOf, isDirty, swapped, type Draft } from "./state";
import "./field.css";

export interface CategoryManagerProps {
    categories: CategoryItem[];
    /** Resolves true when the category was added, so the form can clear. */
    onCreate: (name: string, folder: string) => Promise<boolean>;
    onUpdate: (
        id: string,
        patch: { name?: string; folder?: string },
    ) => Promise<boolean>;
    onOrder: (ids: string[]) => Promise<void>;
    /** Asks first; the shell owns the confirmation. */
    onDelete: (category: CategoryItem) => void;
}

/**
 * The Categories section of Settings: add, rename, repoint, reorder and
 * delete. Each row edits a local draft until Save, so a half-typed name never
 * reaches the API.
 */
export function CategoryManager({
    categories,
    onCreate,
    onUpdate,
    onOrder,
    onDelete,
}: CategoryManagerProps) {
    const [
        drafts,
        setDrafts,
    ] = useState<Record<string, Draft>>({});
    const [
        newName,
        setNewName,
    ] = useState("");
    const [
        newFolder,
        setNewFolder,
    ] = useState("");
    const [
        adding,
        setAdding,
    ] = useState(false);

    const edit = (category: CategoryItem, changes: Partial<Draft>) =>
        setDrafts((held) => ({
            ...held,
            [category.id]: {
                ...draftOf(category),
                ...held[category.id],
                ...changes,
            },
        }));

    const draftFor = (category: CategoryItem): Draft =>
        drafts[category.id] ?? draftOf(category);

    return (
        <section className="settings-section category-manager">
            <h3 className="settings-section-title">Categories</h3>
            <p className="settings-section-note">
                New downloads save in their category's folder, under the
                download folder. Changing a folder moves nothing already there.
            </p>

            <ul className="category-manager-list">
                {categories.map((category, index) => {
                    const draft = draftFor(category);
                    const dirty = isDirty(category, draft);
                    return (
                        <li key={category.id} className="category-manager-row">
                            <input
                                className="settings-input category-manager-name"
                                aria-label={`Name of ${category.name}`}
                                value={draft.name}
                                onChange={({ currentTarget: el }) =>
                                    edit(category, { name: el.value })
                                }
                            />
                            <input
                                className="settings-input category-manager-folder"
                                aria-label={`Folder of ${category.name}`}
                                placeholder={
                                    category.builtin
                                        ? "download folder"
                                        : "blank for the download folder"
                                }
                                value={draft.folder}
                                disabled={category.builtin}
                                onChange={({ currentTarget: el }) =>
                                    edit(category, { folder: el.value })
                                }
                            />
                            <span className="category-manager-count">
                                {category.count}
                            </span>
                            <button
                                type="button"
                                className="settings-key-save"
                                disabled={!dirty || !canSave(draft)}
                                onClick={async () => {
                                    const patch: {
                                        name?: string;
                                        folder?: string;
                                    } = {};
                                    if (draft.name !== category.name)
                                        patch.name = draft.name.trim();
                                    if (
                                        !category.builtin &&
                                        draft.folder !== category.folder
                                    )
                                        patch.folder = draft.folder;
                                    await onUpdate(category.id, patch);
                                }}
                            >
                                Save
                            </button>
                            <button
                                type="button"
                                className="category-manager-icon"
                                aria-label={`Move ${category.name} up`}
                                disabled={index === 0}
                                onClick={async () => {
                                    const next = swapped(
                                        categories.map((c) => c.id),
                                        index,
                                        -1,
                                    );
                                    if (next) await onOrder(next);
                                }}
                            >
                                <span className="category-manager-up">
                                    <LuChevronDown aria-hidden="true" />
                                </span>
                            </button>
                            <button
                                type="button"
                                className="category-manager-icon"
                                aria-label={`Move ${category.name} down`}
                                disabled={index === categories.length - 1}
                                onClick={async () => {
                                    const next = swapped(
                                        categories.map((c) => c.id),
                                        index,
                                        1,
                                    );
                                    if (next) await onOrder(next);
                                }}
                            >
                                <LuChevronDown aria-hidden="true" />
                            </button>
                            {!category.builtin && (
                                <button
                                    type="button"
                                    className="category-manager-icon category-manager-icon--danger"
                                    aria-label={`Delete ${category.name}`}
                                    onClick={() => onDelete(category)}
                                >
                                    <LuTrash aria-hidden="true" />
                                </button>
                            )}
                        </li>
                    );
                })}
            </ul>

            <form
                className="category-manager-add"
                onSubmit={async (event) => {
                    event.preventDefault();
                    const name = newName.trim();
                    if (!name || adding) return;
                    setAdding(true);
                    try {
                        if (await onCreate(name, newFolder.trim())) {
                            setNewName("");
                            setNewFolder("");
                        }
                    } finally {
                        setAdding(false);
                    }
                }}
            >
                <input
                    className="settings-input"
                    aria-label="New category name"
                    placeholder="Name"
                    value={newName}
                    onChange={({ currentTarget: el }) => setNewName(el.value)}
                />
                <input
                    className="settings-input"
                    aria-label="New category folder"
                    placeholder="blank for the download folder"
                    value={newFolder}
                    onChange={({ currentTarget: el }) => setNewFolder(el.value)}
                />
                <button
                    type="submit"
                    className="settings-key-save"
                    disabled={!canSave({ name: newName, folder: "" })}
                >
                    Add
                </button>
            </form>
        </section>
    );
}
