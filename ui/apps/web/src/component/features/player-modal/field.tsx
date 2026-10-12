import { useEffect, useMemo, useRef, useState } from "react";
import { createCleanups } from "@/lib/cleanups";
import { createLive } from "@/lib/live";
import { useLive } from "@/hook/use-live";
import { LuX } from "@/component/core/icons";
import {
    apiUrl,
    authHeaders,
    choosePlayback,
    fetchMediaInfo,
    isTorrentKind,
    NATIVE_LOAD_TIMEOUT_MS,
    nativeFailed,
    normalizeStreamStatusEvent,
    POSITION_SAVE_INTERVAL_MS,
    resumeAt,
    savePosition,
    type PositionView,
    startStream,
    startTaskStream,
    stopStream,
    switchStreamAudio,
    switchStreamQuality,
    type MediaInfo,
    type StreamAudio,
    type StreamSession,
} from "@/lib/api";
import {
    type AudioTrack,
    needsAudioSession,
    pickAudioTrack,
    segmentAt,
} from "@/lib/audio";
import {
    fileQualityMenu,
    NO_QUALITY_MENU,
    type QualityMenu,
} from "@/lib/quality";
import {
    cueKey,
    cueSegmentsAt,
    parseVtt,
    pickSubtitleTrack,
    type SubtitleTrack,
    subtitleToToggleOn,
} from "@/lib/subtitles";
import {
    adjacentFileIndex,
    defaultFileIndex,
    type PlayableFile,
} from "@/lib/media";
import { getBufferedPercent } from "./buffered-progress";
import { PlayerControls } from "./controls";
import { PlayerHud } from "./hud";
import {
    clampSeek,
    formatClockTime,
    scrubberSegments,
} from "./scrubber-progress";
import {
    SEEK_LONG_S,
    SHORTCUT_HINTS,
    resolveShortcut,
    type PlayerAction,
} from "./shortcuts";
import "./field.css";

export interface PlayerModalProps {
    open: boolean;
    /** A link to play, and what kind of link it is. */
    url: string;
    kind: string;
    /**
     * Or a finished download to play instead of a link (#94): its file itself
     * when the browser can play it, else a session reading it from disk.
     */
    taskId?: string;
    /** Which of a torrent's files; its largest media file when absent. */
    fileIndex?: number | null;
    /** A torrent's media files, to switch between in the player (#98). */
    files?: PlayableFile[];
    /**
     * The task is a torrent still downloading: it plays from rqbit's stream,
     * with the swarm showing, never from its partial file (#95).
     */
    fromTorrent?: boolean;
    /** Where the task's files were left, to resume there (#96). */
    positions?: PositionView[];
    /** The player saved where it is. */
    onPositionSaved?: (taskId: string, position: PositionView) => void;
    /** The audio language to open with, from Settings; "" for the file's own (#99). */
    audioLanguage?: string;
    /** The subtitle language to show from the start; "" for none (#100). */
    subtitleLanguage?: string;
    /**
     * A play queue around this item (part 4), owned by the page. When there's
     * no next or previous file, Next and Previous, and the up-next countdown,
     * move through the queue instead.
     */
    hasPreviousItem?: boolean;
    hasNextItem?: boolean;
    nextItemTitle?: string;
    onNextItem?: () => void | Promise<void>;
    onPreviousItem?: () => void | Promise<void>;
    onClose: () => void;
}

/** The line a cue sits on, counted up from the bottom: above the control bar (#100). */
const SUBTITLE_LINE = -4;

/** How long the up-next overlay counts down before moving on (#97). */
const UP_NEXT_SECONDS = 10;

/** Everything the player shows, and its starting values. */
function createPlayerState() {
    return {
        isLoading: false,
        error: "" as string,
        sessionId: "" as string,
        /**
         * The last answer's own file list, which a torrent's answer always
         * carries and a task's never does. Assigned on every start, so a new
         * source cannot inherit the previous one's.
         */
        sessionFiles: [] as PlayableFile[],
        hasVideo: true,
        streamStatus: "" as string,
        isTorrent: false,
        peersConnected: 0,
        downloadBps: 0,
        progressBytes: 0,
        totalBytes: 0,
        bufferedPercent: 0,
        bufferedRanges: [] as { start: number; end: number }[],
        currentTime: 0,
        duration: 0,
        paused: true,
        volume: 1,
        muted: false,
        playbackRate: 1,
        fullscreen: false,
        helpOpen: false,
        // The brief label a key press leaves on screen, so a seek or a
        // volume nudge is visible on a video that looks the same either
        // way. Empty when nothing is showing.
        flash: "",
        flashToken: "",
        // The video is waiting on data: a seek into a part still coming
        // down, or playback that caught up with the download.
        buffering: false,
        // Which of a torrent's files is playing, for the file menu (#98).
        currentFileIndex: null as number | null,
        // Where playback resumed, for the note offering to start over (#96).
        resumedAt: 0,
        // The audio menu (#99): the tracks, the one playing (or on its
        // way), and whether a switch is still getting ready.
        audioTracks: [] as AudioTrack[],
        audioTrack: null as number | null,
        audioPending: false,
        // Subtitles (#100): the tracks, the one showing (`null` is off),
        // the one C brings back, and where a file played as it is gets
        // them whole. A session's come by the segment instead.
        subtitleTracks: [] as SubtitleTrack[],
        subtitleTrack: null as number | null,
        lastSubtitle: null as number | null,
        subtitleFileBase: "",
        subtitleFileQuery: "",
        segmentSeconds: 6,
        // The quality menu (#103), and whether a switch is getting ready.
        quality: NO_QUALITY_MENU as QualityMenu,
        qualityPending: false,
        // The up-next countdown (#97): the file it's counting down to,
        // and how many seconds are left. `null` means no countdown showing.
        upNextIndex: null as number | null,
        upNextSecondsLeft: null as number | null,
        // Counting down to the queue's next video rather than a file (part
        // 4), and what the overlay calls whichever comes next.
        upNextItem: false as boolean,
        upNextTitle: "" as string,
    };
}

