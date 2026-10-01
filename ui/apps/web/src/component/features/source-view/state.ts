/**
 * What the Sources view shows, decided without the DOM so it is tested with plain values.
 */

import type { SourceItem, SourceTest } from "@/lib/api/source";

export type SourceViewState = {
    items: SourceItem[];
    loading: boolean;
    /** What each row is doing, by id; a row with no entry is idle. */
    busy: Record<string, string>;
    /** The last test answer per source, by id; kept across toggles and edits. */
    lastTest: Record<string, SourceTest>;
    failure: string | null;
    /** The source awaiting delete confirmation, or null. */
    confirmingDelete: SourceItem | null;
};

export function emptySourceView(): SourceViewState {
    return {
        items: [],
        loading: false,
        busy: {},
        lastTest: {},
        failure: null,
        confirmingDelete: null,
    };
}

/** The row's last answer in words: the success line, the failure, or that it was never tested. */
export function testLine(result: SourceTest | null): string {
    if (result === null) return "not tested";
    if (!result.ok) return result.message;
    const count = result.count ?? 0;
    return `Answered · ${count} result${count === 1 ? "" : "s"} · ${(result.tookMs / 1000).toFixed(1)} s`;
}

/** A state with the row's answer recorded; every other row's answer survives untouched. */
export function rememberTest(
    state: SourceViewState,
    id: string,
    result: SourceTest,
): SourceViewState {
    return {
        ...state,
        lastTest: { ...state.lastTest, [id]: result },
    };
}
