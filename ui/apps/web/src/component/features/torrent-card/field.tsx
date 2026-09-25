import { component$, $, useSignal } from "@qwik.dev/core";
import { SegmentBar } from "@/component/shared/segment-bar";
import { SpeedDisplay } from "@/component/shared/speed-display";
import {
    LuPause,
    LuPlay,
    LuTrash,
    LuMagnet,
    LuCheckCircle,
    LuLoader2,
    LuAlertTriangle,
    LuXCircle,
    LuMonitorPlay,
    LuMusic2,
    LuGlobe,
    LuFileDown,
    LuRotateCcw,
    LuUpload,
    LuCircle,
    SiYoutube,
} from "@/component/core/icons";
import {
    canPause,
    detailRows,
    canResume,
    canStopSeeding,
    canDownloadTorrentFile,
    downloadAction,
    isActive,
    retryLabel,
    siteName,
    statusView,
    type StatusView,
    type TaskKind,
    type UiTask,
} from "@/lib/api";
import "./field.css";

export interface TorrentCardProps {
    task: TorrentTask;
    /**
     * The clock the countdown is measured against, ticked by whoever owns the
     * list. Passed in rather than read here so every card on screen agrees,
     * and so the label stays a pure function of its inputs.
     */
    now: number;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    /** Without an index, the task's one file; with one, that file of a torrent. */
    onDownloadFile: (id: string, fileIndex?: number) => void;
    onRemove: (id: string) => void;
    onStopSeeding: (id: string) => void;
    /** Whether this card is the one showing its details. */
    expanded: boolean;
    onToggleDetail: (id: string) => void;
}

/**
 * Copy one value, or fail gracefully.
 *
 * `navigator.clipboard` exists only in a secure context, which a LAN
 * deployment over plain http is not. Where it is missing the text is selected
 * instead, so copying is still one keystroke away rather than impossible.
 */
export const CopyButton = component$<{ value: string }>(({ value }) => {
    const copied = useSignal(false);

    return (
        <button
            type="button"
            class="torrent-detail-copy"
            aria-label={`Copy ${value}`}
            onClick$={async (_event, el) => {
                try {
                    await navigator.clipboard.writeText(value);
                    copied.value = true;
                    setTimeout(() => {
                        copied.value = false;
                    }, 1200);
                } catch {
                    const text = el.parentElement?.querySelector(
                        ".torrent-detail-text",
                    );
                    if (!text) return;
                    const range = document.createRange();
                    range.selectNodeContents(text);
                    const selection = window.getSelection();
                    selection?.removeAllRanges();
                    selection?.addRange(range);
                }
            }}
        >
            {copied.value ? "Copied" : "Copy"}
        </button>
    );
});

/** The card draws whatever the API returns; `@/lib/api` owns that shape. */
export type TorrentTask = UiTask;

// Total maps, not switches. A switch with no default returned `undefined` for
// any status the UI had not been taught, and the render then died on it.
const PLATFORM_ICONS: Record<TaskKind, typeof LuMagnet> = {
    video: LuMonitorPlay,
    audio: LuMusic2,
    file: LuGlobe,
    torrent: LuMagnet,
};

const STATUS_ICONS: Record<StatusView["key"], typeof LuMagnet> = {
    pending: LuCheckCircle,
    downloading: LuLoader2,
    muxing: LuLoader2,
    paused: LuPause,
    seeding: LuUpload,
    complete: LuCheckCircle,
    failed: LuAlertTriangle,
    canceled: LuXCircle,
    unknown: LuXCircle,
};

