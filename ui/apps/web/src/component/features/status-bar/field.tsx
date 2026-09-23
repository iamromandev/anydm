import { component$, useStore, useVisibleTask$ } from "@qwik.dev/core";
import { diskTone, type Disk, type DiskTone } from "@/lib/api/disk";
import { connectionLabel, type Connection } from "@/lib/connection";
import { HISTORY_LIMIT, appendSample, emptyHistory } from "./history";
import "./field.css";

/**
 * Four numbers rather than a `GlobalStats` object.
 *
 * The footer draws exactly these, and taking them apart keeps a presentational
 * component free of the API layer's task contract.
 */
interface StatusBarProps {
    downloadSpeed: number;
    uploadSpeed: number;
    totalDownloaded: number;
    totalPeers: number;
    connection: Connection;
    /** Null until the API has reported it; the stat stays hidden till then. */
    disk: Disk | null;
}

const DISK_TONE_LABEL: Record<DiskTone, string> = {
    ok: "",
    low: ", running low",
    critical: ", below the minimum: new downloads are refused",
};

interface SpeedGaugeProps {
    label: string;
    value: string;
    active: boolean;
    tone: "down" | "up";
    history: number[];
    max: number;
    icon: any;
}

const SPARK_WIDTH = 120;
const SPARK_HEIGHT = 32;
const SPARK_PAD = 3;
/** How often a sparkline point is taken. */
const SAMPLE_MS = 1000;

export const StatusBar = component$<StatusBarProps>(
    ({
        downloadSpeed,
        uploadSpeed,
        totalDownloaded,
        totalPeers,
        connection,
        disk,
    }) => {
        const history = useStore(emptyHistory());
        const tone = disk ? diskTone(disk) : "ok";

        /**
         * `document-ready` rather than the default: the footer is fixed to the
         * bottom of every page, so gating it on an intersection buys nothing —
         * and the observer does not fire for it at all, which is half of why
         * these sparklines were empty.
         *
         * The timer is the other half. Sampling from tracked props ran this
         * once and never again, leaving one point where a line needs two.
         */
        useVisibleTask$(
            ({ cleanup }) => {
                const sample = () => {
                    const next = appendSample(
                        history,
                        downloadSpeed,
                        uploadSpeed,
                    );
                    history.download = next.download;
                    history.upload = next.upload;
                    history.max = next.max;
                };

                sample();
                const timer = setInterval(sample, SAMPLE_MS);
                cleanup(() => clearInterval(timer));
            },
            { strategy: "document-ready" },
        );

        const downActive = downloadSpeed > 0;
        const upActive = uploadSpeed > 0;

        return (
            <footer
                class={`speed-meter ${downActive || upActive ? "speed-meter--active" : ""}`}
                role="status"
                aria-live="polite"
            >
                <div class="speed-meter-gauges">
                    <SpeedGauge
                        label="Download"
                        value={formatSpeed(downloadSpeed)}
                        active={downActive}
                        tone="down"
                        history={history.download}
                        max={history.max}
                        icon={
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                                aria-hidden="true"
                            >
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                <polyline points="7 10 12 15 17 10" />
                                <line x1="12" y1="15" x2="12" y2="3" />
                            </svg>
                        }
                    />
                    <SpeedGauge
                        label="Upload"
                        value={formatSpeed(uploadSpeed)}
                        active={upActive}
                        tone="up"
                        history={history.upload}
                        max={history.max}
                        icon={
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                                aria-hidden="true"
                            >
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                <polyline points="17 8 12 3 7 8" />
                                <line x1="12" y1="3" x2="12" y2="15" />
                            </svg>
                        }
                    />
                </div>

                <div class="speed-meter-divider" aria-hidden="true" />

                <div class="speed-meter-stats">
                    {disk && (
                        <span
                            class={`speed-meter-stat speed-meter-disk speed-meter-disk--${tone}`}
                            aria-label={`Free disk space ${formatBytes(disk.freeBytes)}${DISK_TONE_LABEL[tone]}`}
                            title={
                                disk.minFreeBytes > 0
                                    ? `${formatBytes(disk.freeBytes)} free of ${formatBytes(disk.totalBytes)}. Downloads stop below ${formatBytes(disk.minFreeBytes)}.`
                                    : `${formatBytes(disk.freeBytes)} free of ${formatBytes(disk.totalBytes)}.`
                            }
                        >
                            <svg
                                width="12"
                                height="12"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                                aria-hidden="true"
                            >
                                <line x1="22" y1="12" x2="2" y2="12" />
                                <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
                                <line x1="6" y1="16" x2="6.01" y2="16" />
                                <line x1="10" y1="16" x2="10.01" y2="16" />
                            </svg>
                            <span class="speed-meter-stat-value">
                                {formatBytes(disk.freeBytes)}
                            </span>
                            <span class="speed-meter-stat-label">Free</span>
                        </span>
                    )}
                    <span
                        class="speed-meter-stat speed-meter-stat--total"
                        aria-label={`Downloaded total ${formatBytes(totalDownloaded)}`}
                    >
                        <svg
                            width="12"
                            height="12"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                            aria-hidden="true"
                        >
                            <ellipse cx="12" cy="5" rx="7" ry="3" />
                            <path d="M5 5v8c0 1.7 3.1 3 7 3s7-1.3 7-3V5" />
                            <path d="M5 13v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
                        </svg>
                        <span class="speed-meter-stat-value">
                            {formatBytes(totalDownloaded)}
                        </span>
                        <span class="speed-meter-stat-label">Total</span>
                    </span>
                    <span
                        class="speed-meter-stat"
                        aria-label={`Connected peers ${totalPeers}`}
                    >
                        <svg
                            width="12"
                            height="12"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                            aria-hidden="true"
                        >
                            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                            <circle cx="9" cy="7" r="4" />
                            <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                            <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                        </svg>
                        <span class="speed-meter-stat-value">{totalPeers}</span>
                        <span class="speed-meter-stat-label">Peers</span>
                    </span>
                    <span
                        class={`speed-meter-link speed-meter-link--${connection}`}
                        title={`Live updates: ${connectionLabel(connection)}`}
                    >
                        <span class="speed-meter-link-dot" aria-hidden="true" />
                        <span class="speed-meter-stat-label">
                            {connectionLabel(connection)}
                        </span>
                    </span>
                </div>
            </footer>
        );
    },
);

