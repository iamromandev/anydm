import "./field.css";

interface SpeedDisplayProps {
    downloadSpeed: number;
    uploadSpeed: number;
    compact?: boolean;
}

export function SpeedDisplay({
    downloadSpeed,
    uploadSpeed,
    compact = false,
}: SpeedDisplayProps) {
    {
        return (
            <div
                className={`speed-display ${compact ? "speed-display--compact" : ""}`}
                aria-label="Transfer speeds"
            >
                <span
                    className="speed-down"
                    aria-label={`Download ${formatSpeed(downloadSpeed)}`}
                >
                    <svg
                        width="12"
                        height="12"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        aria-hidden="true"
                    >
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                        <polyline points="7 10 12 15 17 10" />
                        <line x1="12" y1="15" x2="12" y2="3" />
                    </svg>
                    <span>{formatSpeed(downloadSpeed)}</span>
                </span>
                <span className="speed-sep" aria-hidden="true">
                    {compact ? "" : "/"}
                </span>
                <span
                    className="speed-up"
                    aria-label={`Upload ${formatSpeed(uploadSpeed)}`}
                >
                    <svg
                        width="12"
                        height="12"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
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
    }
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
