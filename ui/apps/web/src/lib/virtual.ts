/**
 * Which rows of a long list to draw: those in view and a few either side,
 * with padding standing in for the rest so the scrollbar stays true. Rows
 * share one fixed height, which is what makes this arithmetic rather than
 * measurement. A 5,000-video playlist draws a few dozen rows.
 */

export interface VirtualWindow {
    /** The first row drawn. */
    start: number;
    /** One past the last row drawn. */
    end: number;
    /** Height standing in for the rows above. */
    padTop: number;
    /** Height standing in for the rows below. */
    padBottom: number;
}

export function virtualWindow(
    count: number,
    rowHeight: number,
    scrollTop: number,
    viewportHeight: number,
    overscan = 8,
): VirtualWindow {
    if (count <= 0 || rowHeight <= 0) {
        return { start: 0, end: 0, padTop: 0, padBottom: 0 };
    }
    const top = Math.max(0, scrollTop);
    const first = Math.floor(top / rowHeight);
    const last = Math.ceil((top + Math.max(0, viewportHeight)) / rowHeight);
    const end = Math.min(count, last + overscan);
    const start = Math.min(end, Math.max(0, first - overscan));
    return {
        start,
        end,
        padTop: start * rowHeight,
        padBottom: (count - end) * rowHeight,
    };
}
