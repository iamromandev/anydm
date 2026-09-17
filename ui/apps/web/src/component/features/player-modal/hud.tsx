import { component$ } from "@qwik.dev/core";
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
}

export const PlayerHud = component$<PlayerHudProps>(
    ({
        connecting,
        peersConnected,
        downloadBps,
        progressBytes,
        totalBytes,
    }) => {
        const stats = swarmStatsView({
            peersConnected,
            downloadBps,
            progressBytes,
            totalBytes,
        });

        return (
            <div
                class={`player-hud ${connecting ? "player-hud--connecting" : "player-hud--playing"}`}
            >
                <div class="player-hud-row">
                    <span class="player-hud-label">Peers</span>
                    <span class="player-hud-value">{stats.peersLabel}</span>
                </div>
                <div class="player-hud-row">
                    <span class="player-hud-label">Speed</span>
                    <span class="player-hud-value">{stats.speedLabel}</span>
                </div>
                <div class="player-hud-row">
                    <span class="player-hud-label">Downloaded</span>
                    <span class="player-hud-value">{stats.percent}%</span>
                </div>
                <div class="player-hud-bar">
                    <div
                        class="player-hud-bar-fill"
                        style={{ width: `${stats.percent}%` }}
                    />
                </div>
                {stats.etaLabel && (
                    <div class="player-hud-row">
                        <span class="player-hud-label">ETA</span>
                        <span class="player-hud-value">{stats.etaLabel}</span>
                    </div>
                )}
            </div>
        );
    },
);
