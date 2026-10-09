/**
 * Adding many links at once (#266): a pasted list, or one pattern such as
 * `img[001-120].png`. The API expands the pattern and trims the list, so this
 * holds no copy of that grammar; it asks, and words what comes back.
 */

import { postApi } from "./client";

type Post = <T>(path: string, body: unknown) => Promise<T>;

/** What a batch is made of. */
export type BatchSource =
    { kind: "list"; text: string } | { kind: "pattern"; text: string };

export type BatchPreview = { count: number; urls: string[] };

export type BatchOutcome = "added" | "duplicate" | "error";

export type BatchItem = {
    url: string;
    result: BatchOutcome;
    /** The new download, or for a duplicate the one that already has it. */
    downloadId: string | null;
    message: string;
};

/** The request body for a source: a list's lines, or the pattern as typed. */
export function batchBody(source: BatchSource): {
    lines?: string[];
    pattern?: string;
} {
    return source.kind === "list"
        ? { lines: source.text.split(/\r?\n/) }
        : { pattern: source.text };
}

/** Whether a source has anything in it worth asking the API about. */
export function hasLinks(source: BatchSource): boolean {
    return source.text.trim() !== "";
}

export async function previewBatch(
    source: BatchSource,
    post: Post = postApi,
): Promise<BatchPreview> {
    const raw = await post<any>("/download/batch/preview", batchBody(source));
    return {
        count: Number(raw?.count ?? 0),
        urls: Array.isArray(raw?.urls) ? raw.urls.map(String) : [],
    };
}

export function normalizeBatchItem(raw: any): BatchItem {
    const result: BatchOutcome =
        raw?.result === "added" || raw?.result === "duplicate"
            ? raw.result
            : "error";
    return {
        url: String(raw?.url ?? ""),
        result,
        downloadId: raw?.download_id ? String(raw.download_id) : null,
        message: raw?.message ? String(raw.message) : "",
    };
}

export async function addBatch(
    source: BatchSource,
    preset: string,
    post: Post = postApi,
    categoryId?: string,
): Promise<BatchItem[]> {
    const raw = await post<any[]>("/download/batch", {
        ...batchBody(source),
        preset,
        ...(categoryId ? { category_id: categoryId } : {}),
    });
    return (Array.isArray(raw) ? raw : []).map(normalizeBatchItem);
}

/** How many links a preview shows before "and N more". */
export const PREVIEW_SHOWN = 5;

/** The first few links, and how many are left unshown. */
export function previewHead(
    preview: BatchPreview,
    shown = PREVIEW_SHOWN,
): { head: string[]; more: number } {
    return {
        head: preview.urls.slice(0, shown),
        more: Math.max(preview.count - shown, 0),
    };
}

function plural(n: number, one: string, many: string): string {
    return `${n.toLocaleString("en-US")} ${n === 1 ? one : many}`;
}

/** "120 links" for the preview's count. */
export function linkCount(n: number): string {
    return plural(n, "link", "links");
}

/** "118 added · 2 already in your list · 1 failed": every part, even a zero. */
export function batchSummary(items: BatchItem[]): string {
    const count = (outcome: BatchOutcome) =>
        items.filter((item) => item.result === outcome).length;
    return [
        `${count("added").toLocaleString("en-US")} added`,
        `${count("duplicate").toLocaleString("en-US")} already in your list`,
        `${count("error").toLocaleString("en-US")} failed`,
    ].join(" · ");
}

/**
 * Whether pasted text is several links rather than one: two or more lines
 * with something on them. A single link with a trailing newline is one.
 */
export function looksLikeMany(text: string): boolean {
    return text.split(/\r?\n/).filter((line) => line.trim() !== "").length > 1;
}