export const TorrentCard = component$<TorrentCardProps>(
    ({
        task,
        now,
        onPause,
        onResume,
        onDownloadFile,
        onRemove,
        onStopSeeding,
        expanded,
        onToggleDetail,
    }) => {
        const status = statusView(task.status);
        const retry = retryLabel(task, now);
        // YouTube keeps its own mark; any other site draws what the task is.
        const PlatformIcon =
            task.extractor === "Youtube"
                ? SiYoutube
                : (PLATFORM_ICONS[task.kind] ?? LuMagnet);
        const site = siteName(task.extractor);
        const StatusIcon = STATUS_ICONS[status.key];
        const showProgressDetail =
            isActive(task.status) || task.status === "paused";

        return (
            <article
                class={`torrent-card torrent-card--${task.status}`}
                data-task-id={task.id}
                data-kind={task.kind}
            >
                <div
                    class="torrent-card-main"
                    onClick$={(event) => {
                        // Qwik delegates events from the document, so a
                        // child's stopPropagation does not keep this handler
                        // from running. Asking what was actually clicked does.
                        const target = event.target as HTMLElement | null;
                        if (target?.closest("button, a, input, select")) return;
                        onToggleDetail(task.id);
                    }}
                >
                    <div class="torrent-header">
                        <div class="torrent-platform">
                            <PlatformIcon
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </div>
                        <div class="torrent-info">
                            <h3 class="torrent-title" title={task.title}>
                                {task.title}
                            </h3>
                            <div class="torrent-meta">
                                {site && (
                                    <span class="torrent-site">{site}</span>
                                )}
                                {showProgressDetail && task.totalBytes > 0 && (
                                    <span class="torrent-size">
                                        {formatBytes(task.downloadedBytes)} /{" "}
                                        {formatBytes(task.totalBytes)}
                                    </span>
                                )}
                                {task.infoHash && (
                                    <span
                                        class="torrent-hash"
                                        title={task.infoHash}
                                    >
                                        {task.infoHash.substring(0, 12)}…
                                    </span>
                                )}
                            </div>
                        </div>
                        <div
                            class="torrent-status-badge"
                            data-status={task.status}
                        >
                            <StatusIcon
                                width="12"
                                height="12"
                                aria-hidden="true"
                            />
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
                                aria-label={`${task.title} progress`}
                            >
                                <div
                                    class="progress-fill"
                                    style={{ width: `${task.progress}%` }}
                                />
                            </div>
                        </div>

                        {showProgressDetail &&
                            task.segments &&
                            task.segments.length > 0 && (
                                <SegmentBar
                                    segments={task.segments}
                                    label={task.title}
                                />
                            )}
                        <div class="progress-details">
                            <span class="progress-percent">
                                {task.progress.toFixed(0)}%
                            </span>

                            {task.status === "downloading" && (
                                <SpeedDisplay
                                    downloadSpeed={task.downloadSpeed}
                                    uploadSpeed={task.uploadSpeed}
                                    compact
                                />
                            )}

                            {task.eta > 0 && task.status === "downloading" && (
                                <span
                                    class="progress-eta"
                                    aria-label={`ETA ${formatTime(task.eta)}`}
                                >
                                    ETA {formatTime(task.eta)}
                                </span>
                            )}

                            {task.peersConnected > 0 && (
                                <span
                                    class="progress-peers"
                                    aria-label={`${task.peersConnected} peers connected`}
                                >
                                    {task.peersConnected} peers
                                </span>
                            )}

                            {task.ratio !== undefined && task.ratio > 0 && (
                                <span
                                    class="progress-ratio"
                                    aria-label={`Share ratio ${task.ratio.toFixed(2)}`}
                                >
                                    Ratio {task.ratio.toFixed(2)}
                                </span>
                            )}

                            {task.status === "pending" && !retry && (
                                <span class="progress-pending">Queued</span>
                            )}

                            {task.status === "muxing" && (
                                <span class="progress-pending">
                                    Processing…
                                </span>
                            )}

                            {retry && (
                                <span
                                    class={`progress-retry progress-retry--${retry.tone}`}
                                >
                                    {retry.headline}
                                </span>
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

                    {retry?.detail && (
                        <p class="torrent-retry-detail">{retry.detail}</p>
                    )}

                    {task.files && task.files.length > 0 && (
                        <ul class="torrent-file-progress">
                            {task.files
                                .filter((file) => file.selected)
                                .map((file) => (
                                    <li
                                        key={file.index}
                                        class="torrent-file-row"
                                    >
                                        <span class="torrent-file-path">
                                            {file.path}
                                        </span>
                                        <span class="torrent-file-size">
                                            {file.sizeBytes > 0
                                                ? `${Math.min(
                                                      100,
                                                      Math.floor(
                                                          (file.downloadedBytes /
                                                              file.sizeBytes) *
                                                              100,
                                                      ),
                                                  )}%`
                                                : "—"}
                                        </span>
                                        {canDownloadTorrentFile(
                                            task.status,
                                            file,
                                        ) && (
                                            <button
                                                type="button"
                                                class="action-btn torrent-file-download"
                                                aria-label={`Download ${file.path}`}
                                                onClick$={() =>
                                                    onDownloadFile(
                                                        task.id,
                                                        file.index,
                                                    )
                                                }
                                            >
                                                <LuFileDown
                                                    width="14"
                                                    height="14"
                                                    aria-hidden="true"
                                                />
                                            </button>
                                        )}
                                    </li>
                                ))}
                        </ul>
                    )}
                </div>

                <div class="torrent-actions">
                    {/* Gated on status, not kind. Gating on `kind === "torrent"`
                        hid both controls from every task the API can currently
                        produce, which is all of them. */}
                    {canPause(task.status) && (
                        <button
                            type="button"
                            class="action-btn"
                            aria-label="Pause download"
                            onClick$={() => onPause(task.id)}
                        >
                            <LuPause
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    {canResume(task.status) && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label={
                                task.status === "failed"
                                    ? "Retry download"
                                    : "Resume download"
                            }
                            onClick$={() => onResume(task.id)}
                        >
                            {task.status === "failed" ? (
                                <LuRotateCcw
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            ) : (
                                <LuPlay
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            )}
                        </button>
                    )}

                    {downloadAction(task) !== "none" && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label={
                                downloadAction(task) === "choose"
                                    ? "Choose a file to download"
                                    : "Download file"
                            }
                            onClick$={() => {
                                // Several files have no single download: the
                                // detail lists each one with its own link.
                                if (downloadAction(task) === "choose") {
                                    if (!expanded) onToggleDetail(task.id);
                                } else {
                                    onDownloadFile(task.id);
                                }
                            }}
                        >
                            <LuFileDown
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    {canStopSeeding(task.status) && (
                        <button
                            type="button"
                            class="action-btn"
                            aria-label="Stop seeding"
                            onClick$={() => onStopSeeding(task.id)}
                        >
                            <LuCircle
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    <button
                        type="button"
                        class="action-btn action-btn--danger"
                        aria-label={
                            isActive(task.status)
                                ? "Stop and remove download"
                                : "Remove from list"
                        }
                        onClick$={() => onRemove(task.id)}
                    >
                        <LuTrash width="16" height="16" aria-hidden="true" />
                    </button>
                </div>
            </article>
        );
    },
);

function formatBytes(bytes: number): string {
    if (bytes === 0) return "0 B";
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let size = bytes;
    let unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

function formatTime(seconds: number): string {
    if (seconds <= 0) return "—";
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hrs > 0) return `${hrs}h ${mins}m`;
    if (mins > 0) return `${mins}m ${secs}s`;
    return `${secs}s`;
}
