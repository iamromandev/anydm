import { formatSpeed, formatTime } from "@/component/core/utils";

export interface SwarmStats {
    peersConnected: number;
    downloadBps: number;
    progressBytes: number;
    totalBytes: number;
}

export interface SwarmStatsView {
    peersLabel: string;
    speedLabel: string;
    percent: number;
    etaLabel: string | null;
}

/** Formats the live torrent-swarm fields carried on `stream_status` events into HUD text. */
export function swarmStatsView(stats: SwarmStats): SwarmStatsView {
    const { peersConnected, downloadBps, progressBytes, totalBytes } = stats;

    const peersLabel = `${peersConnected} peer${peersConnected === 1 ? "" : "s"}`;
    const speedLabel = downloadBps > 0 ? formatSpeed(downloadBps) : "—";
    const percent =
        totalBytes > 0
            ? Math.min(100, Math.round((progressBytes / totalBytes) * 100))
            : 0;

    const remainingBytes = totalBytes - progressBytes;
    const isComplete = totalBytes > 0 && remainingBytes <= 0;
    const etaLabel = isComplete
        ? null
        : downloadBps > 0
          ? formatTime(Math.ceil(remainingBytes / downloadBps))
          : "Calculating…";

    return { peersLabel, speedLabel, percent, etaLabel };
}