export const SpeedGauge = component$<SpeedGaugeProps>(
    ({ label, value, active, tone, history, max, icon }) => {
        const points = sparkPoints(history, max, SPARK_WIDTH, SPARK_HEIGHT);
        const area = points
            ? `${points} ${SPARK_WIDTH},${SPARK_HEIGHT} 0,${SPARK_HEIGHT}`
            : "";

        return (
            <div
                class={`speed-gauge speed-gauge--${tone} ${active ? "speed-gauge--active" : "speed-gauge--idle"}`}
                aria-label={`${label} ${value}`}
            >
                <span class="speed-gauge-icon">{icon}</span>
                <span class="speed-gauge-meta">
                    <span class="speed-gauge-value">{value}</span>
                    <span class="speed-gauge-label">{label}</span>
                </span>
                <svg
                    class="speed-gauge-spark"
                    viewBox={`0 0 ${SPARK_WIDTH} ${SPARK_HEIGHT}`}
                    preserveAspectRatio="none"
                    aria-hidden="true"
                >
                    {area && <polygon points={area} />}
                    {points && (
                        <polyline
                            points={points}
                            vector-effect="non-scaling-stroke"
                            fill="none"
                        />
                    )}
                </svg>
                <span class="speed-gauge-dot" aria-hidden="true" />
            </div>
        );
    },
);

function sparkPoints(
    data: number[],
    max: number,
    width: number,
    height: number,
): string {
    if (data.length < 2) return "";
    const innerHeight = height - SPARK_PAD * 2;
    const step = width / (HISTORY_LIMIT - 1);
    return data
        .map((value, index) => {
            const x = index * step;
            const normalized = Math.min(Math.max(value, 0), max) / max;
            const y = height - SPARK_PAD - normalized * innerHeight;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(" ");
}

function formatSpeed(bytesPerSecond: number): string {
    if (bytesPerSecond === 0) return "0 B/s";
    const units = [
        "B/s",
        "KB/s",
        "MB/s",
        "GB/s",
        "TB/s",
    ];
    let speed = bytesPerSecond;
    let unitIndex = 0;
    while (speed >= 1024 && unitIndex < units.length - 1) {
        speed /= 1024;
        unitIndex++;
    }
    return `${speed.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

function formatBytes(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let value = bytes;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024;
        unitIndex++;
    }
    return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}
