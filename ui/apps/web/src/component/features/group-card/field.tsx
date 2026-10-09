import { component$ } from "@qwik.dev/core";
import {
    LuChevronDown,
    LuChevronRight,
    LuList,
    LuMonitorPlay,
    LuPause,
    LuPlay,
    LuRotateCcw,
    LuTrash,
} from "@/component/core/icons";
import { CopyButton } from "@/component/features/torrent-card";
import { SpeedDisplay } from "@/component/shared/speed-display";
import {
    canPause,
    canPlayTask,
    canResume,
    countsLine,
    detailRows,
    groupMeta,
    groupProgressLine,
    statusView,
    videoStatus,
    type EntriesView,
    type UiTask,
} from "@/lib/api";
import "./field.css";

export interface GroupCardProps {
    task: UiTask;
    now: number;
    expanded: boolean;
    onToggleDetail: (id: string) => void;
    /** Its open Entries list, or undefined while shut. */
    entries: EntriesView | undefined;
    /** The video Open just brought into view, outlined for a moment. */
    highlightId?: string;
    onToggleEntries: (id: string) => void;
    onLoadMoreEntries: (id: string) => void;
    /** On the group: every video it applies to. */
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onRemove: (id: string) => void;
    onPauseVideo: (id: string) => void;
    onResumeVideo: (id: string) => void;
    /** A finished video, from its file. */
    onPlayVideo: (id: string) => void;
    onRemoveVideo: (groupId: string, id: string) => void;
    /** Play all (part 4): from where it was left. */
    onPlayGroup: (id: string) => void;
}

/**
 * A playlist as one card (v0.5): how many of its videos have finished, with
 * the videos themselves behind Entries. The Entries list is markup here, not
 * a nested component, clear of Qwik's conditional-sibling bug.
 */
