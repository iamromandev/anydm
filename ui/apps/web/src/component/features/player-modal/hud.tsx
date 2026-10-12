import { swarmStatsView } from "./swarm-stats";
import "./hud.css";

export interface PlayerHudProps {
    /** While connecting the HUD is always visible; once playing it only
     * shows on hover so it doesn't crowd the video controls. */
    connecting: boolean;
    peersConnected: number;
    downloadBps: number;
    progressBytes: number;
    totalBytes: number;
    /** Percent of the video actually buffered ahead of the playhead,
     * separate from the swarm's torrent-wide download percent. */
    bufferedPercent: number;
}

export function PlayerHud({
    connecting,
    peersConnected,
    downloadBps,
    progressBytes,
    totalBytes,
    bufferedPercent,
}: PlayerHudProps) {
    const stats = swarmStatsView({
        peersConnected,
        downloadBps,
        progressBytes,
        totalBytes,
    });

    return (
        <div
            className={`player-hud ${connecting ? "player-hud--connecting" : "player-hud--playing"}`}
        >
            <div className="player-hud-row">
                <span className="player-hud-label">Peers</span>
                <span className="player-hud-value">{stats.peersLabel}</span>
            </div>
            <div className="player-hud-row">
                <span className="player-hud-label">Speed</span>
                <span className="player-hud-value">{stats.speedLabel}</span>
            </div>
            <div className="player-hud-row">
                <span className="player-hud-label">Downloaded</span>
                <span className="player-hud-value">{stats.percent}%</span>
            </div>
            <div className="player-hud-bar">
                <div
                    className="player-hud-bar-fill"
                    style={{ width: `${stats.percent}%` }}
                />
            </div>
            {stats.etaLabel && (
                <div className="player-hud-row">
                    <span className="player-hud-label">ETA</span>
                    <span className="player-hud-value">{stats.etaLabel}</span>
                </div>
            )}
            <div className="player-hud-buffered-bar">
                <div
                    className="player-hud-buffered-bar-fill"
                    style={{ width: `${bufferedPercent}%` }}
                />
            </div>
        </div>
    );
}
