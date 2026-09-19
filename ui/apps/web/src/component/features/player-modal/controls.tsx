import { $, component$, useOnWindow, useSignal } from "@qwik.dev/core";
import {
    LuMaximize,
    LuMinimize,
    LuPause,
    LuPlay,
    LuVolume2,
    LuVolumeX,
} from "@/component/core/icons";
import {
    formatClockTime,
    seekRatioFromPointerX,
    type ScrubberSegments,
} from "./scrubber-progress";
import "./controls.css";

export interface PlayerControlsProps {
    paused: boolean;
    currentTime: number;
    duration: number;
    volume: number;
    muted: boolean;
    playbackRate: number;
    fullscreen: boolean;
    segments: ScrubberSegments;
    onTogglePlay: () => void;
    onSeek: (time: number) => void;
    onVolumeChange: (volume: number) => void;
    onToggleMute: () => void;
    onPlaybackRateChange: (rate: number) => void;
    onToggleFullscreen: () => void;
}

const PLAYBACK_RATES = [
    0.5,
    1,
    1.25,
    1.5,
    2,
];

export const PlayerControls = component$<PlayerControlsProps>(
    ({
        paused,
        currentTime,
        duration,
        volume,
        muted,
        playbackRate,
        fullscreen,
        segments,
        onTogglePlay,
        onSeek,
        onVolumeChange,
        onToggleMute,
        onPlaybackRateChange,
        onToggleFullscreen,
    }) => {
        const trackRef = useSignal<HTMLDivElement>();
        const isDragging = useSignal(false);
        const hoverRatio = useSignal<number | null>(null);

        // Drag can carry the pointer off the track element itself, so these
        // listen on the window rather than the track — a native <input
        // type=range> gets this for free, but the three-segment fill this
        // scrubber renders needs a plain div instead.
        useOnWindow(
            "pointermove",
            $((event: Event) => {
                if (!isDragging.value) {
                    return;
                }
                const rect = trackRef.value?.getBoundingClientRect();
                if (!rect) {
                    return;
                }
                const ratio = seekRatioFromPointerX({
                    clientX: (event as PointerEvent).clientX,
                    rectLeft: rect.left,
                    rectWidth: rect.width,
                });
                onSeek(ratio * duration);
            }),
        );

        useOnWindow(
            "pointerup",
            $(() => {
                isDragging.value = false;
            }),
        );

        return (
            <div class="player-controls">
                <div
                    ref={trackRef}
                    class="player-controls-track"
                    onPointerDown$={(event: PointerEvent) => {
                        isDragging.value = true;
                        const rect = trackRef.value?.getBoundingClientRect();
                        if (!rect) {
                            return;
                        }
                        const ratio = seekRatioFromPointerX({
                            clientX: event.clientX,
                            rectLeft: rect.left,
                            rectWidth: rect.width,
                        });
                        onSeek(ratio * duration);
                    }}
                    onPointerMove$={(event: PointerEvent) => {
                        const rect = (
                            event.currentTarget as HTMLDivElement
                        ).getBoundingClientRect();
                        hoverRatio.value = seekRatioFromPointerX({
                            clientX: event.clientX,
                            rectLeft: rect.left,
                            rectWidth: rect.width,
                        });
                    }}
                    onPointerLeave$={() => {
                        hoverRatio.value = null;
                    }}
                >
                    <div
                        class="player-controls-fill player-controls-fill--swarm"
                        style={{ width: `${segments.swarmPercent}%` }}
                    />
                    <div
                        class="player-controls-fill player-controls-fill--buffered"
                        style={{ width: `${segments.bufferedPercent}%` }}
                    />
                    <div
                        class="player-controls-fill player-controls-fill--played"
                        style={{ width: `${segments.playedPercent}%` }}
                    />
                    <div
                        class="player-controls-thumb"
                        style={{ left: `${segments.playedPercent}%` }}
                    />
                    {hoverRatio.value !== null && (
                        <div
                            class="player-controls-tooltip"
                            style={{ left: `${hoverRatio.value * 100}%` }}
                        >
                            {formatClockTime(hoverRatio.value * duration)}
                        </div>
                    )}
                </div>

                <div class="player-controls-row">
                    <button
                        type="button"
                        class="player-controls-button"
                        onClick$={onTogglePlay}
                        aria-label={paused ? "Play" : "Pause"}
                    >
                        {paused ? (
                            <LuPlay width="18" height="18" aria-hidden="true" />
                        ) : (
                            <LuPause
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        )}
                    </button>

                    <button
                        type="button"
                        class="player-controls-button"
                        onClick$={onToggleMute}
                        aria-label={muted || volume === 0 ? "Unmute" : "Mute"}
                    >
                        {muted || volume === 0 ? (
                            <LuVolumeX
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        ) : (
                            <LuVolume2
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        )}
                    </button>

                    <input
                        type="range"
                        class="player-controls-volume"
                        min="0"
                        max="1"
                        step="0.01"
                        value={volume}
                        onInput$={(e: Event) => {
                            onVolumeChange(
                                Number((e.target as HTMLInputElement).value),
                            );
                        }}
                        aria-label="Volume"
                    />

                    <span class="player-controls-time">
                        {formatClockTime(currentTime)} /{" "}
                        {formatClockTime(duration)}
                    </span>

                    <select
                        class="player-controls-rate"
                        value={String(playbackRate)}
                        onChange$={(e: Event) => {
                            onPlaybackRateChange(
                                Number((e.target as HTMLSelectElement).value),
                            );
                        }}
                        aria-label="Playback speed"
                    >
                        {PLAYBACK_RATES.map((rate) => (
                            <option key={rate} value={rate}>
                                {`${rate}x`}
                            </option>
                        ))}
                    </select>

                    <button
                        type="button"
                        class="player-controls-button"
                        onClick$={onToggleFullscreen}
                        aria-label={
                            fullscreen ? "Exit fullscreen" : "Fullscreen"
                        }
                    >
                        {fullscreen ? (
                            <LuMinimize
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        ) : (
                            <LuMaximize
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        )}
                    </button>
                </div>
            </div>
        );
    },
);