export const GroupCard = component$<GroupCardProps>(
    ({
        task,
        now,
        expanded,
        onToggleDetail,
        entries,
        highlightId = "",
        onToggleEntries,
        onLoadMoreEntries,
        onPause,
        onResume,
        onRemove,
        onPauseVideo,
        onResumeVideo,
        onPlayVideo,
        onRemoveVideo,
        onPlayGroup,
    }) => {
        const counts = task.entryCounts;
        const status = statusView(task.status);
        const line = countsLine(counts);
        const total = counts?.total ?? 0;
        // Resume takes the paused and the failed alike; name the one there is.
        const resumeLabel =
            (counts?.paused ?? 0) > 0
                ? "Resume"
                : (counts?.failed ?? 0) > 0
                  ? "Retry failed"
                  : null;

        return (
            <article
                class={`torrent-card torrent-card--${task.status} group-card`}
                data-task-id={task.id}
                data-kind="playlist"
            >
                <div
                    class="torrent-card-main"
                    onClick$={(event) => {
                        // As on the torrent card: Qwik delegates events, so
                        // ask what was clicked rather than stop propagation.
                        const target = event.target as HTMLElement | null;
                        if (target?.closest("button, a, input, select")) return;
                        onToggleDetail(task.id);
                    }}
                >
                    <div class="torrent-header">
                        <div class="torrent-platform">
                            <LuList width="18" height="18" aria-hidden="true" />
                        </div>
                        <div class="torrent-info">
                            <h3 class="torrent-title" title={task.title}>
                                {task.title}
                            </h3>
                            <div class="torrent-meta">
                                <span class="group-card-badge">Playlist</span>
                                <span class="torrent-site">
                                    {groupMeta(task)}
                                </span>
                            </div>
                        </div>
                        <div
                            class="torrent-status-badge"
                            data-status={task.status}
                        >
                            <span>{status.label}</span>
                        </div>
                    </div>

                    <div class="torrent-progress-section">
                        <div class="progress-track-wrapper">
                            <div
                                class="progress-track"
                                role="progressbar"
                                aria-valuenow={Math.round(task.progress)}
                                aria-valuemin={0}
                                aria-valuemax={100}
                                aria-label={`${task.title} videos finished`}
                            >
                                <div
                                    class="progress-fill"
                                    style={{ width: `${task.progress}%` }}
                                />
                            </div>
                        </div>
                        <div class="progress-details">
                            <span class="progress-percent">
                                {groupProgressLine(task)}
                            </span>
                            {task.downloadSpeed > 0 && (
                                <SpeedDisplay
                                    downloadSpeed={task.downloadSpeed}
                                    uploadSpeed={0}
                                    compact
                                />
                            )}
                            {line && (
                                <span class="progress-pending">{line}</span>
                            )}
                        </div>
                    </div>

                    {expanded && (
                        <dl class="torrent-detail">
                            {detailRows(task, now).map((row) => (
                                <div key={row.label} class="torrent-detail-row">
                                    <dt class="torrent-detail-label">
                                        {row.label}
                                    </dt>
                                    <dd
                                        class="torrent-detail-value"
                                        title={row.title}
                                    >
                                        <span class="torrent-detail-text">
                                            {row.value}
                                        </span>
                                        {row.copy && (
                                            <CopyButton value={row.copy} />
                                        )}
                                    </dd>
                                </div>
                            ))}
                        </dl>
                    )}
                </div>

                <div class="torrent-actions">
                    <button
                        type="button"
                        class="action-btn group-card-entries-btn"
                        aria-expanded={entries !== undefined}
                        onClick$={() => onToggleEntries(task.id)}
                    >
                        {entries ? (
                            <LuChevronDown
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        ) : (
                            <LuChevronRight
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        )}
                        <span>Entries</span>
                    </button>
                    {total > 0 && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label="Play all"
                            onClick$={() => onPlayGroup(task.id)}
                        >
                            <LuMonitorPlay
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}
                    {canPause(task.status) && (
                        <button
                            type="button"
                            class="action-btn"
                            aria-label="Pause all"
                            onClick$={() => onPause(task.id)}
                        >
                            <LuPause
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}
                    {resumeLabel && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label={resumeLabel}
                            onClick$={() => onResume(task.id)}
                        >
                            {resumeLabel === "Resume" ? (
                                <LuPlay
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            ) : (
                                <LuRotateCcw
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            )}
                        </button>
                    )}
                    <button
                        type="button"
                        class="action-btn action-btn--danger"
                        aria-label={`Remove ${total} videos`}
                        onClick$={() => onRemove(task.id)}
                    >
                        <LuTrash width="16" height="16" aria-hidden="true" />
                    </button>
                </div>

                {entries && (
                    <div
                        class="group-entries"
                        role="list"
                        aria-label={`${task.title} videos`}
                    >
                        {entries.rows.map((video) => (
                            <div
                                key={video.id}
                                id={`download-${video.id}`}
                                class={`group-entry ${video.id === highlightId ? "group-entry--found" : ""}`}
                                role="listitem"
                            >
                                <span class="group-entry-index">
                                    {video.position}
                                </span>
                                <span
                                    class="group-entry-title"
                                    title={video.title}
                                >
                                    {video.title}
                                </span>
                                <span class="group-entry-status">
                                    {videoStatus(video)}
                                </span>
                                <div class="group-entry-actions">
                                    {canPause(video.status) && (
                                        <button
                                            type="button"
                                            class="action-btn"
                                            aria-label={`Pause ${video.title}`}
                                            onClick$={() =>
                                                onPauseVideo(video.id)
                                            }
                                        >
                                            <LuPause
                                                width="14"
                                                height="14"
                                                aria-hidden="true"
                                            />
                                        </button>
                                    )}
                                    {canResume(video.status) && (
                                        <button
                                            type="button"
                                            class="action-btn"
                                            aria-label={`${video.status === "failed" ? "Retry" : "Resume"} ${video.title}`}
                                            onClick$={() =>
                                                onResumeVideo(video.id)
                                            }
                                        >
                                            <LuRotateCcw
                                                width="14"
                                                height="14"
                                                aria-hidden="true"
                                            />
                                        </button>
                                    )}
                                    {canPlayTask(video) && (
                                        <button
                                            type="button"
                                            class="action-btn"
                                            aria-label={`Play ${video.title}`}
                                            onClick$={() =>
                                                onPlayVideo(video.id)
                                            }
                                        >
                                            <LuMonitorPlay
                                                width="14"
                                                height="14"
                                                aria-hidden="true"
                                            />
                                        </button>
                                    )}
                                    <button
                                        type="button"
                                        class="action-btn action-btn--danger"
                                        aria-label={`Remove ${video.title}`}
                                        onClick$={() =>
                                            onRemoveVideo(task.id, video.id)
                                        }
                                    >
                                        <LuTrash
                                            width="14"
                                            height="14"
                                            aria-hidden="true"
                                        />
                                    </button>
                                </div>
                            </div>
                        ))}
                        {entries.loading && (
                            <p class="group-entries-note">Loading…</p>
                        )}
                        {!entries.loading &&
                            entries.page < entries.totalPages && (
                                <button
                                    type="button"
                                    class="torrent-list-more-btn group-entries-more"
                                    onClick$={() => onLoadMoreEntries(task.id)}
                                >
                                    Load more
                                </button>
                            )}
                    </div>
                )}
            </article>
        );
    },
);
