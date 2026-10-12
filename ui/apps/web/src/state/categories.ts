import {
    createCategory,
    deleteCategory,
    listCategories,
    orderCategories,
    updateCategory,
} from "@/lib/api";
import { loadCategoryChoice, saveCategoryChoice } from "@/lib/category-choice";
import { errorMessage } from "@/lib/toast";
import type { App } from "./app";
import type { Store } from "./store";

/**
 * The Settings section's edits. Each one reloads the list, so the sidebar, the
 * pickers and the counts show the new state. A refusal is said in a toast, and
 * the form keeps what was typed.
 */
export function createCategories(store: Store, app: App) {
    const fail = (err: unknown) =>
        app.toasts.notify("error", errorMessage(err));

    /**
     * The categories, and the add forms' choice among them: the one remembered
     * while it still exists, else Downloads. A category the filter still names
     * but the list no longer has is dropped from it.
     */
    async function load(): Promise<void> {
        const listed = await listCategories().catch(() => null);
        if (listed === null) return;
        const { category } = store.get();
        store.patch({
            categories: listed,
            addCategory: loadCategoryChoice(listed),
            ...(category !== null &&
            !listed.some((item) => item.id === category)
                ? { category: null }
                : {}),
        });
    }

    // Handlers are passed around unbound, so none of them uses `this`.
    return {
        load,

        async create(name: string, folder: string): Promise<boolean> {
            try {
                await createCategory(name, folder);
            } catch (err) {
                fail(err);
                return false;
            }
            await load();
            return true;
        },

        async update(
            id: string,
            patch: { name?: string; folder?: string },
        ): Promise<boolean> {
            try {
                await updateCategory(id, patch);
            } catch (err) {
                fail(err);
                return false;
            }
            await load();
            // A rename shows on every row in it.
            await app.tasks.sync();
            return true;
        },

        async order(ids: string[]): Promise<void> {
            try {
                store.patch({ categories: await orderCategories(ids) });
            } catch (err) {
                fail(err);
            }
        },

        async remove(id: string): Promise<void> {
            try {
                await deleteCategory(id);
            } catch (err) {
                fail(err);
                return;
            }
            await load();
        },

        /** The add forms' choice, remembered for the next visit. */
        changeAdd(id: string): void {
            store.patch({ addCategory: id });
            saveCategoryChoice(id);
        },
    };
}
