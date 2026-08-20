import { component$, $ } from "@qwik.dev/core";
import { SpeedDisplay } from "@/component/shared/speed-display";
import {
    LuPause,
    LuPlay,
    LuTrash,
    LuMagnet,
    LuCheckCircle,
    LuLoader2,
    LuAlertTriangle,
    LuMonitorPlay,
    LuMusic2,
    LuGlobe,
    LuFileDown,
    LuRotateCcw,
} from "@/component/core/icons";
import "./field.css";

export interface TorrentCardProps {
    task: TorrentTask;
    onPause: (id: string) => void;
    onResume: (id: string) => void;
    onDownloadFile: (id: string) => void;
    onRemove: (id: string) => void;
}

export interface TorrentTask {
    id: string;
    title: string;
    url: string;
    kind: "video" | "audio" | "torrent" | "url";
    status: TaskStatus;
    progress: number;
    error?: string;
    downloadSpeed?: number;
    uploadSpeed?: number;
    eta?: number;
    peersConnected?: number;
    downloadedBytes?: number;
    totalBytes?: number;
    seeders?: number;
    leechers?: number;
    ratio?: number;
    infoHash?: string;
    addedAt?: number;
    completedAt?: number;
}

type TaskStatus =
    | "pending"
    | "downloading"
    | "verifying"
    | "checking"
    | "paused"
    | "seeding"
    | "complete"
    | "failed";

function getPlatformIcon(kind: TorrentTask["kind"]) {
    switch (kind) {
        case "video":
            return LuMonitorPlay;
        case "audio":
            return LuMusic2;
        case "url":
            return LuGlobe;
        case "torrent":
        default:
            return LuMagnet;
    }
}

export const TorrentCard = component$<TorrentCardProps>(
    ({ task, onPause, onResume, onDownloadFile, onRemove }) => {
        const PlatformIcon = getPlatformIcon(task.kind);
        const statusConfig = getStatusConfig(task.status);
        const showProgressDetail =
            task.status === "downloading" ||
            task.status === "seeding" ||
            task.status === "paused";
        const hasTelemetry =
            task.kind === "torrent" &&
            (task.downloadSpeed !== undefined ||
                task.uploadSpeed !== undefined);

        return (
            <article
                class={`torrent-card torrent-card--${task.status}`}
                data-task-id={task.id}
                data-kind={task.kind}
            >
                <div class="torrent-card-main">
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
                                {showProgressDetail &&
                                    task.totalBytes &&
                                    task.downloadedBytes !== undefined && (
                                        <span class="torrent-size">
                                            {formatBytes(task.downloadedBytes)}{" "}
                                            / {formatBytes(task.totalBytes)}
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
                            <statusConfig.icon
                                width="12"
                                height="12"
                                aria-hidden="true"
                            />
                            <span>{statusConfig.label}</span>
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
                        <div class="progress-details">
                            <span class="progress-percent">
                                {task.progress.toFixed(0)}%
                            </span>

                            {showProgressDetail && hasTelemetry && (
                                <SpeedDisplay
                                    downloadSpeed={task.downloadSpeed || 0}
                                    uploadSpeed={task.uploadSpeed || 0}
                                    compact
                                />
                            )}

                            {task.eta &&
                                task.eta > 0 &&
                                task.status === "downloading" && (
                                    <span
                                        class="progress-eta"
                                        aria-label={`ETA ${formatTime(task.eta)}`}
                                    >
                                        ETA {formatTime(task.eta)}
                                    </span>
                                )}

                            {task.peersConnected !== undefined &&
                                task.peersConnected > 0 && (
                                    <span
                                        class="progress-peers"
                                        aria-label={`${task.peersConnected} peers connected`}
                                    >
                                        {task.peersConnected} peers
                                    </span>
                                )}

                            {task.ratio !== undefined &&
                                task.status === "seeding" && (
                                    <span
                                        class="progress-ratio"
                                        aria-label={`Share ratio ${task.ratio.toFixed(2)}`}
                                    >
                                        Ratio {task.ratio.toFixed(2)}
                                    </span>
                                )}

                            {task.status === "pending" && (
                                <span class="progress-pending">Queued</span>
                            )}

                            {task.status === "verifying" && (
                                <span class="progress-pending">Verifying…</span>
                            )}

                            {task.status === "checking" && (
                                <span class="progress-pending">Checking…</span>
                            )}

                            {task.status === "failed" && task.error && (
                                <span class="progress-error">{task.error}</span>
                            )}
                        </div>
                    </div>
                </div>

                <div class="torrent-actions">
                    {task.kind === "torrent" &&
                        (task.status === "downloading" ||
                            task.status === "pending" ||
                            task.status === "verifying" ||
                            task.status === "checking") && (
                            <button
                                type="button"
                                class="action-btn"
                                aria-label="Pause torrent"
                                onClick$={() => onPause(task.id)}
                            >
                                <LuPause
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            </button>
                        )}

                    {task.kind === "torrent" && task.status === "paused" && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label="Resume torrent"
                            onClick$={() => onResume(task.id)}
                        >
                            <LuPlay width="16" height="16" aria-hidden="true" />
                        </button>
                    )}

                    {(task.status === "complete" ||
                        task.status === "seeding") && (
                        <button
                            type="button"
                            class="action-btn action-btn--primary"
                            aria-label="Download file"
                            onClick$={() => onDownloadFile(task.id)}
                        >
                            <LuFileDown
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    {task.status === "failed" && (
                        <button
                            type="button"
                            class="action-btn"
                            aria-label="Retry download"
                            onClick$={() => onResume(task.id)}
                        >
                            <LuRotateCcw
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
                            task.status === "downloading" ||
                            task.status === "seeding"
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

function getStatusConfig(status: TaskStatus) {
    switch (status) {
        case "pending":
            return {
                icon: LuCheckCircle,
                label: "Queued",
                class: "status-pending",
            };
        case "downloading":
            return {
                icon: LuLoader2,
                label: "Downloading",
                class: "status-downloading",
            };
        case "verifying":
        case "checking":
            return {
                icon: LuLoader2,
                label: "Verifying",
                class: "status-verifying",
            };
        case "paused":
            return { icon: LuPause, label: "Paused", class: "status-paused" };
        case "seeding":
            return {
                icon: LuCheckCircle,
                label: "Seeding",
                class: "status-seeding",
            };
        case "complete":
            return {
                icon: LuCheckCircle,
                label: "Complete",
                class: "status-complete",
            };
        case "failed":
            return {
                icon: LuAlertTriangle,
                label: "Failed",
                class: "status-failed",
            };
    }
}

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
