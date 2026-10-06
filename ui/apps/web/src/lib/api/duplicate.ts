/**
 * An add the list already holds, refused with a 409 that names the download.
 *
 * The API puts it in the error's one detail: `subject` is the download's id,
 * `description` its title and `fields` its status. The add box and the modal
 * offer Open (show that row) and, where the API allows a second copy, Add
 * anyway.
 */

import { ApiError } from "./envelope";
import type { AddType } from "./site";
import type { UiTask } from "./download";

export type Duplicate = { id: string; title: string; status: string };

/** What an add is asked to do; `allowDuplicate` is Add anyway. */
export type AddInput = {
    type: AddType;
    value: string;
    preset?: string;
    files?: number[];
    allowDuplicate?: boolean;
};

/**
 * The download a refused add names, or null for any other failure.
 *
 * Other 409s (pausing a paused download) carry no such detail, so they are
 * not mistaken for one.
 */
export function duplicateOf(err: unknown): Duplicate | null {
    if (!(err instanceof ApiError) || Number(err.code) !== 409) return null;
    const detail = err.details?.[0];
    if (!detail?.subject) return null;
    return {
        id: detail.subject,
        title: detail.description ?? "",
        status: detail.fields?.[0] ?? "",
    };
}

/**
 * Whether the API will take a second copy of this add. The engine holds a
 * torrent once, so a magnet or a .torrent is refused with a 400.
 */
export function canAddAnyway(type: AddType): boolean {
    return type !== "magnet" && type !== "file";
}

/** The same add, sent again as a second copy. */
export function addAnyway(input: AddInput): AddInput {
    return { ...input, allowDuplicate: true };
}

/** The request a page or direct add makes, with Add anyway when asked. */
export function directRequest(input: AddInput): {
    path: string;
    body: Record<string, unknown>;
} {
    const allow = input.allowDuplicate ? { allow_duplicate: true } : {};
    if (input.type === "site") {
        return {
            path: "/download/media",
            body: {
                url: input.value,
                preset: input.preset || "best",
                ...allow,
            },
        };
    }
    return { path: "/download/url", body: { url: input.value, ...allow } };
}

/** The list with `row` on top, replacing the copy it already holds. */
export function withRowOnTop(tasks: UiTask[], row: UiTask): UiTask[] {
    return [
        row,
        ...tasks.filter((task) => task.id !== row.id),
    ];
}
