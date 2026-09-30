import { component$, $, useStore } from "@qwik.dev/core";
import {
    TorrentCard,
    type TorrentTask,
} from "@/component/features/torrent-card";
import { GroupCard } from "@/component/features/group-card";
import { StartDoors } from "@/component/features/start-doors";
import { showDoors } from "@/component/features/start-doors/present";
import { LuDownload, LuSearchX } from "@/component/core/icons";
import { isActive, isSeeding, type EntriesView } from "@/lib/api";
import { matchesSearch } from "@/lib/search";
import "./field.css";

export interface TorrentListProps {
    tasks: TorrentTask[];
    filter: TorrentFilter;
    searchQuery: string;
    /** The shared clock every card's retry countdown reads. */
    now: number;
    /** Whether the API has pages left for the current filter. */
    hasMore: boolean;
    loadingMore: boolean;
    onLoadMore: () => void;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onDownloadFile: (id: string, fileIndex?: number) => void;
    /** Play a finished download (#94). */
    onPlay: (id: string) => void;
    onRemove: (id: string) => void;
    onStopSeeding: (id: string) => void;
    /** Each open group's Entries list, by group id (v0.5). */
    entries: Record<string, EntriesView>;
    onToggleEntries: (id: string) => void;
    onLoadMoreEntries: (id: string) => void;
    onPauseVideo: (id: string) => void;
    onResumeVideo: (id: string) => void;
    onRemoveVideo: (groupId: string, id: string) => void;
    /** Play all from a group's card (part 4). */
    onPlayGroup: (id: string) => void;
    /** For the empty list's Browse door: shown when there are torrent sources, and opens Search on Torrents. */
    torrentSearch: boolean;
    onOpenSearch: () => void;
}

export type TorrentFilter =
    | "all"
    | "downloading"
    | "seeding"
    | "completed"
    | `category:${string}`
    | `label:${string}`;

export const TorrentList = component$<TorrentListProps>(
    ({
        tasks,
        filter,
        searchQuery,
        now,
        hasMore,
        loadingMore,
        onLoadMore,
        onPause,
        onResume,
        onDownloadFile,
        onPlay,
        onRemove,
        onStopSeeding,
        entries,
        onToggleEntries,
        onLoadMoreEntries,
        onPauseVideo,
        onResumeVideo,
        onRemoveVideo,
        onPlayGroup,
        torrentSearch,
        onOpenSearch,
    }) => {
        // One card at a time: two open at once turns a list into a wall.
        const store = useStore({ expandedId: "" as string });
        const toggleDetail = $((id: string) => {
            store.expandedId = store.expandedId === id ? "" : id;
        });

        const filteredTasks = tasks.filter((task) => {
            if (!matchesSearch(task, searchQuery)) return false;

            switch (filter) {
                case "downloading":
                    return isActive(task.status);
                case "seeding":
                    return isSeeding(task.status);
                case "completed":
                    return task.status === "complete";
                case "all":
                default:
                    return true;
            }
        });

        const getEmptyState = () => {
            if (showDoors(tasks.length, searchQuery)) {
                return (
                    <StartDoors
                        torrentSearch={torrentSearch}
                        onBrowse={onOpenSearch}
                    />
                );
            }

            if (tasks.length === 0) {
                return (
                    <div class="torrent-list-empty">
                        <div class="torrent-list-empty-icon">
                            <LuDownload
                                width="32"
                                height="32"
                                aria-hidden="true"
                            />
                        </div>
                        <p>No downloads yet</p>
                        <span>Paste a link above or drop a .torrent file</span>
                    </div>
                );
            }

            if (filteredTasks.length === 0) {
                const filterLabels: Record<string, string> = {
                    all: "matching your search",
                    downloading: "currently active",
                    seeding: "seeding",
                    completed: "completed",
                };
                const label = filterLabels[filter] || "matching your filters";
                return (
                    <div class="torrent-list-empty">
                        <div class="torrent-list-empty-icon">
                            <LuSearchX
                                width="32"
                                height="32"
                                aria-hidden="true"
                            />
                        </div>
                        <p>No downloads {label}</p>
                        <span>
                            {searchQuery && hasMore
                                ? `Only the ${tasks.length} loaded downloads were searched. Load more to search further.`
                                : "Try adjusting your filters or search"}
                        </span>
                    </div>
                );
            }

            return null;
        };

        return (
            <div
                id="download-list"
                class="torrent-list"
                role="list"
                aria-label="Downloads"
            >
                {getEmptyState()}
                <div class="torrent-list-items" style={{ contain: "layout" }}>
                    {filteredTasks.map((task) => (
                        <div
                            key={task.id}
                            class="torrent-list-item"
                            style={{ contentVisibility: "auto" }}
                        >
                            {task.kind === "playlist" ? (
                                <GroupCard
                                    now={now}
                                    expanded={store.expandedId === task.id}
                                    onToggleDetail={toggleDetail}
                                    task={task}
                                    entries={entries[task.id]}
                                    onToggleEntries={onToggleEntries}
                                    onLoadMoreEntries={onLoadMoreEntries}
                                    onPause={onPause}
                                    onResume={onResume}
                                    onRemove={onRemove}
                                    onPauseVideo={onPauseVideo}
                                    onResumeVideo={onResumeVideo}
                                    onPlayVideo={onPlay}
                                    onRemoveVideo={onRemoveVideo}
                                    onPlayGroup={onPlayGroup}
                                />
                            ) : (
                                <TorrentCard
                                    now={now}
                                    expanded={store.expandedId === task.id}
                                    onToggleDetail={toggleDetail}
                                    task={task}
                                    onPause={onPause}
                                    onResume={onResume}
                                    onDownloadFile={onDownloadFile}
                                    onPlay={onPlay}
                                    onRemove={onRemove}
                                    onStopSeeding={onStopSeeding}
                                />
                            )}
                        </div>
                    ))}
                </div>

                {hasMore && (
                    <div class="torrent-list-more">
                        <button
                            type="button"
                            class="torrent-list-more-btn"
                            onClick$={onLoadMore}
                            disabled={loadingMore}
                        >
                            {loadingMore ? "Loading…" : "Load more"}
                        </button>
                    </div>
                )}
            </div>
        );
    },
);
