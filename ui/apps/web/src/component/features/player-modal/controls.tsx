import { $, component$, useOnWindow, useSignal } from "@qwik.dev/core";
import {
    LuCaptions,
    LuCheck,
    LuChevronLeft,
    LuChevronRight,
    LuMaximize,
    LuMinimize,
    LuPause,
    LuPlay,
    LuFastForward,
    LuRewind,
    LuSettings,
    LuSkipBack,
    LuSkipForward,
    LuVolume2,
    LuVolumeX,
} from "@/component/core/icons";
import {
    formatClockTime,
    seekRatioFromPointerX,
    type ScrubberSegments,
} from "./scrubber-progress";
import { type AudioTrack, audioTrackLabel } from "@/lib/audio";
import type { PlayableFile } from "@/lib/media";
import { defaultQualityLabel, type QualityMenu } from "@/lib/quality";
import {
    type SubtitleTrack,
    subtitleToToggleOn,
    subtitleTrackLabel,
} from "@/lib/subtitles";
import {
    availablePages,
    formatRate,
    SETTINGS_PAGE_TITLES,
    type SettingsPage,
} from "./settings-menu";
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
    /** The skip buttons: back (-1) or forward (1) by `skipSeconds`. */
    onSkip?: (direction: 1 | -1) => void;
    skipSeconds?: number;
    onVolumeChange: (volume: number) => void;
    onToggleMute: () => void;
    onPlaybackRateChange: (rate: number) => void;
    onToggleFullscreen: () => void;
    /** A torrent's media files, when there's more than one to choose from (#98). */
    files?: PlayableFile[];
    currentFileIndex?: number | null;
    onPickFile?: (index: number) => void;
    /** Previous/Next buttons, shown when `showSteps` says so (#97, part 4). */
    onPreviousFile?: () => void;
    onNextFile?: () => void;
    hasPreviousFile?: boolean;
    hasNextFile?: boolean;
    /** Prev and Next: a torrent's files, or a play queue (part 4). */
    showSteps?: boolean;
    /** The source's audio tracks, when there's more than one (#99). */
    audioTracks?: AudioTrack[];
    audioTrack?: number | null;
    /** A switch is on its way: the old track plays until the new one is ready. */
    audioPending?: boolean;
    onPickAudio?: (index: number) => void;
    /** The source's subtitle tracks (#100), and the one showing; `null` is off. */
    subtitleTracks?: SubtitleTrack[];
    subtitleTrack?: number | null;
    onPickSubtitle?: (index: number | null) => void;
    /** The quality menu (#103), and whether a switch is getting ready. */
    quality?: QualityMenu;
    qualityPending?: boolean;
    onPickQuality?: (height: number | null) => void;
}

const PLAYBACK_RATES = [
    0.5,
    1,
    1.25,
    1.5,
    2,
];

