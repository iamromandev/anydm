/**
 * Finding a task in the list, and not doing it on every keystroke.
 *
 * The matching runs over the rows already loaded. That is a real limit once
 * the list pages, and the empty state says so rather than implying the term
 * was not found anywhere.
 */

import type { UiTask } from "./api";

/**
 * Whether a task answers to what was typed.
 *
 * Title, source and info hash: the three things someone actually has to hand
 * when looking for a download. An empty term matches everything, so the
 * caller needs no special case for "not searching".
 */
export function matchesSearch(task: UiTask, term: string): boolean {
    const query = term.trim().toLowerCase();
    if (!query) return true;

    return (
        task.title.toLowerCase().includes(query) ||
        task.url.toLowerCase().includes(query) ||
        (task.infoHash?.toLowerCase().includes(query) ?? false)
    );
}
