import {
    component$,
    $,
    noSerialize,
    type NoSerialize,
    useSignal,
    useStore,
    useVisibleTask$,
} from "@qwik.dev/core";
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
import { formatClockTime, scrubberSegments } from "./scrubber-progress";
import {
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

export const PlayerModal = component$<PlayerModalProps>(
    ({
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
    }) => {
        const videoRef = useSignal<HTMLVideoElement>();
        // Set by the task below once something plays: moves playback to
        // another audio track without starting over (#99). A function, so
        // kept out of what Qwik serializes.
        const switchAudioRef =
            useSignal<NoSerialize<(track: number) => Promise<void>>>();
        // The same, for the quality menu (#103).
        const switchQualityRef =
            useSignal<NoSerialize<(height: number | null) => Promise<void>>>();
        // Cancel / Play now for the up-next countdown (#97), set by the task
        // below whenever a file ends with another one to follow.
        const upNextRef =
            useSignal<
                NoSerialize<{ cancel: () => void; playNow: () => void }>
            >();
        // The file picked in the player's own menu. A signal of this
        // component's rather than a prop, so the task below reliably re-runs
        // on every pick.
        const chosen = useSignal<number | null>(null);
        // Bumped after the page swaps the source for the next queue item.
        // The task tracks this, not the source props: tracking a prop
        // re-runs a task only once in this Qwik beta.
        const step = useSignal(0);
        const panelRef = useSignal<HTMLDivElement>();
        const store = useStore({
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
        });

        /**
         * The files the menu offers: the last answer's own list when it carried
         * one (a torrent's always does), else the list this modal was handed —
         * a task's own files, or the add dialog's resolve (#98).
         */
        const fileList =
            store.sessionFiles.length > 0 ? store.sessionFiles : (files ?? []);

        /** Another queue item: the page swaps the source, then the task re-runs. */
        const goItem = $(async (dir: 1 | -1) => {
            const move = dir === 1 ? onNextItem : onPreviousItem;
            if (!move) return;
            chosen.value = null;
            await move();
            step.value += 1;
        });

        useVisibleTask$(
            async ({ track, cleanup }) => {
                const isOpen = track(() => open);
                const sourceUrl = track(() => url);
                const sourceKind = track(() => kind);
                const sourceTask = track(() => taskId) ?? "";
                const sourceFileIndex = track(() => fileIndex) ?? null;
                const picked = track(() => chosen.value);
                track(() => step.value);
                const sourceFromTorrent = Boolean(track(() => fromTorrent));

                if (!isOpen || (!sourceUrl && !sourceTask)) {
                    return;
                }

                // Which of a torrent's files: picked in the menu, named by
                // whoever opened the player, or its largest. Known here rather
                // than left to the API, so the menu can show it (#98).
                const playIndex =
                    picked ?? sourceFileIndex ?? defaultFileIndex(fileList);
                store.currentFileIndex = playIndex;

                store.isLoading = true;
                store.error = "";
                store.sessionId = "";
                store.streamStatus = "";
                // Cleared with the rest: a start that fails must not leave the
                // previous source's files in the menu.
                store.sessionFiles = [];
                // Known synchronously from the picked kind, not from the
                // server response — for a magnet link, even the initial
                // POST /stream/start can take a while (metadata resolve),
                // and the HUD needs to be up for that whole wait, not just
                // after it resolves.
                // A finished torrent plays from disk: there's no swarm to show.
                // One still downloading streams from it, with the swarm (#95).
                store.isTorrent =
                    sourceFromTorrent ||
                    (!sourceTask && isTorrentKind(sourceKind));
                store.peersConnected = 0;
                store.downloadBps = 0;
                store.progressBytes = 0;
                store.totalBytes = 0;
                store.bufferedPercent = 0;
                store.bufferedRanges = [];
                store.currentTime = 0;
                store.duration = 0;
                store.paused = true;
                store.volume = 1;
                store.muted = false;
                store.playbackRate = 1;
                store.audioTracks = [];
                store.audioTrack = null;
                store.audioPending = false;
                switchAudioRef.value = undefined;
                switchQualityRef.value = undefined;
                store.quality = NO_QUALITY_MENU;
                store.qualityPending = false;
                upNextRef.value = undefined;
                store.upNextIndex = null;
                store.upNextSecondsLeft = null;
                store.upNextItem = false;
                store.upNextTitle = "";
                // Read, not tracked: a change in Settings applies to the
                // next thing played, not to this one.
                const preferred: StreamAudio = {
                    language: audioLanguage || null,
                };
                store.subtitleTracks = [];
                store.subtitleTrack = null;
                store.lastSubtitle = null;
                store.subtitleFileBase = "";
                store.subtitleFileQuery = "";
                const preferredSubtitles = subtitleLanguage || null;
                // The first time a source's tracks are known, the preference
                // picks what shows; later lists (an audio switch) keep it.
                let subtitlesOffered = false;
                const offerSubtitles = (tracks: SubtitleTrack[]) => {
                    store.subtitleTracks = tracks;
                    if (subtitlesOffered || tracks.length === 0) return;
                    subtitlesOffered = true;
                    store.subtitleTrack = pickSubtitleTrack(
                        tracks,
                        preferredSubtitles,
                    );
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
                    store.fullscreen =
                        document.fullscreenElement === panelRef.value;
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
                        const media = await fetchMediaInfo(
                            sourceTask,
                            playIndex,
                        );
                        store.hasVideo = media.hasVideo;
                        store.currentFileIndex = media.fileIndex;
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
                            store.audioTracks = media.audioTracks;
                            store.audioTrack = pickAudioTrack(
                                media.audioTracks,
                            );
                            store.subtitleFileBase = `/download/${sourceTask}/subtitles`;
                            store.subtitleFileQuery =
                                media.fileIndex === null
                                    ? ""
                                    : `?file_index=${media.fileIndex}`;
                            offerSubtitles(media.subtitleTracks);
                            store.quality = fileQualityMenu(media.videoHeight);
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

                    let ready = true;
                    if (session) {
                        store.sessionId = session.sessionId;
                        store.streamStatus = session.status;
                        store.hasVideo = session.hasVideo ?? true;
                        store.audioTracks = session.audioTracks;
                        store.audioTrack = session.audioTrack;
                        store.segmentSeconds = session.segmentSeconds;
                        offerSubtitles(session.subtitleTracks);
                        store.quality = session.quality;
                        // A torrent's answer names its own files; a task's leaves
                        // this empty and the `files` prop stands.
                        store.sessionFiles = session.files;
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
                            events.addEventListener(
                                "stream_status",
                                (event) => {
                                    const data = normalizeStreamStatusEvent(
                                        JSON.parse(
                                            (event as MessageEvent).data,
                                        ),
                                    );
                                    // The session playing, which a switch of
                                    // audio track replaces (#99).
                                    if (data.id !== store.sessionId) {
                                        return;
                                    }
                                    if (data.status === "error") {
                                        store.error =
                                            data.message ||
                                            "Failed to start the stream";
                                        resolved = true;
                                        resolve(false);
                                        return;
                                    }
                                    if (data.peersConnected !== undefined) {
                                        store.peersConnected =
                                            data.peersConnected;
                                    }
                                    if (data.downloadBps !== undefined) {
                                        store.downloadBps = data.downloadBps;
                                    }
                                    if (data.progressBytes !== undefined) {
                                        store.progressBytes =
                                            data.progressBytes;
                                    }
                                    if (data.totalBytes !== undefined) {
                                        store.totalBytes = data.totalBytes;
                                    }
                                    if (data.audioTracks !== undefined) {
                                        store.audioTracks = data.audioTracks;
                                        store.audioTrack =
                                            data.audioTrack ?? null;
                                    }
                                    if (data.subtitleTracks !== undefined) {
                                        offerSubtitles(data.subtitleTracks);
                                    }
                                    if (data.quality !== undefined) {
                                        store.quality = data.quality;
                                    }
                                    if (!resolved && data.status === "ready") {
                                        resolved = true;
                                        resolve(true);
                                    }
                                },
                            );
                        });
                    }

                    if (ready) {
                        const video = videoRef.value;
                        if (video) {
                            const updateBufferedPercent = () => {
                                const ranges: {
                                    start: number;
                                    end: number;
                                }[] = [];
                                for (
                                    let i = 0;
                                    i < video.buffered.length;
                                    i++
                                ) {
                                    ranges.push({
                                        start: video.buffered.start(i),
                                        end: video.buffered.end(i),
                                    });
                                }
                                store.bufferedRanges = ranges;
                                store.currentTime = video.currentTime;
                                store.bufferedPercent = getBufferedPercent({
                                    ranges,
                                    currentTime: video.currentTime,
                                    duration: video.duration,
                                });
                            };
                            const updatePlaybackState = () => {
                                store.paused = video.paused;
                            };
                            const updateVolumeState = () => {
                                store.volume = video.volume;
                                store.muted = video.muted;
                            };
                            const updateRateState = () => {
                                store.playbackRate = video.playbackRate;
                            };
                            const updateDuration = () => {
                                store.duration = video.duration;
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
                            let upNextTimer: ReturnType<
                                typeof setInterval
                            > | null = null;
                            const clearUpNext = () => {
                                if (upNextTimer !== null) {
                                    clearInterval(upNextTimer);
                                    upNextTimer = null;
                                }
                                store.upNextIndex = null;
                                store.upNextSecondsLeft = null;
                                store.upNextItem = false;
                                store.upNextTitle = "";
                            };
                            const goToFile = (index: number) => {
                                clearUpNext();
                                chosen.value = index;
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
                                    fileList,
                                    store.currentFileIndex,
                                    1,
                                );
                                // A torrent's next file, else the queue's next
                                // video (part 4), else nothing: it just ends.
                                if (next === null && !hasNextItem) return;
                                store.upNextIndex = next;
                                store.upNextItem = next === null;
                                store.upNextTitle =
                                    next !== null
                                        ? (fileList
                                              .find((f) => f.index === next)
                                              ?.path.split("/")
                                              .pop() ?? "")
                                        : (nextItemTitle ?? "");
                                store.upNextSecondsLeft = UP_NEXT_SECONDS;
                                upNextTimer = setInterval(() => {
                                    const left =
                                        (store.upNextSecondsLeft ?? 1) - 1;
                                    if (left <= 0) {
                                        if (next !== null) goToFile(next);
                                        else {
                                            clearUpNext();
                                            goItem(1);
                                        }
                                        return;
                                    }
                                    store.upNextSecondsLeft = left;
                                }, 1000);
                            };
                            video.addEventListener("ended", onEnded);
                            cleanup(() => {
                                video.removeEventListener("ended", onEnded);
                                clearUpNext();
                            });
                            upNextRef.value = noSerialize({
                                cancel: clearUpNext,
                                playNow: () => {
                                    if (store.upNextIndex !== null) {
                                        goToFile(store.upNextIndex);
                                    } else if (store.upNextItem) {
                                        clearUpNext();
                                        goItem(1);
                                    }
                                },
                            });

                            // A download resumes where it was left, and says
                            // so, on any device (#96). A link has no place.
                            store.resumedAt = 0;
                            if (sourceTask) {
                                const startAt = resumeAt(
                                    positions,
                                    store.currentFileIndex,
                                );
                                if (startAt > 0) {
                                    video.addEventListener(
                                        "loadedmetadata",
                                        () => {
                                            video.currentTime = startAt;
                                            store.resumedAt = startAt;
                                            // A note, not a prompt: it goes.
                                            setTimeout(() => {
                                                store.resumedAt = 0;
                                            }, 6000);
                                        },
                                        { once: true },
                                    );
                                }

                                const savingFile = store.currentFileIndex;
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
                                    savePosition(
                                        sourceTask,
                                        savingFile,
                                        at,
                                        length,
                                    )
                                        .then((saved) =>
                                            onPositionSaved?.(
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
                                                xhr.setRequestHeader(
                                                    name,
                                                    value,
                                                );
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
                                    store.error =
                                        "This browser cannot play HLS streams.";
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
                                        store.sessionId = taken.sessionId;
                                        store.streamStatus = taken.status;
                                        store.audioTracks = taken.audioTracks;
                                        store.audioTrack = taken.audioTrack;
                                        store.segmentSeconds =
                                            taken.segmentSeconds;
                                        offerSubtitles(taken.subtitleTracks);
                                        store.quality = taken.quality;
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
                                                    video.currentTime =
                                                        resumeAt;
                                                },
                                                { once: true },
                                            );
                                        }
                                        await attach(taken);
                                    } catch (err) {
                                        store.error =
                                            err instanceof Error
                                                ? err.message
                                                : "Failed to start the stream";
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
                                    video.removeEventListener(
                                        "loadeddata",
                                        settle,
                                    );
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
                                switchAudioRef.value = undefined;
                                switchQualityRef.value = undefined;
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
                                const oldId = store.sessionId;
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
                                if (
                                    closed ||
                                    token !== switching ||
                                    !warmed.ok
                                ) {
                                    stopStream(next.sessionId).catch(() => {});
                                    if (!warmed.ok) {
                                        throw new Error(
                                            `status ${warmed.status}`,
                                        );
                                    }
                                    return false;
                                }
                                const resumeFrom = video.currentTime;
                                const wasPaused = video.paused;
                                store.sessionId = next.sessionId;
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
                                store.flash = `Couldn't switch the ${what}`;
                                setTimeout(() => {
                                    store.flash = "";
                                }, 3000);
                            };
                            const busy = () =>
                                store.audioPending || store.qualityPending;

                            // Another audio track (#99). A file the browser
                            // plays itself moves to a session instead.
                            switchAudioRef.value = noSerialize(
                                async (track: number) => {
                                    if (track === store.audioTrack || busy()) {
                                        return;
                                    }
                                    const token = ++switching;
                                    const previous = store.audioTrack;
                                    store.audioTrack = track;
                                    store.audioPending = true;
                                    try {
                                        if (!store.sessionId) {
                                            await fallBackRef.current?.({
                                                track,
                                                quality: store.quality.chosen,
                                            });
                                            return;
                                        }
                                        const next = await switchStreamAudio(
                                            store.sessionId,
                                            track,
                                        );
                                        if (await swapTo(next, token)) {
                                            store.audioTrack = next.audioTrack;
                                        }
                                    } catch {
                                        store.audioTrack = previous;
                                        failed("audio track");
                                    } finally {
                                        if (token === switching) {
                                            store.audioPending = false;
                                        }
                                    }
                                },
                            );

                            // Another quality (#103), the same way. A file
                            // the browser plays itself moves to a session.
                            switchQualityRef.value = noSerialize(
                                async (height: number | null) => {
                                    if (
                                        height === store.quality.chosen ||
                                        busy()
                                    ) {
                                        return;
                                    }
                                    const token = ++switching;
                                    const previous = store.quality;
                                    store.quality = {
                                        ...previous,
                                        chosen: height,
                                    };
                                    store.qualityPending = true;
                                    try {
                                        if (!store.sessionId) {
                                            await fallBackRef.current?.({
                                                language: preferred.language,
                                                quality: height,
                                            });
                                            return;
                                        }
                                        const next = await switchStreamQuality(
                                            store.sessionId,
                                            height,
                                        );
                                        if (await swapTo(next, token)) {
                                            store.quality = next.quality;
                                        }
                                    } catch {
                                        store.quality = previous;
                                        failed("quality");
                                    } finally {
                                        if (token === switching) {
                                            store.qualityPending = false;
                                        }
                                    }
                                },
                            );
                        }
                    }
                } catch (err) {
                    store.error =
                        err instanceof Error
                            ? err.message
                            : "Failed to start the stream";
                } finally {
                    store.isLoading = false;
                }

                cleanup(() => {
                    streamEventsRef.current?.close();
                    hls?.destroy();
                    if (store.sessionId) {
                        const sessionId = store.sessionId;
                        store.sessionId = "";
                        stopStream(sessionId).catch(() => {
                            // Best-effort: the idle sweeper cleans this up anyway.
                        });
                    }
                });
            },
            { strategy: "document-ready" },
        );

        const handleClose = $(() => {
            // The next thing played starts from its own file, not this pick.
            chosen.value = null;
            onClose();
        });

        // Another of a torrent's files: the task above stops this session and
        // starts one on that file (#98).
        const handlePickFile = $((index: number) => {
            chosen.value = index;
        });

        // The Previous/Next buttons and their shortcuts (#97): one step
        // through `files` in the natural order the file menu already uses.
        const handlePreviousFile = $(async () => {
            const index = adjacentFileIndex(
                fileList,
                store.currentFileIndex,
                -1,
            );
            if (index !== null) chosen.value = index;
            else if (hasPreviousItem) await goItem(-1);
        });
        const handleNextFile = $(async () => {
            const index = adjacentFileIndex(
                fileList,
                store.currentFileIndex,
                1,
            );
            if (index !== null) chosen.value = index;
            else if (hasNextItem) await goItem(1);
        });

        // From the up-next overlay (#97): the task above runs the countdown.
        const handleCancelUpNext = $(() => {
            upNextRef.value?.cancel();
        });
        const handlePlayNextNow = $(() => {
            upNextRef.value?.playNow();
        });

        // From the audio menu (#99): the task above does the switch.
        // From the subtitle menu (#100): the loader below follows the pick.
        const handlePickSubtitle = $((index: number | null) => {
            store.lastSubtitle = index ?? store.subtitleTrack;
            store.subtitleTrack = index;
        });

        // Cues for the subtitle track showing (#100), added to one text track
        // on the video at their own times. A session's come by the segment,
        // the one playing and the next, as playback and seeks reach them; a
        // file played as it is gets the track whole. A new pick, or a new
        // session, starts from no cues.
        useVisibleTask$(
            ({ track, cleanup }) => {
                const chosen = track(() => store.subtitleTrack);
                const sessionId = track(() => store.sessionId);
                const fileBase = track(() => store.subtitleFileBase);
                const video = track(() => videoRef.value);
                if (!video) return;

                let cues: TextTrack | undefined;
                for (const existing of Array.from(video.textTracks)) {
                    if (existing.label === "anydm") cues = existing;
                }
                cues ??= video.addTextTrack("subtitles", "anydm");
                // Cues are only reachable while the track isn't disabled.
                cues.mode = "hidden";
                for (const cue of Array.from(cues.cues ?? []))
                    cues.removeCue(cue);
                if (chosen === null || (!sessionId && !fileBase)) return;
                cues.mode = "showing";
                const showing = cues;

                let closed = false;
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
                    if (!response.ok)
                        throw new Error(`status ${response.status}`);
                    return response.text();
                };

                // A subtitle file beside the video comes whole in a session too
                // (#101); only an embedded track is cut by the segment.
                const external = store.subtitleTracks.some(
                    (known) => known.index === chosen && known.external,
                );
                if (!sessionId || external) {
                    const whole = sessionId
                        ? `/stream/${sessionId}/subtitles/${chosen}.vtt`
                        : `${fileBase}/${chosen}.vtt${store.subtitleFileQuery}`;
                    get(whole).then(add, () => {
                        // Best-effort: the film plays on without them.
                    });
                    return;
                }

                const requested = new Set<number>();
                const load = () => {
                    const duration = Number.isFinite(video.duration)
                        ? video.duration
                        : store.duration;
                    for (const index of cueSegmentsAt(
                        video.currentTime,
                        store.segmentSeconds,
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
                // Named, as the task above does: the modal renders nothing while
                // closed, so the default strategy has no element to watch.
            },
            { strategy: "document-ready" },
        );

        // From the quality menu (#103): the task above does the switch.
        const handlePickQuality = $(async (height: number | null) => {
            await switchQualityRef.value?.(height);
        });

        const handlePickAudio = $(async (track: number) => {
            await switchAudioRef.value?.(track);
        });

        // From the resume note: back to the beginning (#96).
        const handleStartOver = $(() => {
            const video = videoRef.value;
            if (video) video.currentTime = 0;
            store.resumedAt = 0;
        });

        const handleTogglePlay = $(() => {
            const video = videoRef.value;
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
        });

        const handleSeek = $((time: number) => {
            const video = videoRef.value;
            if (!video || !Number.isFinite(time)) {
                return;
            }
            video.currentTime = Math.max(0, time);
        });

        const handleVolumeChange = $((volume: number) => {
            const video = videoRef.value;
            if (!video) {
                return;
            }
            video.volume = Math.min(1, Math.max(0, volume));
            if (video.volume > 0) {
                video.muted = false;
            }
        });

        const handleToggleMute = $(() => {
            const video = videoRef.value;
            if (!video) {
                return;
            }
            video.muted = !video.muted;
        });

        const handlePlaybackRateChange = $((rate: number) => {
            const video = videoRef.value;
            if (!video) {
                return;
            }
            video.playbackRate = rate;
        });

        const handleToggleFullscreen = $(() => {
            if (document.fullscreenElement) {
                document.exitFullscreen().catch(() => {
                    // Best-effort: some browsers/iframes reject exit requests.
                });
            } else {
                panelRef.value?.requestFullscreen().catch(() => {
                    // Best-effort: fullscreen isn't available everywhere.
                });
            }
        });

        const flash = $((label: string) => {
            store.flash = label;
            // A token rather than a stored timer id: the point is only to let
            // a later press win, and comparing what is on screen says that
            // more directly than cancelling a handle.
            const token = `${label}:${Date.now()}:${Math.random()}`;
            store.flashToken = token;
            setTimeout(() => {
                if (store.flashToken === token) {
                    store.flash = "";
                }
            }, 700);
        });

        const applyShortcut = $(async (action: PlayerAction) => {
            const video = videoRef.value;

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
                    if (store.subtitleTrack !== null) {
                        store.lastSubtitle = store.subtitleTrack;
                        store.subtitleTrack = null;
                        await flash("Subtitles off");
                        return;
                    }
                    const next = subtitleToToggleOn(
                        store.subtitleTracks,
                        store.lastSubtitle,
                        subtitleLanguage || null,
                    );
                    if (next === null) return;
                    store.subtitleTrack = next;
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
                    store.helpOpen = !store.helpOpen;
                    return;
                case "escape":
                    // The browser leaves fullscreen on Escape by itself, so
                    // doing anything else here would close the player out
                    // from under someone who only wanted the window back.
                    if (document.fullscreenElement) return;
                    if (store.helpOpen) {
                        store.helpOpen = false;
                        return;
                    }
                    await handleClose();
                    return;
            }
        });

        useVisibleTask$(
            ({ track, cleanup }) => {
                // Its own task, separate from the one that starts the stream: the
                // keys should work while a torrent is still finding peers, which
                // is exactly when that other task has not finished.
                if (!track(() => open)) return;

                const onKeyDown = (event: KeyboardEvent) => {
                    const action = resolveShortcut(event);
                    if (!action) return;
                    // Space scrolls and arrows scroll; neither should, with a
                    // player in front of everything.
                    event.preventDefault();
                    applyShortcut(action);
                };

                document.addEventListener("keydown", onKeyDown);
                cleanup(() =>
                    document.removeEventListener("keydown", onKeyDown),
                );
                // Named, like the tasks above: the modal renders nothing while
                // closed, so the default strategy has no element to watch.
            },
            { strategy: "document-ready" },
        );

        if (!open) {
            return null;
        }

        return (
            <div
                class="player-modal-overlay"
                role="dialog"
                aria-modal="true"
                aria-label="Media player"
            >
                <div
                    ref={panelRef}
                    class={`player-modal-panel ${store.hasVideo ? "" : "player-modal-panel--audio-only"}`}
                >
                    <button
                        type="button"
                        class="player-modal-close"
                        onClick$={handleClose}
                        aria-label="Close player"
                    >
                        <LuX width="20" height="20" aria-hidden="true" />
                    </button>

                    <div class="player-modal-body">
                        {store.error ? (
                            <p class="player-modal-error">{store.error}</p>
                        ) : (
                            <video
                                ref={videoRef}
                                class="player-modal-video"
                                autoplay
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
                    <div class="player-flash" aria-live="polite">
                        {store.flash}
                    </div>
                )}

                {store.resumedAt > 0 && (
                    <div class="player-resumed" role="status">
                        <span>
                            Resumed at {formatClockTime(store.resumedAt)}
                        </span>
                        <button
                            type="button"
                            class="player-resumed-restart"
                            onClick$={handleStartOver}
                        >
                            Start over
                        </button>
                    </div>
                )}

                {(store.upNextIndex !== null || store.upNextItem) && (
                    <div class="player-up-next" role="status">
                        <span>
                            Up next in {store.upNextSecondsLeft}s
                            {store.upNextTitle ? `: ${store.upNextTitle}` : ""}
                        </span>
                        <div class="player-up-next-actions">
                            <button
                                type="button"
                                class="player-up-next-cancel"
                                onClick$={handleCancelUpNext}
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                class="player-up-next-play"
                                onClick$={handlePlayNextNow}
                            >
                                Play now
                            </button>
                        </div>
                    </div>
                )}

                {store.helpOpen && (
                    <section
                        class="player-help"
                        aria-label="Keyboard shortcuts"
                    >
                        <h2 class="player-help-title">Keyboard shortcuts</h2>
                        <dl class="player-help-list">
                            {SHORTCUT_HINTS.map((hint) => (
                                <div key={hint.keys} class="player-help-row">
                                    <dt class="player-help-keys">
                                        {hint.keys}
                                    </dt>
                                    <dd class="player-help-description">
                                        {hint.description}
                                    </dd>
                                </div>
                            ))}
                        </dl>
                    </section>
                )}
            </div>
        );
    },
);
