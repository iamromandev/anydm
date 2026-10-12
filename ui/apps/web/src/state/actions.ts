import {
    apiUrl,
    deleteApi,
    dropVideo,
    normalizeApiTask,
    postApi,
} from "@/lib/api";
import { removePrompt } from "@/component/features/remove-dialog/prompt";
import { errorMessage } from "@/lib/toast";
import type { App } from "./app";
import type { BulkAction, Store } from "./store";

export type { BulkAction };

/** What each sweep did, for the line it leaves behind. */
const BULK_VERBS: Record<BulkAction, string> = {
    pause_all: "Paused",
    resume_all: "Resumed",
    clear_finished: "Cleared",
};

/** What a person does to a download: pause, remove, sweep, save. */
export function createActions(store: Store, app: App) {
    async function taskAction(
        taskId: string,
        action: "pause" | "resume",
    ): Promise<void> {
        const task = store.get().tasks.find((t) => t.id === taskId);
        if (!task) return;

        try {
            const updated = await postApi<any>(
                task.kind === "playlist"
                    ? `/collection/${taskId}/${action}`
                    : `/download/${taskId}/${action}`,
                {},
            );
            if (updated) {
                const row = normalizeApiTask(updated);
                store.patch((s) => ({
                    tasks: s.tasks.map((t) => (t.id === taskId ? row : t)),
                }));
            }
            // A group moves its videos in one UPDATE and publishes only its
            // own frame, so an open Entries list is read again.
            if (task.kind === "playlist") await app.entries.reload(taskId);
        } catch (err) {
            app.toasts.notify("error", errorMessage(err));
        }
    }

    async function removeConfirm(
        taskId: string,
        deleteFiles: boolean,
    ): Promise<void> {
        store.patch({ removing: null });
        // A collection is removed by its own route; a collection's video,
        // which is never a row of the list, by the download's.
        const removing = store.get().tasks.find((t) => t.id === taskId);

        try {
            await deleteApi(
                removing?.kind === "playlist"
                    ? `/collection/${taskId}?delete_files=${deleteFiles}`
                    : `/download/${taskId}?delete_files=${deleteFiles}`,
            );
        } catch (err) {
            // The row still goes: the person asked for it gone, and a failure
            // here is nearly always a row the API has already forgotten.
            app.toasts.notify("error", errorMessage(err));
        }

        store.patch((s) => {
            // A video leaves its Entries list; a group's own list shuts.
            const { [taskId]: _shut, ...open } = s.entries;
            return {
                tasks: s.tasks.filter((t) => t.id !== taskId),
                entries: dropVideo(open, taskId),
            };
        });
        // The row leaves before its frame lands, so the frame finds nothing to
        // compare with: the counts are asked for here, as the desktop does.
        void app.tasks.loadSummary();
    }

    async function runBulk(action: BulkAction): Promise<void> {
        try {
            const result = await postApi<any>("/download/bulk", { action });
            const affected = result?.affected ?? 0;
            app.toasts.notify(
                "info",
                affected === 0
                    ? "Nothing to do"
                    : `${BULK_VERBS[action]} ${affected} download${affected === 1 ? "" : "s"}`,
            );
        } catch (err) {
            app.toasts.notify("error", errorMessage(err));
        }
        await app.tasks.sync();
    }

    return {
        pause: (id: string) => taskAction(id, "pause"),
        resume: (id: string) => taskAction(id, "resume"),
        removeConfirm,

        async remove(taskId: string): Promise<void> {
            const state = store.get();
            const task = state.tasks.find((t) => t.id === taskId);
            if (!task) return;

            // A group asks about its videos, and can always keep what finished.
            const videos =
                task.kind === "playlist"
                    ? (task.entryCounts?.total ?? 0)
                    : undefined;

            if (!state.prefs.confirmBeforeRemove) {
                // Straight through, on the same terms the dialog would have
                // offered by default: keep a finished download's files, and
                // take the partial remains of anything else.
                const keepable = removePrompt(task.status, videos).canKeepFiles;
                await removeConfirm(task.id, !keepable);
                return;
            }

            store.patch({
                removing: {
                    id: task.id,
                    title: task.title,
                    status: task.status,
                    videos,
                },
            });
        },

        removeCancel(): void {
            store.patch({ removing: null });
        },

        /**
         * Run a sweep, or ask first when it removes things. Pausing and
         * resuming are reversible in one click, so they just happen. Clearing
         * is not, so it asks.
         */
        async bulk(action: BulkAction): Promise<void> {
            if (action === "clear_finished") {
                store.patch({ pendingBulk: action });
                return;
            }
            await runBulk(action);
        },

        bulkCancel(): void {
            store.patch({ pendingBulk: null });
        },

        async bulkConfirm(): Promise<void> {
            const action = store.get().pendingBulk;
            store.patch({ pendingBulk: null });
            if (action) await runBulk(action);
        },

        async stopSeeding(taskId: string): Promise<void> {
            try {
                await postApi(`/download/${taskId}/seed/stop`, {});
            } catch (err) {
                app.toasts.notify("error", errorMessage(err));
                return;
            }
            void app.tasks.sync();
        },

        downloadFile(taskId: string, fileIndex?: number): void {
            const task = store.get().tasks.find((t) => t.id === taskId);
            if (!task) return;

            // A torrent's file by its index; any other download's one file is
            // index 0, and a torrent of one selected file hands over that file.
            const index =
                fileIndex ??
                (task.kind === "torrent"
                    ? (task.files?.find((file) => file.selected)?.index ?? 0)
                    : 0);
            const a = document.createElement("a");
            a.href = apiUrl(`/download/${taskId}/file/${index}`, {
                withKey: true,
            });
            a.style.display = "none";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        },
    };
}
