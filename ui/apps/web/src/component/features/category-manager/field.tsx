import { component$, $, useSignal, useStore, type QRL } from "@qwik.dev/core";
import type { CategoryItem } from "@/lib/api/category";
import { LuTrash, LuChevronDown } from "@/component/core/icons";
import { canSave, draftOf, isDirty, swapped, type Draft } from "./state";
import "./field.css";

export interface CategoryManagerProps {
    categories: CategoryItem[];
    /** Resolves true when the category was added, so the form can clear. */
    onCreate$: QRL<(name: string, folder: string) => Promise<boolean>>;
    onUpdate$: QRL<
        (
            id: string,
            patch: { name?: string; folder?: string },
        ) => Promise<boolean>
    >;
    onOrder$: QRL<(ids: string[]) => Promise<void>>;
    /** Asks first; the shell owns the confirmation. */
    onDelete$: QRL<(category: CategoryItem) => void>;
}

/**
 * The Categories section of Settings: add, rename, repoint, reorder and
 * delete. Each row edits a local draft until Save, so a half-typed name never
 * reaches the API.
 */
export const CategoryManager = component$<CategoryManagerProps>(
    ({ categories, onCreate$, onUpdate$, onOrder$, onDelete$ }) => {
        const drafts = useStore<Record<string, Draft>>({});
        const newName = useSignal("");
        const newFolder = useSignal("");
        const adding = useSignal(false);

        const draftFor = (category: CategoryItem): Draft =>
            drafts[category.id] ?? draftOf(category);

        const setDraft = (category: CategoryItem, change: Partial<Draft>) => {
            drafts[category.id] = { ...draftFor(category), ...change };
        };

        return (
            <section class="settings-section category-manager">
                <h3 class="settings-section-title">Categories</h3>
                <p class="settings-section-note">
                    New downloads save in their category's folder, under the
                    download folder. Changing a folder moves nothing already
                    there.
                </p>

                <ul class="category-manager-list">
                    {categories.map((category, index) => {
                        const draft = draftFor(category);
                        const dirty = isDirty(category, draft);
                        return (
                            <li key={category.id} class="category-manager-row">
                                <input
                                    class="settings-input category-manager-name"
                                    aria-label={`Name of ${category.name}`}
                                    value={draft.name}
                                    onInput$={$((_, el) =>
                                        setDraft(category, { name: el.value }),
                                    )}
                                />
                                <input
                                    class="settings-input category-manager-folder"
                                    aria-label={`Folder of ${category.name}`}
                                    placeholder={
                                        category.builtin
                                            ? "download folder"
                                            : "blank for the download folder"
                                    }
                                    value={draft.folder}
                                    disabled={category.builtin}
                                    onInput$={$((_, el) =>
                                        setDraft(category, {
                                            folder: el.value,
                                        }),
                                    )}
                                />
                                <span class="category-manager-count">
                                    {category.count}
                                </span>
                                <button
                                    type="button"
                                    class="settings-key-save"
                                    disabled={!dirty || !canSave(draft)}
                                    onClick$={$(async () => {
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
                                        await onUpdate$(category.id, patch);
                                    })}
                                >
                                    Save
                                </button>
                                <button
                                    type="button"
                                    class="category-manager-icon"
                                    aria-label={`Move ${category.name} up`}
                                    disabled={index === 0}
                                    onClick$={$(async () => {
                                        const next = swapped(
                                            categories.map((c) => c.id),
                                            index,
                                            -1,
                                        );
                                        if (next) await onOrder$(next);
                                    })}
                                >
                                    <span class="category-manager-up">
                                        <LuChevronDown aria-hidden="true" />
                                    </span>
                                </button>
                                <button
                                    type="button"
                                    class="category-manager-icon"
                                    aria-label={`Move ${category.name} down`}
                                    disabled={index === categories.length - 1}
                                    onClick$={$(async () => {
                                        const next = swapped(
                                            categories.map((c) => c.id),
                                            index,
                                            1,
                                        );
                                        if (next) await onOrder$(next);
                                    })}
                                >
                                    <LuChevronDown aria-hidden="true" />
                                </button>
                                {!category.builtin && (
                                    <button
                                        type="button"
                                        class="category-manager-icon category-manager-icon--danger"
                                        aria-label={`Delete ${category.name}`}
                                        onClick$={$(() => onDelete$(category))}
                                    >
                                        <LuTrash aria-hidden="true" />
                                    </button>
                                )}
                            </li>
                        );
                    })}
                </ul>

                <form
                    class="category-manager-add"
                    preventdefault:submit
                    onSubmit$={$(async () => {
                        const name = newName.value.trim();
                        if (!name || adding.value) return;
                        adding.value = true;
                        try {
                            if (await onCreate$(name, newFolder.value.trim())) {
                                newName.value = "";
                                newFolder.value = "";
                            }
                        } finally {
                            adding.value = false;
                        }
                    })}
                >
                    <input
                        class="settings-input"
                        aria-label="New category name"
                        placeholder="Name"
                        value={newName.value}
                        onInput$={$((_, el) => {
                            newName.value = el.value;
                        })}
                    />
                    <input
                        class="settings-input"
                        aria-label="New category folder"
                        placeholder="blank for the download folder"
                        value={newFolder.value}
                        onInput$={$((_, el) => {
                            newFolder.value = el.value;
                        })}
                    />
                    <button
                        type="submit"
                        class="settings-key-save"
                        disabled={!canSave({ name: newName.value, folder: "" })}
                    >
                        Add
                    </button>
                </form>
            </section>
        );
    },
);
