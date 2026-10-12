/** A class list from parts, dropping the ones that are off. */
export function cx(...parts: (string | false | null | undefined)[]): string {
    return parts.filter(Boolean).join(" ");
}
