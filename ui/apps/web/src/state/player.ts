import {
    getPageApi,
    normalizeApiTask,
    pickFileToOpen,
    playsFromTorrent,
    withPosition,
    type PositionView,
} from "@/lib/api";
import { mediaFiles, type PlayableFile } from "@/lib/media";
import {
    adjacentItem,
    queueFromVideos,
    resumeItem,
    startItem,
    type QueueItem,
} from "@/lib/queue";
import type { App } from "./app";
import type { AppState, Store } from "./store";

/** Videos per page when a group's play queue fetches more. The API caps it at 100. */
const QUEUE_PAGE = 100;

/** What the player's Previous / Next buttons need to know about the queue. */
export function selectPlayerNav(state: AppState): {
    hasPrevious: boolean;
    hasNext: boolean;
    nextTitle: string;
} {
    const { queue, queueIndex, queueGroup } = state;
    const next = adjacentItem(queue, queueIndex, 1);
    return {
        hasPrevious: adjacentItem(queue, queueIndex, -1) !== null,
        hasNext:
            next !== null ||
            Boolean(queueGroup && queueGroup.page < queueGroup.totalPages),
        nextTitle: queue[next ?? -1]?.title ?? "",
    };
}

/** The player modal, and the queue of items it plays through. */
export function createPlayer(store: Store, app: App) {
    const noQueue = (): Partial<AppState> => ({
        queue: [],
        queueIndex: -1,
        queueGroup: null,
    });

    /** Point the player at one queue item. */
    function playQueueItem(index: number): void {
        const item = store.get().queue[index];
        if (!item) return;
        if ("taskId" in item.source) {
            store.patch({
                queueIndex: index,
                playerFileIndex: null,
                playerFiles: [],
                playerFromTorrent: false,
                playerUrl: "",
                playerKind: "",
                playerPositions: item.positions ?? [],
                playerTaskId: item.source.taskId,
                playerModalOpen: true,
            });
        } else {
            store.patch({
                queueIndex: index,
                playerFileIndex: null,
                playerFiles: [],
                playerFromTorrent: false,
                playerTaskId: "",
                playerPositions: [],
                playerKind: "site",
                playerUrl: item.source.url,
                playerModalOpen: true,
            });
        }
    }

    /** The next page of a group's videos onto the queue, if there is one. */
    async function loadQueuePage(): Promise<boolean> {
        const group = store.get().queueGroup;
        if (!group || group.page >= group.totalPages) return false;
        const result = await getPageApi<any[]>(
            `/collection/${group.id}/downloads?page=${group.page + 1}&page_size=${QUEUE_PAGE}`,
        ).catch(() => null);
        // Read again: the queue may have been closed or replaced meanwhile.
        if (result === null || store.get().queueGroup?.id !== group.id) {
            return false;
        }
        store.patch((s) => ({
            queue: [
                ...s.queue,
                ...queueFromVideos(result.data.map(normalizeApiTask)),
            ],
            queueGroup: {
                id: group.id,
                page: result.meta.page,
                totalPages: result.meta.totalPages,
            },
        }));
        return true;
    }

    return {
        /** Open the player on a source; for a torrent, which file and the ones to switch between. */
        playClick(
            value: string,
            kind: string,
            fileIndex: number | null = null,
            files: PlayableFile[] = [],
        ): void {
            store.patch({
                ...noQueue(),
                playerTaskId: "",
                playerFileIndex: fileIndex,
                playerFiles: files,
                playerFromTorrent: false,
                playerUrl: value,
                playerKind: kind,
                playerModalOpen: true,
            });
        },

        /** A finished download, played from its file rather than its source. */
        playTask(taskId: string): void {
            const task = store.get().tasks.find((t) => t.id === taskId);
            store.patch({
                ...noQueue(),
                playerUrl: "",
                playerKind: "",
                // The file left partway through, else the first not yet
                // watched; `null` for a task with no file list, which lets the
                // API pick as it always has.
                playerFileIndex: task
                    ? pickFileToOpen(task, task.positions)
                    : null,
                // A torrent's files, for the player's file menu.
                playerFiles: mediaFiles(task?.files ?? []),
                // Still downloading: its stream, not its partial file.
                playerFromTorrent: task ? playsFromTorrent(task) : false,
                // Where each file was left, to resume there.
                playerPositions: task?.positions ?? [],
                playerTaskId: taskId,
                playerModalOpen: true,
            });
        },

        /**
         * The player saved where it is: the card's bar follows at once, and the
         * next Play resumes there.
         */
        positionSaved(taskId: string, position: PositionView): void {
            store.patch((s) => ({
                tasks: s.tasks.map((task) =>
                    task.id === taskId ? withPosition(task, position) : task,
                ),
                ...(s.playerTaskId === taskId
                    ? {
                          playerPositions: [
                              ...s.playerPositions.filter(
                                  (p) => p.fileIndex !== position.fileIndex,
                              ),
                              position,
                          ],
                      }
                    : {}),
            }));
        },

        close(): void {
            store.patch({ ...noQueue(), playerModalOpen: false });
        },

        playQueueItem,
        loadQueuePage,

        /** Next or Previous from the player; near the end of what's loaded, load more. */
        async step(dir: 1 | -1): Promise<void> {
            const at = () => store.get();
            let next = adjacentItem(at().queue, at().queueIndex, dir);
            if (next === null && dir === 1 && (await loadQueuePage())) {
                next = adjacentItem(at().queue, at().queueIndex, dir);
            }
            if (next === null) return;
            playQueueItem(next);
            if (dir === 1 && at().queue.length - next <= 3) {
                void loadQueuePage();
            }
        },

        /** Play all from the picker: its ticked videos, streamed in order. */
        playQueue(items: QueueItem[], start: number): void {
            store.patch({ queueGroup: null, queue: items });
            playQueueItem(start);
        },

        /**
         * Play all from a group's card: from where it was left, finished
         * videos from their files and the rest from their pages. Pages are
         * loaded until one holds a video to resume, or there are none left.
         */
        async playGroup(groupId: string): Promise<void> {
            store.patch({
                queue: [],
                queueIndex: -1,
                queueGroup: { id: groupId, page: 0, totalPages: 1 },
            });
            while (
                resumeItem(store.get().queue) === null &&
                (await loadQueuePage())
            ) {
                // keep loading
            }
            if (store.get().queueGroup?.id !== groupId) return;
            const start = startItem(store.get().queue);
            if (start === null) {
                app.toasts.notify(
                    "info",
                    "Nothing in this playlist can be played yet",
                );
                return;
            }
            playQueueItem(start);
        },
    };
}
