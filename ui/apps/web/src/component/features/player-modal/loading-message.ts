import { formatBytes } from "@/component/core/utils";

const NOTICEABLE_WAIT_SECONDS = 5;
const LONG_WAIT_SECONDS = 30;

/**
 * Starting a torrent-backed stream can legitimately take tens of seconds
 * (metadata resolve + buffering enough to probe format), with nothing to
 * show for it otherwise — a silent spinner then looks identical to a hang.
 */
export function loadingMessage(elapsedSeconds: number): string {
    if (elapsedSeconds < NOTICEABLE_WAIT_SECONDS) {
        return "Starting stream…";
    }
    const base = `Starting stream… (${elapsedSeconds}s)`;
    if (elapsedSeconds < LONG_WAIT_SECONDS) {
        return base;
    }
    return `${base} — first play on a fresh torrent can take a minute`;
}

/**
 * Live swarm status for a torrent-backed stream while it's connecting.
 * rqbit only reports connected-peer count and current throughput, not
 * tracker-style seeder/leecher counts — see the design doc for why.
 */
export function connectingMessage(peersConnected: number, downloadBps: number): string {
    if (peersConnected === 0) {
        return "Connecting to swarm… looking for peers";
    }
    const peerLabel = `${peersConnected} peer${peersConnected === 1 ? "" : "s"}`;
    if (downloadBps === 0) {
        return `Connecting to swarm… ${peerLabel}`;
    }
    return `Connecting to swarm… ${peerLabel}, ${formatBytes(downloadBps)}/s`;
}
