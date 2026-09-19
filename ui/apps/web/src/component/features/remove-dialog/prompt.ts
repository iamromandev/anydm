/**
 * What the remove dialog says, and whether it offers to keep the files.
 *
 * The offer mirrors the API exactly: `DELETE /download/{id}?delete_files=false`
 * answers 409 for anything that is not finished, because a `.part` outlives
 * its row as so many bytes nothing can describe. Showing a checkbox that the
 * server would refuse would be worse than not showing one.
 */

export type RemovePrompt = {
    heading: string;
    body: string;
    /** Whether the "keep the files" choice is on offer at all. */
    canKeepFiles: boolean;
    confirmLabel: string;
};

/** The two statuses whose files are complete enough to be worth keeping. */
const FINISHED = new Set([
    "complete",
    "seeding",
]);

export function removePrompt(status: string): RemovePrompt {
    if (status === "seeding") {
        return {
            heading: "Remove this torrent?",
            body: "Sharing stops. The files stay on disk unless you ask for them to go too.",
            canKeepFiles: true,
            confirmLabel: "Remove",
        };
    }

    if (FINISHED.has(status)) {
        return {
            heading: "Remove from the list?",
            body: "The download stays on disk unless you ask for it to go too.",
            canKeepFiles: true,
            confirmLabel: "Remove",
        };
    }

    // Everything else, including a status this build has never heard of: no
    // complete file exists, so there is nothing to offer keeping.
    return {
        heading: "Stop and remove?",
        body: "Anything downloaded so far is discarded.",
        canKeepFiles: false,
        confirmLabel: "Stop and remove",
    };
}
