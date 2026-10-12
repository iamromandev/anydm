import { useEffect, useRef, useState } from "react";
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

function MenuOption({
    checked,
    label,
    disabled,
    onPick,
}: {
    checked: boolean;
    label: string;
    disabled?: boolean;
    onPick: () => void;
}) {
    return (
        <button
            type="button"
            role="menuitemradio"
            aria-checked={checked}
            className="player-controls-menu-option"
            disabled={disabled}
            onClick={onPick}
        >
            <LuCheck
                className="player-controls-menu-tick"
                width="16"
                height="16"
                aria-hidden="true"
            />
            <span className="player-controls-menu-label">{label}</span>
        </button>
    );
}

export function PlayerControls({
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
}: PlayerControlsProps) {
    const trackRef = useRef<HTMLDivElement>(null);
    // Read only by the window listeners and the track's own handlers, so a
    // ref: nothing is drawn differently while dragging.
    const isDragging = useRef(false);
    const [
        hoverRatio,
        setHoverRatio,
    ] = useState<number | null>(null);
    // The settings menu: closed, or open on its list of rows ("main") or
    // on one row's options.
    const [
        menuOpen,
        setMenuOpen,
    ] = useState(false);
    const [
        menuPage,
        setMenuPage,
    ] = useState<SettingsPage | "main">("main");
    // The track captions came back to last time they were switched off.
    const [
        lastSubtitle,
        setLastSubtitle,
    ] = useState<number | null>(null);

    // The window listeners below are set up once; they read the seek
    // target and length as they are when the pointer moves.
    const latest = useRef({ onSeek, duration });
    latest.current = { onSeek, duration };

    // Drag can carry the pointer off the track element itself, so these
    // listen on the window rather than the track — a native <input
    // type=range> gets this for free, but the three-segment fill this
    // scrubber renders needs a plain div instead.
    useEffect(() => {
        const onMove = (event: PointerEvent) => {
            if (!isDragging.current) return;
            const rect = trackRef.current?.getBoundingClientRect();
            if (!rect) return;
            const ratio = seekRatioFromPointerX({
                clientX: event.clientX,
                rectLeft: rect.left,
                rectWidth: rect.width,
            });
            latest.current.onSeek(ratio * latest.current.duration);
        };
        // A press anywhere but the menu and its gear dismisses the menu.
        const onDown = (event: PointerEvent) => {
            const target = event.target as Element | null;
            if (!target?.closest?.(".player-controls-menu, [aria-haspopup]")) {
                setMenuOpen(false);
            }
        };
        const onUp = () => {
            isDragging.current = false;
        };
        window.addEventListener("pointermove", onMove);
        window.addEventListener("pointerdown", onDown);
        window.addEventListener("pointerup", onUp);
        return () => {
            window.removeEventListener("pointermove", onMove);
            window.removeEventListener("pointerdown", onDown);
            window.removeEventListener("pointerup", onUp);
        };
    }, []);

    return (
        <div className="player-controls">
            <div
                ref={trackRef}
                className="player-controls-track"
                onPointerDown={(event) => {
                    isDragging.current = true;
                    const rect = trackRef.current?.getBoundingClientRect();
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
                onPointerMove={(event) => {
                    const rect = (
                        event.currentTarget as HTMLDivElement
                    ).getBoundingClientRect();
                    setHoverRatio(
                        seekRatioFromPointerX({
                            clientX: event.clientX,
                            rectLeft: rect.left,
                            rectWidth: rect.width,
                        }),
                    );
                }}
                onPointerLeave={() => {
                    setHoverRatio(null);
                }}
            >
                <div
                    className="player-controls-fill player-controls-fill--swarm"
                    style={{ width: `${segments.swarmPercent}%` }}
                />
                <div
                    className="player-controls-fill player-controls-fill--buffered"
                    style={{ width: `${segments.bufferedPercent}%` }}
                />
                <div
                    className="player-controls-fill player-controls-fill--played"
                    style={{ width: `${segments.playedPercent}%` }}
                />
                <div
                    className="player-controls-thumb"
                    style={{ left: `${segments.playedPercent}%` }}
                />
                {hoverRatio !== null && (
                    <div
                        className="player-controls-tooltip"
                        style={{ left: `${hoverRatio * 100}%` }}
                    >
                        {formatClockTime(hoverRatio * duration)}
                    </div>
                )}
            </div>

            <div className="player-controls-row">
                {showSteps && (
                    <button
                        type="button"
                        className="player-controls-button"
                        onClick={onPreviousFile}
                        disabled={!hasPreviousFile}
                        aria-label="Previous file"
                    >
                        <LuSkipBack width="18" height="18" aria-hidden="true" />
                    </button>
                )}

                {onSkip && duration > 0 && (
                    <button
                        type="button"
                        className="player-controls-button"
                        onClick={() => onSkip(-1)}
                        aria-label={`Back ${skipSeconds ?? 10} seconds`}
                    >
                        <LuRewind width="18" height="18" aria-hidden="true" />
                    </button>
                )}

                <button
                    type="button"
                    className="player-controls-button"
                    onClick={onTogglePlay}
                    aria-label={paused ? "Play" : "Pause"}
                >
                    {paused ? (
                        <LuPlay width="18" height="18" aria-hidden="true" />
                    ) : (
                        <LuPause width="18" height="18" aria-hidden="true" />
                    )}
                </button>

                {onSkip && duration > 0 && (
                    <button
                        type="button"
                        className="player-controls-button"
                        onClick={() => onSkip(1)}
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
                        className="player-controls-button"
                        onClick={onNextFile}
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
                    className="player-controls-button"
                    onClick={onToggleMute}
                    aria-label={muted || volume === 0 ? "Unmute" : "Mute"}
                >
                    {muted || volume === 0 ? (
                        <LuVolumeX width="18" height="18" aria-hidden="true" />
                    ) : (
                        <LuVolume2 width="18" height="18" aria-hidden="true" />
                    )}
                </button>

                <input
                    type="range"
                    className="player-controls-volume"
                    min="0"
                    max="1"
                    step="0.01"
                    value={volume}
                    onChange={(e) => {
                        onVolumeChange(
                            Number((e.target as HTMLInputElement).value),
                        );
                    }}
                    aria-label="Volume"
                />

                <span className="player-controls-time">
                    {formatClockTime(currentTime)} / {formatClockTime(duration)}
                </span>

                <span className="player-controls-spacer" />

                {subtitleTracks && subtitleTracks.length > 0 && (
                    <button
                        type="button"
                        className="player-controls-button"
                        aria-pressed={(subtitleTrack ?? null) !== null}
                        aria-label="Subtitles"
                        onClick={() => {
                            if (subtitleTrack != null) {
                                setLastSubtitle(subtitleTrack);
                                onPickSubtitle?.(null);
                                return;
                            }
                            const next = subtitleToToggleOn(
                                subtitleTracks,
                                lastSubtitle,
                                null,
                            );
                            if (next !== null) onPickSubtitle?.(next);
                        }}
                    >
                        <LuCaptions width="18" height="18" aria-hidden="true" />
                    </button>
                )}

                <button
                    type="button"
                    className="player-controls-button"
                    aria-label="Settings"
                    aria-haspopup="menu"
                    aria-expanded={menuOpen}
                    onClick={() => {
                        setMenuPage("main");
                        setMenuOpen(!menuOpen);
                    }}
                >
                    <LuSettings width="18" height="18" aria-hidden="true" />
                </button>

                <button
                    type="button"
                    className="player-controls-button"
                    onClick={onToggleFullscreen}
                    aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                >
                    {fullscreen ? (
                        <LuMinimize width="18" height="18" aria-hidden="true" />
                    ) : (
                        <LuMaximize width="18" height="18" aria-hidden="true" />
                    )}
                </button>
            </div>
            {menuOpen && (
                <div
                    className="player-controls-menu"
                    role="menu"
                    aria-label="Settings"
                    onKeyDown={(e) => {
                        // Keys typed in the menu are the menu's: they
                        // must not reach the player's own shortcuts.
                        e.stopPropagation();
                        if (e.key !== "Escape") return;
                        if (menuPage === "main") {
                            setMenuOpen(false);
                        } else {
                            setMenuPage("main");
                        }
                    }}
                >
                    {menuPage === "main" ? (
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
                                className="player-controls-menu-row"
                                onClick={() => {
                                    setMenuPage(page);
                                }}
                            >
                                <span className="player-controls-menu-label">
                                    {SETTINGS_PAGE_TITLES[page]}
                                </span>
                                <span className="player-controls-menu-value">
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
                                                (t) => t.index === audioTrack,
                                            )
                                            .map((t) =>
                                                audioTrackLabel(t, audioTracks),
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
                                            .map((f) => f.path.split("/").pop())
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
                            <div className="player-controls-menu-head">
                                <button
                                    type="button"
                                    className="player-controls-button"
                                    aria-label="Back to settings"
                                    onClick={() => {
                                        setMenuPage("main");
                                    }}
                                >
                                    <LuChevronLeft
                                        width="16"
                                        height="16"
                                        aria-hidden="true"
                                    />
                                </button>
                                {SETTINGS_PAGE_TITLES[menuPage]}
                            </div>

                            {menuPage === "speed" &&
                                PLAYBACK_RATES.map((rate) => (
                                    <MenuOption
                                        key={rate}
                                        checked={rate === playbackRate}
                                        label={formatRate(rate)}
                                        onPick={() => {
                                            onPlaybackRateChange(rate);
                                            setMenuPage("main");
                                        }}
                                    />
                                ))}

                            {menuPage === "quality" && quality && (
                                <>
                                    <MenuOption
                                        checked={quality.chosen === null}
                                        disabled={qualityPending}
                                        label={defaultQualityLabel(quality)}
                                        onPick={() => {
                                            onPickQuality?.(null);
                                            setMenuPage("main");
                                        }}
                                    />
                                    {quality.heights.map((height) => (
                                        <MenuOption
                                            key={height}
                                            checked={height === quality.chosen}
                                            disabled={qualityPending}
                                            label={`${height}p`}
                                            onPick={() => {
                                                onPickQuality?.(height);
                                                setMenuPage("main");
                                            }}
                                        />
                                    ))}
                                    <p className="player-controls-menu-note">
                                        {qualityPending
                                            ? "Switching…"
                                            : "Changing quality restarts the stream where you are."}
                                    </p>
                                </>
                            )}

                            {menuPage === "audio" &&
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
                                        onPick={() => {
                                            onPickAudio?.(track.index);
                                            setMenuPage("main");
                                        }}
                                    />
                                ))}

                            {menuPage === "subtitles" && subtitleTracks && (
                                <>
                                    <MenuOption
                                        checked={subtitleTrack === null}
                                        label="Off"
                                        onPick={() => {
                                            onPickSubtitle?.(null);
                                            setMenuPage("main");
                                        }}
                                    />
                                    {subtitleTracks.map((track) => (
                                        <MenuOption
                                            key={track.index}
                                            checked={
                                                track.index === subtitleTrack
                                            }
                                            disabled={!track.text}
                                            label={subtitleTrackLabel(
                                                track,
                                                subtitleTracks,
                                            )}
                                            onPick={() => {
                                                onPickSubtitle?.(track.index);
                                                setMenuPage("main");
                                            }}
                                        />
                                    ))}
                                </>
                            )}

                            {menuPage === "files" &&
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
                                        onPick={() => {
                                            onPickFile?.(file.index);
                                            setMenuOpen(false);
                                        }}
                                    />
                                ))}
                        </>
                    )}
                </div>
            )}
        </div>
    );
}
