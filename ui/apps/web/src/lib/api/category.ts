import { deleteApi, getApi, patchApi, postApi, putApi } from "./client";

/** A category as Settings and the pickers show it. */
export type CategoryItem = {
    id: string;
    name: string;
    slug: string;
    /** Relative to the download folder; "" is the download folder itself. */
    folder: string;
    position: number;
    /** Downloads: its folder is fixed and it can't be deleted. */
    builtin: boolean;
    /** List items in it. */
    count: number;
};

/** A download's or a group's category, as its row carries it. */
export type CategoryRef = { id: string; name: string };

/** Downloads, the default category; fixed by the API's migration. */
export const DOWNLOADS_CATEGORY_ID = "00000000-0000-4000-8000-000000000001";

export function normalizeCategory(raw: any): CategoryItem {
    return {
        id: raw?.id ?? "",
        name: raw?.name ?? "",
        slug: raw?.slug ?? "",
        folder: raw?.folder ?? "",
        position: Number(raw?.position ?? 0),
        builtin: raw?.builtin === true,
        count: Number(raw?.count ?? 0),
    };
}

export function normalizeCategoryRef(raw: any): CategoryRef | undefined {
    return raw?.id ? { id: raw.id, name: raw.name ?? "" } : undefined;
}

export async function listCategories(): Promise<CategoryItem[]> {
    const raw = await getApi<any>("/category");
    return Array.isArray(raw?.categories)
        ? raw.categories.map(normalizeCategory)
        : [];
}

export async function createCategory(
    name: string,
    folder: string,
): Promise<CategoryItem> {
    return normalizeCategory(await postApi<any>("/category", { name, folder }));
}

export async function updateCategory(
    id: string,
    patch: { name?: string; folder?: string },
): Promise<CategoryItem> {
    return normalizeCategory(
        await patchApi<any>(`/category/${id}`, {
            ...(patch.name !== undefined ? { name: patch.name } : {}),
            ...(patch.folder !== undefined ? { folder: patch.folder } : {}),
        }),
    );
}

export async function orderCategories(ids: string[]): Promise<CategoryItem[]> {
    const raw = await postApi<any>("/category/order", { ids });
    return Array.isArray(raw?.categories)
        ? raw.categories.map(normalizeCategory)
        : [];
}

export async function deleteCategory(id: string): Promise<void> {
    await deleteApi(`/category/${id}`);
}

/** The moved row, raw, for `normalizeApiTask`. */
export async function moveToCategory(
    target: { id: string; collection: boolean },
    categoryId: string,
): Promise<any> {
    const owner = target.collection ? "collection" : "download";
    return putApi<any>(`/${owner}/${target.id}/category`, {
        category_id: categoryId,
    });
}

/** Whether a row belongs under the chosen category; no choice lets every row through. */
export function inCategory(
    task: { category?: CategoryRef },
    categoryId: string | null,
): boolean {
    return categoryId === null || task.category?.id === categoryId;
}

/** The API refuses the rest: a torrent stays where it was added, a video moves with its group, and a running download waits. */
export function canMoveCategory(task: {
    kind: string;
    status: string;
    parentId?: string;
}): boolean {
    if (task.kind === "torrent" || task.parentId) return false;
    return task.status !== "downloading" && task.status !== "muxing";
}
