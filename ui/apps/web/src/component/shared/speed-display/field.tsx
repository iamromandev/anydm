import { component$ } from "@qwik.dev/core";
import "./field.css";

interface SpeedDisplayProps {
    downloadSpeed: number;
    uploadSpeed: number;
    compact?: boolean;
}

export const SpeedDisplay = component$<SpeedDisplayProps>(
    ({ downloadSpeed, uploadSpeed, compact = false }) => {
        return (
            <div
                class={`speed-display ${compact ? "speed-display--compact" : ""}`}
                aria-label="Transfer speeds"
            >
                <span
                    class="speed-down"
                    aria-label={`Download ${formatSpeed(downloadSpeed)}`}
                >
                    <svg
                        width="12"
                        height="12"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        aria-hidden="true"
                    >
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                        <polyline points="7 10 12 15 17 10" />
                        <line x1="12" y1="15" x2="12" y2="3" />
                    </svg>
                    <span>{formatSpeed(downloadSpeed)}</span>
                </span>
                <span class="speed-sep" aria-hidden="true">
                    {compact ? "" : "/"}
                </span>
                <span
                    class="speed-up"
                    aria-label={`Upload ${formatSpeed(uploadSpeed)}`}
                >
                    <svg
                        width="12"
                        height="12"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        aria-hidden="true"
                    >
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                        <polyline points="7 10 12 15 17 10" />
                        <line x1="12" y1="15" x2="12" y2="3" />
                    </svg>
                    <span>{formatSpeed(uploadSpeed)}</span>
                </span>
            </div>
        );
    },
);

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