export function PlayerModal({
    open,
    url,
    kind,
    taskId,
    fileIndex,
    files,
    fromTorrent,
    positions,
    onPositionSaved,
    audioLanguage,
    subtitleLanguage,
    hasPreviousItem,
    hasNextItem,
    nextItemTitle,
    onNextItem,
    onPreviousItem,
    onClose,
}: PlayerModalProps) {
    const videoRef = useRef<HTMLVideoElement>(null);
    // Set by the effect below once something plays: moves playback to
    // another audio track without starting over (#99).
    const switchAudioRef = useRef<
        ((track: number) => Promise<void>) | undefined
    >(undefined);
    // The same, for the quality menu (#103).
    const switchQualityRef = useRef<
        ((height: number | null) => Promise<void>) | undefined
    >(undefined);
    // Cancel / Play now for the up-next countdown (#97), set by the effect
    // below whenever a file ends with another one to follow.
    const upNextRef = useRef<
        { cancel: () => void; playNow: () => void } | undefined
    >(undefined);
    // The file picked in the player's own menu. State of this component's
    // rather than a prop, so the effect below reliably re-runs on every
    // pick.
    const [
        chosen,
        setChosen,
    ] = useState<number | null>(null);
    // Bumped after the page swaps the source for the next queue item; the
    // effect below depends on it, not on the source props.
    const [
        step,
        setStep,
    ] = useState(0);
    const panelRef = useRef<HTMLDivElement>(null);
    // What the player knows, in a store of its own: the effects below read
    // it as it is when they run, and write it as events arrive.
    const live = useMemo(() => createLive(createPlayerState()), []);
    const store = useLive(live);
    // The props the long-lived effects read, as they are when read.
    const latest = useRef({
        files,
        positions,
        onPositionSaved,
        audioLanguage,
        subtitleLanguage,
        hasNextItem,
        nextItemTitle,
        onNextItem,
        onPreviousItem,
        onClose,
    });
    latest.current = {
        files,
        positions,
        onPositionSaved,
        audioLanguage,
        subtitleLanguage,
        hasNextItem,
        nextItemTitle,
        onNextItem,
        onPreviousItem,
        onClose,
    };

    /**
     * The files the menu offers: the last answer's own list when it carried
     * one (a torrent's always does), else the list this modal was handed —
     * a task's own files, or the add dialog's resolve (#98).
     */
    const fileList =
        store.sessionFiles.length > 0 ? store.sessionFiles : (files ?? []);
    // The same, read when the effects need it rather than when they began.
    const fileListNow = () => {
        const own = live.get().sessionFiles;
        return own.length > 0 ? own : (latest.current.files ?? []);
    };

    /** Another queue item: the page swaps the source, then the task re-runs. */
    const goItem = async (dir: 1 | -1) => {
        const move =
            dir === 1
                ? latest.current.onNextItem
                : latest.current.onPreviousItem;
        if (!move) return;
        setChosen(null);
        await move();
        setStep((n) => n + 1);
    };

    // Starts what is asked for, and ends it when the ask changes or the
    // modal closes. Superseded runs stop themselves at each await.
    useEffect(() => {
        const isOpen = open;
        const sourceUrl = url;
        const sourceKind = kind;
        const sourceTask = taskId ?? "";
        const sourceFileIndex = fileIndex ?? null;
        const picked = chosen;
        const sourceFromTorrent = Boolean(fromTorrent);

        if (!isOpen || (!sourceUrl && !sourceTask)) {
            return;
        }

        // Undone in the order it was set up; one added after this run was
        // superseded runs at once.
        const cleanups = createCleanups();
        const cleanup = cleanups.add;

        void (async () => {
            // Which of a torrent's files: picked in the menu, named by
            // whoever opened the player, or its largest. Known here rather
            // than left to the API, so the menu can show it (#98).
            const playIndex =
                picked ?? sourceFileIndex ?? defaultFileIndex(fileListNow());
            live.patch({ currentFileIndex: playIndex });

            live.patch({ isLoading: true });
            live.patch({ error: "" });
            live.patch({ sessionId: "" });
            live.patch({ streamStatus: "" });
            // Cleared with the rest: a start that fails must not leave the
            // previous source's files in the menu.
            live.patch({ sessionFiles: [] });
            // Known synchronously from the picked kind, not from the
            // server response — for a magnet link, even the initial
            // POST /stream/start can take a while (metadata resolve),
            // and the HUD needs to be up for that whole wait, not just
            // after it resolves.
            // A finished torrent plays from disk: there's no swarm to show.
            // One still downloading streams from it, with the swarm (#95).
            live.patch({
                isTorrent:
                    sourceFromTorrent ||
                    (!sourceTask && isTorrentKind(sourceKind)),
            });
            live.patch({ peersConnected: 0 });
            live.patch({ downloadBps: 0 });
            live.patch({ progressBytes: 0 });
            live.patch({ totalBytes: 0 });
            live.patch({ bufferedPercent: 0 });
            live.patch({ bufferedRanges: [] });
            live.patch({ currentTime: 0 });
            live.patch({ duration: 0 });
            live.patch({ paused: true });
            live.patch({ volume: 1 });
            live.patch({ muted: false });
            live.patch({ playbackRate: 1 });
            live.patch({ audioTracks: [] });
            live.patch({ audioTrack: null });
            live.patch({ audioPending: false });
            switchAudioRef.current = undefined;
            switchQualityRef.current = undefined;
            live.patch({ quality: NO_QUALITY_MENU });
            live.patch({ qualityPending: false });
            upNextRef.current = undefined;
            live.patch({ upNextIndex: null });
            live.patch({ upNextSecondsLeft: null });
            live.patch({ upNextItem: false });
            live.patch({ upNextTitle: "" });
            // Read, not tracked: a change in Settings applies to the
            // next thing played, not to this one.
            const preferred: StreamAudio = {
                language: latest.current.audioLanguage || null,
            };
            live.patch({ subtitleTracks: [] });
            live.patch({ subtitleTrack: null });
            live.patch({ lastSubtitle: null });
            live.patch({ subtitleFileBase: "" });
            live.patch({ subtitleFileQuery: "" });
            const preferredSubtitles = latest.current.subtitleLanguage || null;
            // The first time a source's tracks are known, the preference
            // picks what shows; later lists (an audio switch) keep it.
            let subtitlesOffered = false;
            const offerSubtitles = (tracks: SubtitleTrack[]) => {
                live.patch({ subtitleTracks: tracks });
                if (subtitlesOffered || tracks.length === 0) return;
                subtitlesOffered = true;
                live.patch({
                    subtitleTrack: pickSubtitleTrack(
                        tracks,
                        preferredSubtitles,
                    ),
                });
            };

            // Reassigned inside the try block below; declared here so `cleanup`
            // below can reach whichever instance (if any) actually got created.
            let hls: import("hls.js").default | null = null;
            // An object, not a bare `let`, because it's only ever assigned
            // from inside the Promise executor below — TypeScript narrows a
            // closure-only-assigned `let` back to its null initializer at
            // this scope, which a property access on a ref object avoids.
            const streamEventsRef: { current: EventSource | null } = {
                current: null,
            };
            // The same, for the move from a file the browser plays itself
            // to a session: set only when that's how this run plays.
            const fallBackRef: {
                current: ((audio?: StreamAudio) => Promise<void>) | null;
            } = { current: null };

            const updateFullscreenState = () => {
                live.patch({
                    fullscreen: document.fullscreenElement === panelRef.current,
                });
            };
            document.addEventListener(
                "fullscreenchange",
                updateFullscreenState,
            );
            cleanup(() => {
                document.removeEventListener(
                    "fullscreenchange",
                    updateFullscreenState,
                );
            });

            try {
                // A finished download plays its file itself when the
                // browser says it can; everything else is a session.
                let native: MediaInfo | null = null;
                let session: StreamSession | null = null;
                if (sourceTask && sourceFromTorrent) {
                    // A partial file can't play natively: straight to
                    // rqbit's stream, through the task's own torrent.
                    session = await startTaskStream(
                        sourceTask,
                        playIndex,
                        preferred,
                    );
                } else if (sourceTask) {
                    const media = await fetchMediaInfo(sourceTask, playIndex);
                    if (cleanups.disposed) return;
                    live.patch({ hasVideo: media.hasVideo });
                    live.patch({ currentFileIndex: media.fileIndex });
                    const probe = document.createElement("video");
                    // The browser opens with the track the file marks,
                    // so a preference for another takes a session (#99).
                    if (
                        choosePlayback(media, (type) =>
                            probe.canPlayType(type),
                        ) === "native" &&
                        !needsAudioSession(
                            media.audioTracks,
                            preferred.language ?? null,
                        )
                    ) {
                        native = media;
                        live.patch({ audioTracks: media.audioTracks });
                        live.patch({
                            audioTrack: pickAudioTrack(media.audioTracks),
                        });
                        live.patch({
                            subtitleFileBase: `/download/${sourceTask}/subtitles`,
                        });
                        live.patch({
                            subtitleFileQuery:
                                media.fileIndex === null
                                    ? ""
                                    : `?file_index=${media.fileIndex}`,
                        });
                        offerSubtitles(media.subtitleTracks);
                        live.patch({
                            quality: fileQualityMenu(media.videoHeight),
                        });
                    } else {
                        session = await startTaskStream(
                            sourceTask,
                            media.fileIndex,
                            preferred,
                        );
                    }
                } else {
                    session = await startStream(
                        sourceUrl,
                        sourceKind,
                        playIndex,
                        preferred,
                    );
                }

                // Superseded while the session was being asked for: it was
                // started for a run that no longer exists, so end it.
                if (cleanups.disposed) {
                    if (session) {
                        void stopStream(session.sessionId).catch(() => {});
                    }
                    return;
                }

                let ready = true;
                if (session) {
                    live.patch({ sessionId: session.sessionId });
                    live.patch({ streamStatus: session.status });
                    live.patch({ hasVideo: session.hasVideo ?? true });
                    live.patch({ audioTracks: session.audioTracks });
                    live.patch({ audioTrack: session.audioTrack });
                    live.patch({ segmentSeconds: session.segmentSeconds });
                    offerSubtitles(session.subtitleTracks);
                    live.patch({ quality: session.quality });
                    // A torrent's answer names its own files; a task's leaves
                    // this empty and the `files` prop stands.
                    live.patch({ sessionFiles: session.files });
                    ready = session.status !== "connecting";
                }
                if (session && session.status === "connecting") {
                    // A torrent-backed session comes back before ffprobe
                    // has run — the backend probes in the background and
                    // pushes live swarm status here until it's ready (or
                    // gives up), so the modal never sits on a silent wait.
                    // The connection is left open past that point (closed
                    // only in `cleanup` below): the backend keeps
                    // publishing peer/speed/progress updates through
                    // playback, which is what feeds the HUD's live stats.
                    ready = await new Promise<boolean>((resolve) => {
                        const events = new EventSource(
                            apiUrl("/stream/events", { withKey: true }),
                        );
                        streamEventsRef.current = events;
                        let resolved = false;
                        events.addEventListener("stream_status", (event) => {
                            const data = normalizeStreamStatusEvent(
                                JSON.parse((event as MessageEvent).data),
                            );
                            // The session playing, which a switch of
                            // audio track replaces (#99).
                            if (data.id !== live.get().sessionId) {
                                return;
                            }
                            if (data.status === "error") {
                                live.patch({
                                    error:
                                        data.message ||
                                        "Failed to start the stream",
                                });
                                resolved = true;
                                resolve(false);
                                return;
                            }
                            if (data.peersConnected !== undefined) {
                                live.patch({
                                    peersConnected: data.peersConnected,
                                });
                            }
                            if (data.downloadBps !== undefined) {
                                live.patch({ downloadBps: data.downloadBps });
                            }
                            if (data.progressBytes !== undefined) {
                                live.patch({
                                    progressBytes: data.progressBytes,
                                });
                            }
                            if (data.totalBytes !== undefined) {
                                live.patch({ totalBytes: data.totalBytes });
                            }
                            if (data.audioTracks !== undefined) {
                                live.patch({ audioTracks: data.audioTracks });
                                live.patch({
                                    audioTrack: data.audioTrack ?? null,
                                });
                            }
                            if (data.subtitleTracks !== undefined) {
                                offerSubtitles(data.subtitleTracks);
                            }
                            if (data.quality !== undefined) {
                                live.patch({ quality: data.quality });
                            }
                            if (!resolved && data.status === "ready") {
                                resolved = true;
                                resolve(true);
                            }
                        });
                    });
                }

                if (cleanups.disposed) {
                    streamEventsRef.current?.close();
                    if (session) {
                        void stopStream(session.sessionId).catch(() => {});
                    }
                    return;
                }

                if (ready) {
                    const video = videoRef.current;
                    if (video) {
                        const updateBufferedPercent = () => {
                            const ranges: {
                                start: number;
                                end: number;
                            }[] = [];
                            for (let i = 0; i < video.buffered.length; i++) {
                                ranges.push({
                                    start: video.buffered.start(i),
                                    end: video.buffered.end(i),
                                });
                            }
                            live.patch({ bufferedRanges: ranges });
                            live.patch({ currentTime: video.currentTime });
                            live.patch({
                                bufferedPercent: getBufferedPercent({
                                    ranges,
                                    currentTime: video.currentTime,
                                    duration: video.duration,
                                }),
                            });
                        };
                        const updatePlaybackState = () => {
                            live.patch({ paused: video.paused });
                        };
                        const updateVolumeState = () => {
                            live.patch({ volume: video.volume });
                            live.patch({ muted: video.muted });
                        };
                        const updateRateState = () => {
                            live.patch({ playbackRate: video.playbackRate });
                        };
                        const updateDuration = () => {
                            live.patch({ duration: video.duration });
                        };
                        // A seek past what has downloaded waits for the
                        // swarm, so say so rather than freeze the frame.
                        const startBuffering = () => {
                            live.patch({ buffering: true });
                        };
                        const stopBuffering = () => {
                            live.patch({ buffering: false });
                        };

                        const videoListeners: [
                            string,
                            () => void,
                        ][] = [
                            [
                                "timeupdate",
                                updateBufferedPercent,
                            ],
                            [
                                "progress",
                                updateBufferedPercent,
                            ],
                            [
                                "play",
                                updatePlaybackState,
                            ],
                            [
                                "pause",
                                updatePlaybackState,
                            ],
                            [
                                "volumechange",
                                updateVolumeState,
                            ],
                            [
                                "ratechange",
                                updateRateState,
                            ],
                            [
                                "durationchange",
                                updateDuration,
                            ],
                            [
                                "loadedmetadata",
                                updateDuration,
                            ],
                            [
                                "seeking",
                                startBuffering,
                            ],
                            [
                                "waiting",
                                startBuffering,
                            ],
                            [
                                "seeked",
                                stopBuffering,
                            ],
                            [
                                "playing",
                                stopBuffering,
                            ],
                            [
                                "canplay",
                                stopBuffering,
                            ],
                        ];
                        for (const [
                            type,
                            handler,
                        ] of videoListeners) {
                            video.addEventListener(type, handler);
                        }
                        cleanup(() => {
                            for (const [
                                type,
                                handler,
                            ] of videoListeners) {
                                video.removeEventListener(type, handler);
                            }
                        });

                        // A season pack counts down to its next episode
                        // when one ends (#97); a single file just ends.
                        let upNextTimer: ReturnType<typeof setInterval> | null =
                            null;
                        const clearUpNext = () => {
                            if (upNextTimer !== null) {
                                clearInterval(upNextTimer);
                                upNextTimer = null;
                            }
                            live.patch({ upNextIndex: null });
                            live.patch({ upNextSecondsLeft: null });
                            live.patch({ upNextItem: false });
                            live.patch({ upNextTitle: "" });
                        };
                        const goToFile = (index: number) => {
                            clearUpNext();
                            setChosen(index);
                        };
                        const onEnded = () => {
                            // 'ended' can in principle fire again before a
                            // countdown resolves (seeking back into the
                            // last frame and forward again reaches it a
                            // second time) — clearing first means there's
                            // never more than one timer counting down, so
                            // an earlier one can't outlive Cancel or a
                            // later pick and fire on its own.
                            clearUpNext();
                            const next = adjacentFileIndex(
                                fileListNow(),
                                live.get().currentFileIndex,
                                1,
                            );
                            // A torrent's next file, else the queue's next
                            // video (part 4), else nothing: it just ends.
                            if (next === null && !latest.current.hasNextItem)
                                return;
                            live.patch({ upNextIndex: next });
                            live.patch({ upNextItem: next === null });
                            live.patch({
                                upNextTitle:
                                    next !== null
                                        ? (fileListNow()
                                              .find((f) => f.index === next)
                                              ?.path.split("/")
                                              .pop() ?? "")
                                        : (latest.current.nextItemTitle ?? ""),
                            });
                            live.patch({ upNextSecondsLeft: UP_NEXT_SECONDS });
                            upNextTimer = setInterval(() => {
                                const left =
                                    (live.get().upNextSecondsLeft ?? 1) - 1;
                                if (left <= 0) {
                                    if (next !== null) goToFile(next);
                                    else {
                                        clearUpNext();
                                        goItem(1);
                                    }
                                    return;
                                }
                                live.patch({ upNextSecondsLeft: left });
                            }, 1000);
                        };
                        video.addEventListener("ended", onEnded);
                        cleanup(() => {
                            video.removeEventListener("ended", onEnded);
                            clearUpNext();
                        });
                        upNextRef.current = {
                            cancel: clearUpNext,
                            playNow: () => {
                                const next = live.get().upNextIndex;
                                if (next !== null) {
                                    goToFile(next);
                                } else if (live.get().upNextItem) {
                                    clearUpNext();
                                    goItem(1);
                                }
                            },
                        };

                        // A download resumes where it was left, and says
                        // so, on any device (#96). A link has no place.
                        live.patch({ resumedAt: 0 });
                        if (sourceTask) {
                            const startAt = resumeAt(
                                latest.current.positions,
                                live.get().currentFileIndex,
                            );
                            if (startAt > 0) {
                                video.addEventListener(
                                    "loadedmetadata",
                                    () => {
                                        video.currentTime = startAt;
                                        live.patch({ resumedAt: startAt });
                                        // A note, not a prompt: it goes.
                                        setTimeout(() => {
                                            live.patch({ resumedAt: 0 });
                                        }, 6000);
                                    },
                                    { once: true },
                                );
                            }

                            const savingFile = live.get().currentFileIndex;
                            let lastSaved = -1;
                            const save = () => {
                                const at = video.currentTime;
                                const length = video.duration;
                                if (
                                    !Number.isFinite(length) ||
                                    length <= 0 ||
                                    at <= 0 ||
                                    Math.abs(at - lastSaved) < 1
                                ) {
                                    return;
                                }
                                lastSaved = at;
                                savePosition(sourceTask, savingFile, at, length)
                                    .then((saved) =>
                                        latest.current.onPositionSaved?.(
                                            sourceTask,
                                            saved,
                                        ),
                                    )
                                    .catch(() => {
                                        // Best-effort: the next save tries again.
                                    });
                            };
                            const timer = setInterval(() => {
                                if (!video.paused) save();
                            }, POSITION_SAVE_INTERVAL_MS);
                            video.addEventListener("pause", save);
                            // On close, and on a switch to another file:
                            // this run's file, where it was.
                            cleanup(() => {
                                clearInterval(timer);
                                video.removeEventListener("pause", save);
                                save();
                            });
                        }

                        const attach = async (playing: StreamSession) => {
                            // hls.js first: Chromium's canPlayType("application/vnd.apple.mpegurl")
                            // reports "maybe" even though Chrome has no real native
                            // HLS support, which let this decode a simple mono test
                            // tone by luck and then fail outright on a real movie's
                            // stereo AAC audio. Hls.isSupported() (real MSE
                            // availability) is the reliable signal; native <video src>
                            // is the fallback for the few browsers without it (Safari).
                            const { default: Hls } = await import("hls.js");
                            if (cleanups.disposed) return;
                            if (Hls.isSupported()) {
                                // hls.js makes its own requests, so it can
                                // send the key as a header and keep it out of
                                // every playlist and segment URL.
                                const headers = authHeaders();
                                hls = new Hls({
                                    xhrSetup: (xhr) => {
                                        for (const [
                                            name,
                                            value,
                                        ] of Object.entries(headers)) {
                                            xhr.setRequestHeader(name, value);
                                        }
                                    },
                                });
                                hls.loadSource(apiUrl(playing.playlistUrl));
                                hls.attachMedia(video);
                            } else if (
                                video.canPlayType(
                                    "application/vnd.apple.mpegurl",
                                )
                            ) {
                                // Native playback fetches for itself and cannot
                                // add a header. The API hands a key found here
                                // on to every segment URI in the playlist.
                                video.src = apiUrl(playing.playlistUrl, {
                                    withKey: true,
                                });
                            } else {
                                live.patch({
                                    error: "This browser cannot play HLS streams.",
                                });
                            }
                        };

                        if (native) {
                            const media = native;
                            // If the file won't play after all, a session
                            // from disk takes over where it stopped (#93).
                            // So does another audio track than the file's
                            // own, picked from the menu (#99).
                            let fellBack = false;
                            fallBackRef.current = async (
                                audio: StreamAudio = preferred,
                            ) => {
                                if (fellBack) return;
                                fellBack = true;
                                const resumeAt = video.currentTime;
                                const wasPaused = video.paused;
                                video.removeAttribute("src");
                                video.load();
                                try {
                                    const taken = await startTaskStream(
                                        sourceTask,
                                        media.fileIndex,
                                        audio,
                                    );
                                    live.patch({ sessionId: taken.sessionId });
                                    live.patch({ streamStatus: taken.status });
                                    live.patch({
                                        audioTracks: taken.audioTracks,
                                    });
                                    live.patch({
                                        audioTrack: taken.audioTrack,
                                    });
                                    live.patch({
                                        segmentSeconds: taken.segmentSeconds,
                                    });
                                    offerSubtitles(taken.subtitleTracks);
                                    live.patch({ quality: taken.quality });
                                    if (wasPaused) {
                                        video.addEventListener(
                                            "loadedmetadata",
                                            () => video.pause(),
                                            { once: true },
                                        );
                                    }
                                    if (resumeAt > 0) {
                                        video.addEventListener(
                                            "loadedmetadata",
                                            () => {
                                                video.currentTime = resumeAt;
                                            },
                                            { once: true },
                                        );
                                    }
                                    await attach(taken);
                                } catch (err) {
                                    live.patch({
                                        error:
                                            err instanceof Error
                                                ? err.message
                                                : "Failed to start the stream",
                                    });
                                }
                            };
                            const fallBack = () => fallBackRef.current?.();
                            const onError = () => {
                                if (
                                    nativeFailed({
                                        errored: true,
                                        hasVideo: media.hasVideo,
                                        videoWidth: video.videoWidth,
                                    })
                                ) {
                                    void fallBack();
                                }
                            };
                            const onLoaded = () => {
                                if (
                                    nativeFailed({
                                        errored: false,
                                        hasVideo: media.hasVideo,
                                        videoWidth: video.videoWidth,
                                    })
                                ) {
                                    void fallBack();
                                }
                            };
                            // A file that never loads at all, which WebKit
                            // does with VP9 in WebM, fires neither event.
                            let stallTimer: ReturnType<
                                typeof setTimeout
                            > | null = null;
                            const settle = () => {
                                if (stallTimer !== null) {
                                    clearTimeout(stallTimer);
                                    stallTimer = null;
                                }
                            };
                            const onStalled = () => {
                                stallTimer = null;
                                if (
                                    video.readyState <
                                        HTMLMediaElement.HAVE_CURRENT_DATA &&
                                    nativeFailed({
                                        errored: false,
                                        hasVideo: media.hasVideo,
                                        videoWidth: video.videoWidth,
                                        stalled: true,
                                    })
                                ) {
                                    void fallBack();
                                }
                            };
                            video.addEventListener("error", settle);
                            video.addEventListener("loadeddata", settle);
                            video.addEventListener("error", onError);
                            video.addEventListener("loadeddata", onLoaded);
                            cleanup(() => {
                                settle();
                                video.removeEventListener("error", settle);
                                video.removeEventListener("loadeddata", settle);
                                video.removeEventListener("error", onError);
                                video.removeEventListener(
                                    "loadeddata",
                                    onLoaded,
                                );
                            });
                            // The browser fetches the file itself, with
                            // Range, so the key goes in the URL.
                            video.src = apiUrl(media.fileUrl, {
                                withKey: true,
                            });
                            stallTimer = setTimeout(
                                onStalled,
                                NATIVE_LOAD_TIMEOUT_MS,
                            );
                        } else if (session) {
                            await attach(session);
                        }

                        let switching = 0;
                        let closed = false;
                        cleanup(() => {
                            closed = true;
                            switchAudioRef.current = undefined;
                            switchQualityRef.current = undefined;
                        });
                        // Another audio track (#99). The session playing
                        // carries on while the new one readies the segment
                        // at the current position; then the player swaps
                        // to it there and stops the old one. A file the
                        // browser plays itself moves to a session instead.
                        // Moves playback to `next`, a session another
                        // switch made: readies its segment at the current
                        // position while the old one plays, then swaps to
                        // it there and stops the old one (#99, #103).
                        // `false` when it's no longer wanted.
                        const swapTo = async (
                            next: StreamSession,
                            token: number,
                        ): Promise<boolean> => {
                            const oldId = live.get().sessionId;
                            const headers = authHeaders();
                            const playlist = await fetch(
                                apiUrl(next.playlistUrl),
                                { headers },
                            ).then((r) => r.text());
                            const warmed = await fetch(
                                apiUrl(
                                    `/stream/${next.sessionId}/segment_${segmentAt(playlist, video.currentTime)}.ts`,
                                ),
                                { headers },
                            );
                            if (closed || token !== switching || !warmed.ok) {
                                stopStream(next.sessionId).catch(() => {});
                                if (!warmed.ok) {
                                    throw new Error(`status ${warmed.status}`);
                                }
                                return false;
                            }
                            const resumeFrom = video.currentTime;
                            const wasPaused = video.paused;
                            live.patch({ sessionId: next.sessionId });
                            hls?.destroy();
                            hls = null;
                            video.removeAttribute("src");
                            video.load();
                            video.addEventListener(
                                "loadedmetadata",
                                () => {
                                    video.currentTime = resumeFrom;
                                    if (wasPaused) video.pause();
                                },
                                { once: true },
                            );
                            await attach(next);
                            stopStream(oldId).catch(() => {
                                // Best-effort: the idle sweeper cleans this up anyway.
                            });
                            return true;
                        };
                        const failed = (what: string) => {
                            live.patch({
                                flash: `Couldn't switch the ${what}`,
                            });
                            setTimeout(() => {
                                live.patch({ flash: "" });
                            }, 3000);
                        };
                        const busy = () =>
                            live.get().audioPending ||
                            live.get().qualityPending;

                        // Another audio track (#99). A file the browser
                        // plays itself moves to a session instead.
                        switchAudioRef.current = async (track: number) => {
                            if (track === live.get().audioTrack || busy()) {
                                return;
                            }
                            const token = ++switching;
                            const previous = live.get().audioTrack;
                            live.patch({ audioTrack: track });
                            live.patch({ audioPending: true });
                            try {
                                if (!live.get().sessionId) {
                                    await fallBackRef.current?.({
                                        track,
                                        quality: live.get().quality.chosen,
                                    });
                                    return;
                                }
                                const next = await switchStreamAudio(
                                    live.get().sessionId,
                                    track,
                                );
                                if (await swapTo(next, token)) {
                                    live.patch({ audioTrack: next.audioTrack });
                                }
                            } catch {
                                live.patch({ audioTrack: previous });
                                failed("audio track");
                            } finally {
                                if (token === switching) {
                                    live.patch({ audioPending: false });
                                }
                            }
                        };

                        // Another quality (#103), the same way. A file
                        // the browser plays itself moves to a session.
                        switchQualityRef.current = async (
                            height: number | null,
                        ) => {
                            if (
                                height === live.get().quality.chosen ||
                                busy()
                            ) {
                                return;
                            }
                            const token = ++switching;
                            const previous = live.get().quality;
                            live.patch({
                                quality: {
                                    ...previous,
                                    chosen: height,
                                },
                            });
                            live.patch({ qualityPending: true });
                            try {
                                if (!live.get().sessionId) {
                                    await fallBackRef.current?.({
                                        language: preferred.language,
                                        quality: height,
                                    });
                                    return;
                                }
                                const next = await switchStreamQuality(
                                    live.get().sessionId,
                                    height,
                                );
                                if (await swapTo(next, token)) {
                                    live.patch({ quality: next.quality });
                                }
                            } catch {
                                live.patch({ quality: previous });
                                failed("quality");
                            } finally {
                                if (token === switching) {
                                    live.patch({ qualityPending: false });
                                }
                            }
                        };
                    }
                }
            } catch (err) {
                live.patch({
                    error:
                        err instanceof Error
                            ? err.message
                            : "Failed to start the stream",
                });
            } finally {
                // A superseded run leaves the flag to the run that replaced it.
                if (!cleanups.disposed) live.patch({ isLoading: false });
            }

            cleanup(() => {
                streamEventsRef.current?.close();
                hls?.destroy();
                if (live.get().sessionId) {
                    const sessionId = live.get().sessionId;
                    live.patch({ sessionId: "" });
                    stopStream(sessionId).catch(() => {
                        // Best-effort: the idle sweeper cleans this up anyway.
                    });
                }
            });
        })();

        return () => cleanups.dispose();
    }, [
        open,
        url,
        kind,
        taskId,
        fileIndex,
        chosen,
        step,
        fromTorrent,
    ]);

    const handleClose = () => {
        // The next thing played starts from its own file, not this pick.
        setChosen(null);
        onClose();
    };

    // Another of a torrent's files: the task above stops this session and
    // starts one on that file (#98).
    const handlePickFile = (index: number) => {
        setChosen(index);
    };

    // The Previous/Next buttons and their shortcuts (#97): one step
    // through `files` in the natural order the file menu already uses.
    const handlePreviousFile = async () => {
        const index = adjacentFileIndex(
            fileListNow(),
            live.get().currentFileIndex,
            -1,
        );
        if (index !== null) setChosen(index);
        else if (hasPreviousItem) await goItem(-1);
    };
    const handleNextFile = async () => {
        const index = adjacentFileIndex(
            fileListNow(),
            live.get().currentFileIndex,
            1,
        );
        if (index !== null) setChosen(index);
        else if (latest.current.hasNextItem) await goItem(1);
    };

    // From the up-next overlay (#97): the task above runs the countdown.
    const handleCancelUpNext = () => {
        upNextRef.current?.cancel();
    };
    const handlePlayNextNow = () => {
        upNextRef.current?.playNow();
    };

    // From the audio menu (#99): the task above does the switch.
    // From the subtitle menu (#100): the loader below follows the pick.
    const handlePickSubtitle = (index: number | null) => {
        live.patch({ lastSubtitle: index ?? live.get().subtitleTrack });
        live.patch({ subtitleTrack: index });
    };

    // Cues for the subtitle track showing (#100), added to one text track
    // on the video at their own times. A session's come by the segment,
    // the one playing and the next, as playback and seeks reach them; a
    // file played as it is gets the track whole. A new pick, or a new
    // session, starts from no cues.
    const subtitleTrack = store.subtitleTrack;
    const subtitleSession = store.sessionId;
    const subtitleFileBase = store.subtitleFileBase;
    useEffect(() => {
        const chosen = subtitleTrack;
        const sessionId = subtitleSession;
        const fileBase = subtitleFileBase;
        const video = videoRef.current;
        if (!video) return;
        let closed = false;
        const stopFns: Array<() => void> = [];
        const cleanup = (fn: () => void) => {
            stopFns.push(fn);
        };

        let cues: TextTrack | undefined;
        for (const existing of Array.from(video.textTracks)) {
            if (existing.label === "anydm") cues = existing;
        }
        cues ??= video.addTextTrack("subtitles", "anydm");
        // Cues are only reachable while the track isn't disabled.
        cues.mode = "hidden";
        for (const cue of Array.from(cues.cues ?? [])) cues.removeCue(cue);
        if (chosen === null || (!sessionId && !fileBase)) return;
        cues.mode = "showing";
        const showing = cues;

        cleanup(() => {
            closed = true;
        });
        const seen = new Set<string>();
        const add = (text: string) => {
            if (closed) return;
            for (const cue of parseVtt(text)) {
                const key = cueKey(cue);
                if (seen.has(key)) continue;
                seen.add(key);
                const shown = new VTTCue(cue.start, cue.end, cue.text);
                // Lines count up from the bottom: clear of the control
                // bar, which always sits over the foot of the picture.
                shown.line = SUBTITLE_LINE;
                showing.addCue(shown);
            }
        };
        const get = async (path: string) => {
            const response = await fetch(apiUrl(path), {
                headers: authHeaders(),
            });
            if (!response.ok) throw new Error(`status ${response.status}`);
            return response.text();
        };

        // A subtitle file beside the video comes whole in a session too
        // (#101); only an embedded track is cut by the segment.
        const external = live
            .get()
            .subtitleTracks.some(
                (known) => known.index === chosen && known.external,
            );
        if (!sessionId || external) {
            const whole = sessionId
                ? `/stream/${sessionId}/subtitles/${chosen}.vtt`
                : `${fileBase}/${chosen}.vtt${live.get().subtitleFileQuery}`;
            get(whole).then(add, () => {
                // Best-effort: the film plays on without them.
            });
            return () => {
                closed = true;
            };
        }

        const requested = new Set<number>();
        const load = () => {
            const duration = Number.isFinite(video.duration)
                ? video.duration
                : live.get().duration;
            for (const index of cueSegmentsAt(
                video.currentTime,
                live.get().segmentSeconds,
                duration,
            )) {
                if (requested.has(index)) continue;
                requested.add(index);
                get(
                    `/stream/${sessionId}/subtitles/${chosen}/segment_${index}.vtt`,
                ).then(add, () => {
                    // Asked again a little later, not on every tick.
                    setTimeout(() => requested.delete(index), 3000);
                });
            }
        };
        video.addEventListener("timeupdate", load);
        video.addEventListener("seeking", load);
        load();
        cleanup(() => {
            video.removeEventListener("timeupdate", load);
            video.removeEventListener("seeking", load);
        });
        return () => {
            for (const fn of stopFns.splice(0)) fn();
        };
    }, [
        open,
        subtitleTrack,
        subtitleSession,
        subtitleFileBase,
    ]);

    // From the quality menu (#103): the task above does the switch.
    const handlePickQuality = async (height: number | null) => {
        await switchQualityRef.current?.(height);
    };

    const handlePickAudio = async (track: number) => {
        await switchAudioRef.current?.(track);
    };

    // From the resume note: back to the beginning (#96).
    const handleStartOver = () => {
        const video = videoRef.current;
        if (video) video.currentTime = 0;
        live.patch({ resumedAt: 0 });
    };

    const handleTogglePlay = () => {
        const video = videoRef.current;
        if (!video) {
            return;
        }
        if (video.paused) {
            video.play().catch(() => {
                // Best-effort: autoplay-restriction policies can reject this.
            });
        } else {
            video.pause();
        }
    };

    const handleSeek = (time: number) => {
        const video = videoRef.current;
        if (!video) {
            return;
        }
        const target = clampSeek(time, video.duration);
        if (target === null) {
            return;
        }
        video.currentTime = target;
    };

    const handleVolumeChange = (volume: number) => {
        const video = videoRef.current;
        if (!video) {
            return;
        }
        video.volume = Math.min(1, Math.max(0, volume));
        if (video.volume > 0) {
            video.muted = false;
        }
    };

    const handleToggleMute = () => {
        const video = videoRef.current;
        if (!video) {
            return;
        }
        video.muted = !video.muted;
    };

    const handlePlaybackRateChange = (rate: number) => {
        const video = videoRef.current;
        if (!video) {
            return;
        }
        video.playbackRate = rate;
    };

    const handleToggleFullscreen = () => {
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {
                // Best-effort: some browsers/iframes reject exit requests.
            });
        } else {
            panelRef.current?.requestFullscreen().catch(() => {
                // Best-effort: fullscreen isn't available everywhere.
            });
        }
    };

    const flash = (label: string) => {
        live.patch({ flash: label });
        // A token rather than a stored timer id: the point is only to let
        // a later press win, and comparing what is on screen says that
        // more directly than cancelling a handle.
        const token = `${label}:${Date.now()}:${Math.random()}`;
        live.patch({ flashToken: token });
        setTimeout(() => {
            if (live.get().flashToken === token) {
                live.patch({ flash: "" });
            }
        }, 700);
    };

    const applyShortcut = async (action: PlayerAction) => {
        const video = videoRef.current;

        switch (action.type) {
            case "togglePlay":
                // Read before acting: the label describes what the press
                // is about to do, not what was true a moment ago.
                await flash(video?.paused ? "Play" : "Pause");
                await handleTogglePlay();
                return;
            case "seekBy": {
                if (!video) return;
                const sign = action.seconds > 0 ? "+" : "−";
                await flash(`${sign}${Math.abs(action.seconds)}s`);
                await handleSeek(video.currentTime + action.seconds);
                return;
            }
            case "volumeBy": {
                if (!video) return;
                const next = Math.min(
                    1,
                    Math.max(0, video.volume + action.delta),
                );
                await flash(`Volume ${Math.round(next * 100)}%`);
                await handleVolumeChange(next);
                return;
            }
            case "toggleMute":
                await flash(video?.muted ? "Unmuted" : "Muted");
                await handleToggleMute();
                return;
            case "toggleFullscreen":
                await handleToggleFullscreen();
                return;
            case "toggleSubtitles": {
                if (live.get().subtitleTrack !== null) {
                    live.patch({ lastSubtitle: live.get().subtitleTrack });
                    live.patch({ subtitleTrack: null });
                    await flash("Subtitles off");
                    return;
                }
                const next = subtitleToToggleOn(
                    live.get().subtitleTracks,
                    live.get().lastSubtitle,
                    latest.current.subtitleLanguage || null,
                );
                if (next === null) return;
                live.patch({ subtitleTrack: next });
                await flash("Subtitles on");
                return;
            }
            case "nextFile":
                await handleNextFile();
                return;
            case "previousFile":
                await handlePreviousFile();
                return;
            case "toggleHelp":
                live.patch({ helpOpen: !live.get().helpOpen });
                return;
            case "escape":
                // The browser leaves fullscreen on Escape by itself, so
                // doing anything else here would close the player out
                // from under someone who only wanted the window back.
                if (document.fullscreenElement) return;
                if (live.get().helpOpen) {
                    live.patch({ helpOpen: false });
                    return;
                }
                await handleClose();
                return;
        }
    };

    // The skip buttons take the same road as J and L, so a click leaves
    // the same "+10s" label.
    const handleSkip = async (direction: 1 | -1) => {
        await applyShortcut({
            type: "seekBy",
            seconds: direction * SEEK_LONG_S,
        });
    };

    // The latest handler, so the listener below is set up once per open.
    const shortcutRef = useRef(applyShortcut);
    shortcutRef.current = applyShortcut;

    useEffect(() => {
        // Its own effect, separate from the one that starts the stream:
        // the keys should work while a torrent is still finding peers,
        // which is exactly when that other effect has not finished.
        if (!open) return;

        const onKeyDown = (event: KeyboardEvent) => {
            const action = resolveShortcut(event);
            if (!action) return;
            // Space scrolls and arrows scroll; neither should, with a
            // player in front of everything.
            event.preventDefault();
            void shortcutRef.current(action);
        };

        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [
        open,
    ]);

    if (!open) {
        return null;
    }

    return (
        <div
            className="player-modal-overlay"
            role="dialog"
            aria-modal="true"
            aria-label="Media player"
        >
            <div
                ref={panelRef}
                className={`player-modal-panel ${store.hasVideo ? "" : "player-modal-panel--audio-only"}`}
            >
                <button
                    type="button"
                    className="player-modal-close"
                    onClick={handleClose}
                    aria-label="Close player"
                >
                    <LuX width="20" height="20" aria-hidden="true" />
                </button>

                <div className="player-modal-body">
                    {store.error ? (
                        <p className="player-modal-error">{store.error}</p>
                    ) : (
                        <video
                            ref={videoRef}
                            className="player-modal-video"
                            autoPlay
                        />
                    )}
                    {store.isTorrent && !store.error && (
                        <PlayerHud
                            connecting={store.isLoading}
                            peersConnected={store.peersConnected}
                            downloadBps={store.downloadBps}
                            progressBytes={store.progressBytes}
                            totalBytes={store.totalBytes}
                            bufferedPercent={store.bufferedPercent}
                        />
                    )}
                    {!store.error && (
                        <PlayerControls
                            paused={store.paused}
                            currentTime={store.currentTime}
                            duration={store.duration}
                            volume={store.volume}
                            muted={store.muted}
                            playbackRate={store.playbackRate}
                            fullscreen={store.fullscreen}
                            segments={scrubberSegments({
                                isTorrent: store.isTorrent,
                                progressBytes: store.progressBytes,
                                totalBytes: store.totalBytes,
                                bufferedRanges: store.bufferedRanges,
                                currentTime: store.currentTime,
                                duration: store.duration,
                            })}
                            onTogglePlay={handleTogglePlay}
                            onSeek={handleSeek}
                            onSkip={handleSkip}
                            skipSeconds={SEEK_LONG_S}
                            onVolumeChange={handleVolumeChange}
                            onToggleMute={handleToggleMute}
                            onPlaybackRateChange={handlePlaybackRateChange}
                            onToggleFullscreen={handleToggleFullscreen}
                            files={fileList}
                            currentFileIndex={store.currentFileIndex}
                            onPickFile={handlePickFile}
                            onPreviousFile={handlePreviousFile}
                            onNextFile={handleNextFile}
                            showSteps={
                                (fileList?.length ?? 0) > 1 ||
                                Boolean(hasNextItem || hasPreviousItem)
                            }
                            hasPreviousFile={
                                adjacentFileIndex(
                                    fileList,
                                    store.currentFileIndex,
                                    -1,
                                ) !== null || Boolean(hasPreviousItem)
                            }
                            hasNextFile={
                                adjacentFileIndex(
                                    fileList,
                                    store.currentFileIndex,
                                    1,
                                ) !== null || Boolean(hasNextItem)
                            }
                            audioTracks={store.audioTracks}
                            audioTrack={store.audioTrack}
                            audioPending={store.audioPending}
                            onPickAudio={handlePickAudio}
                            subtitleTracks={store.subtitleTracks}
                            subtitleTrack={store.subtitleTrack}
                            onPickSubtitle={handlePickSubtitle}
                            quality={store.quality}
                            qualityPending={store.qualityPending}
                            onPickQuality={handlePickQuality}
                        />
                    )}
                </div>
            </div>

            {/* Outside the panel on purpose: an audio-only stream leaves
                    the panel only as tall as its controls, which clipped both
                    of these to a couple of lines. */}
            {store.flash && (
                <div className="player-flash" aria-live="polite">
                    {store.flash}
                </div>
            )}

            {store.buffering && !store.error && (
                <div className="player-buffering" role="status">
                    Buffering…
                </div>
            )}

            {store.resumedAt > 0 && (
                <div className="player-resumed" role="status">
                    <span>Resumed at {formatClockTime(store.resumedAt)}</span>
                    <button
                        type="button"
                        className="player-resumed-restart"
                        onClick={handleStartOver}
                    >
                        Start over
                    </button>
                </div>
            )}

            {(store.upNextIndex !== null || store.upNextItem) && (
                <div className="player-up-next" role="status">
                    <span>
                        Up next in {store.upNextSecondsLeft}s
                        {store.upNextTitle ? `: ${store.upNextTitle}` : ""}
                    </span>
                    <div className="player-up-next-actions">
                        <button
                            type="button"
                            className="player-up-next-cancel"
                            onClick={handleCancelUpNext}
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            className="player-up-next-play"
                            onClick={handlePlayNextNow}
                        >
                            Play now
                        </button>
                    </div>
                </div>
            )}

            {store.helpOpen && (
                <section
                    className="player-help"
                    aria-label="Keyboard shortcuts"
                >
                    <h2 className="player-help-title">Keyboard shortcuts</h2>
                    <dl className="player-help-list">
                        {SHORTCUT_HINTS.map((hint) => (
                            <div key={hint.keys} className="player-help-row">
                                <dt className="player-help-keys">
                                    {hint.keys}
                                </dt>
                                <dd className="player-help-description">
                                    {hint.description}
                                </dd>
                            </div>
                        ))}
                    </dl>
                </section>
            )}
        </div>
    );
}