const MenuOption = component$<{
    checked: boolean;
    label: string;
    disabled?: boolean;
    onPick$: () => void;
}>(({ checked, label, disabled, onPick$ }) => (
    <button
        type="button"
        role="menuitemradio"
        aria-checked={checked}
        class="player-controls-menu-option"
        disabled={disabled}
        onClick$={onPick$}
    >
        <LuCheck
            class="player-controls-menu-tick"
            width="16"
            height="16"
            aria-hidden="true"
        />
        <span class="player-controls-menu-label">{label}</span>
    </button>
));

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
        onSkip,
        skipSeconds,
        onVolumeChange,
        onToggleMute,
        onPlaybackRateChange,
        onToggleFullscreen,
        files,
        currentFileIndex,
        onPickFile,
        onPreviousFile,
        onNextFile,
        hasPreviousFile,
        hasNextFile,
        showSteps,
        audioTracks,
        audioTrack,
        audioPending,
        onPickAudio,
        subtitleTracks,
        subtitleTrack,
        onPickSubtitle,
        quality,
        qualityPending,
        onPickQuality,
    }) => {
        const trackRef = useSignal<HTMLDivElement>();
        const isDragging = useSignal(false);
        const hoverRatio = useSignal<number | null>(null);
        // The settings menu: closed, or open on its list of rows ("main") or
        // on one row's options.
        const menuOpen = useSignal(false);
        const menuPage = useSignal<SettingsPage | "main">("main");
        // The track captions came back to last time they were switched off.
        const lastSubtitle = useSignal<number | null>(null);

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

        // A press anywhere but the menu and its gear dismisses the menu.
        useOnWindow(
            "pointerdown",
            $((event: Event) => {
                const target = event.target as Element | null;
                if (
                    !target?.closest?.(".player-controls-menu, [aria-haspopup]")
                ) {
                    menuOpen.value = false;
                }
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
                    {showSteps && (
                        <button
                            type="button"
                            class="player-controls-button"
                            onClick$={onPreviousFile}
                            disabled={!hasPreviousFile}
                            aria-label="Previous file"
                        >
                            <LuSkipBack
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    {onSkip && duration > 0 && (
                        <button
                            type="button"
                            class="player-controls-button"
                            onClick$={() => onSkip(-1)}
                            aria-label={`Back ${skipSeconds ?? 10} seconds`}
                        >
                            <LuRewind
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>
                    )}

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

                    {onSkip && duration > 0 && (
                        <button
                            type="button"
                            class="player-controls-button"
                            onClick$={() => onSkip(1)}
                            aria-label={`Forward ${skipSeconds ?? 10} seconds`}
                        >
                            <LuFastForward
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    {showSteps && (
                        <button
                            type="button"
                            class="player-controls-button"
                            onClick$={onNextFile}
                            disabled={!hasNextFile}
                            aria-label="Next file"
                        >
                            <LuSkipForward
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>
                    )}

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

                    <span class="player-controls-spacer" />

                    {subtitleTracks && subtitleTracks.length > 0 && (
                        <button
                            type="button"
                            class="player-controls-button"
                            aria-pressed={(subtitleTrack ?? null) !== null}
                            aria-label="Subtitles"
                            onClick$={() => {
                                if (subtitleTrack != null) {
                                    lastSubtitle.value = subtitleTrack;
                                    onPickSubtitle?.(null);
                                    return;
                                }
                                const next = subtitleToToggleOn(
                                    subtitleTracks,
                                    lastSubtitle.value,
                                    null,
                                );
                                if (next !== null) onPickSubtitle?.(next);
                            }}
                        >
                            <LuCaptions
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>
                    )}

                    <button
                        type="button"
                        class="player-controls-button"
                        aria-label="Settings"
                        aria-haspopup="menu"
                        aria-expanded={menuOpen.value}
                        onClick$={() => {
                            menuPage.value = "main";
                            menuOpen.value = !menuOpen.value;
                        }}
                    >
                        <LuSettings width="18" height="18" aria-hidden="true" />
                    </button>

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
                {menuOpen.value && (
                    <div
                        class="player-controls-menu"
                        role="menu"
                        aria-label="Settings"
                        stoppropagation:keydown
                        onKeyDown$={(e: KeyboardEvent) => {
                            if (e.key !== "Escape") return;
                            if (menuPage.value === "main") {
                                menuOpen.value = false;
                            } else {
                                menuPage.value = "main";
                            }
                        }}
                    >
                        {menuPage.value === "main" ? (
                            availablePages({
                                files: files?.length ?? 0,
                                audio: audioTracks?.length ?? 0,
                                subtitles: subtitleTracks?.length ?? 0,
                                quality: quality?.heights.length ?? 0,
                            }).map((page) => (
                                <button
                                    key={page}
                                    type="button"
                                    role="menuitem"
                                    class="player-controls-menu-row"
                                    onClick$={() => {
                                        menuPage.value = page;
                                    }}
                                >
                                    <span class="player-controls-menu-label">
                                        {SETTINGS_PAGE_TITLES[page]}
                                    </span>
                                    <span class="player-controls-menu-value">
                                        {page === "speed" &&
                                            formatRate(playbackRate)}
                                        {page === "quality" &&
                                            quality &&
                                            (quality.chosen === null
                                                ? defaultQualityLabel(quality)
                                                : `${quality.chosen}p`)}
                                        {page === "audio" &&
                                            audioTracks &&
                                            audioTracks
                                                .filter(
                                                    (t) =>
                                                        t.index === audioTrack,
                                                )
                                                .map((t) =>
                                                    audioTrackLabel(
                                                        t,
                                                        audioTracks,
                                                    ),
                                                )
                                                .join("")}
                                        {page === "subtitles" &&
                                            subtitleTracks &&
                                            (subtitleTrack === null
                                                ? "Off"
                                                : subtitleTracks
                                                      .filter(
                                                          (t) =>
                                                              t.index ===
                                                              subtitleTrack,
                                                      )
                                                      .map((t) =>
                                                          subtitleTrackLabel(
                                                              t,
                                                              subtitleTracks,
                                                          ),
                                                      )
                                                      .join(""))}
                                        {page === "files" &&
                                            files &&
                                            files
                                                .filter(
                                                    (f) =>
                                                        f.index ===
                                                        currentFileIndex,
                                                )
                                                .map((f) =>
                                                    f.path.split("/").pop(),
                                                )
                                                .join("")}
                                    </span>
                                    <LuChevronRight
                                        width="16"
                                        height="16"
                                        aria-hidden="true"
                                    />
                                </button>
                            ))
                        ) : (
                            <>
                                <div class="player-controls-menu-head">
                                    <button
                                        type="button"
                                        class="player-controls-button"
                                        aria-label="Back to settings"
                                        onClick$={() => {
                                            menuPage.value = "main";
                                        }}
                                    >
                                        <LuChevronLeft
                                            width="16"
                                            height="16"
                                            aria-hidden="true"
                                        />
                                    </button>
                                    {SETTINGS_PAGE_TITLES[menuPage.value]}
                                </div>

                                {menuPage.value === "speed" &&
                                    PLAYBACK_RATES.map((rate) => (
                                        <MenuOption
                                            key={rate}
                                            checked={rate === playbackRate}
                                            label={formatRate(rate)}
                                            onPick$={() => {
                                                onPlaybackRateChange(rate);
                                                menuPage.value = "main";
                                            }}
                                        />
                                    ))}

                                {menuPage.value === "quality" && quality && (
                                    <>
                                        <MenuOption
                                            checked={quality.chosen === null}
                                            disabled={qualityPending}
                                            label={defaultQualityLabel(quality)}
                                            onPick$={() => {
                                                onPickQuality?.(null);
                                                menuPage.value = "main";
                                            }}
                                        />
                                        {quality.heights.map((height) => (
                                            <MenuOption
                                                key={height}
                                                checked={
                                                    height === quality.chosen
                                                }
                                                disabled={qualityPending}
                                                label={`${height}p`}
                                                onPick$={() => {
                                                    onPickQuality?.(height);
                                                    menuPage.value = "main";
                                                }}
                                            />
                                        ))}
                                        <p class="player-controls-menu-note">
                                            {qualityPending
                                                ? "Switching…"
                                                : "Changing quality restarts the stream where you are."}
                                        </p>
                                    </>
                                )}

                                {menuPage.value === "audio" &&
                                    audioTracks?.map((track) => (
                                        <MenuOption
                                            key={track.index}
                                            checked={track.index === audioTrack}
                                            disabled={audioPending}
                                            label={
                                                audioPending &&
                                                track.index === audioTrack
                                                    ? "Switching…"
                                                    : audioTrackLabel(
                                                          track,
                                                          audioTracks,
                                                      )
                                            }
                                            onPick$={() => {
                                                onPickAudio?.(track.index);
                                                menuPage.value = "main";
                                            }}
                                        />
                                    ))}

                                {menuPage.value === "subtitles" &&
                                    subtitleTracks && (
                                        <>
                                            <MenuOption
                                                checked={subtitleTrack === null}
                                                label="Off"
                                                onPick$={() => {
                                                    onPickSubtitle?.(null);
                                                    menuPage.value = "main";
                                                }}
                                            />
                                            {subtitleTracks.map((track) => (
                                                <MenuOption
                                                    key={track.index}
                                                    checked={
                                                        track.index ===
                                                        subtitleTrack
                                                    }
                                                    disabled={!track.text}
                                                    label={subtitleTrackLabel(
                                                        track,
                                                        subtitleTracks,
                                                    )}
                                                    onPick$={() => {
                                                        onPickSubtitle?.(
                                                            track.index,
                                                        );
                                                        menuPage.value = "main";
                                                    }}
                                                />
                                            ))}
                                        </>
                                    )}

                                {menuPage.value === "files" &&
                                    files?.map((file) => (
                                        <MenuOption
                                            key={file.index}
                                            checked={
                                                file.index === currentFileIndex
                                            }
                                            label={
                                                file.path.split("/").pop() ??
                                                file.path
                                            }
                                            onPick$={() => {
                                                onPickFile?.(file.index);
                                                menuOpen.value = false;
                                            }}
                                        />
                                    ))}
                            </>
                        )}
                    </div>
                )}
            </div>
        );
    },
);
