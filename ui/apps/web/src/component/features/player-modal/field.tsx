import {
    component$,
    $,
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
    startStream,
    startTaskStream,
    stopStream,
    type MediaInfo,
    type StreamSession,
} from "@/lib/api";
import { defaultFileIndex, type PlayableFile } from "@/lib/media";
import { getBufferedPercent } from "./buffered-progress";
import { PlayerControls } from "./controls";
import { PlayerHud } from "./hud";
import { scrubberSegments } from "./scrubber-progress";
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
    onClose: () => void;
}

export const PlayerModal = component$<PlayerModalProps>(
    ({ open, url, kind, taskId, fileIndex, files, fromTorrent, onClose }) => {
        const videoRef = useSignal<HTMLVideoElement>();
        // The file picked in the player's own menu. A signal of this
        // component's rather than a prop, so the task below reliably re-runs
        // on every pick.
        const chosen = useSignal<number | null>(null);
        const panelRef = useSignal<HTMLDivElement>();
        const store = useStore({
            isLoading: false,
            error: "" as string,
            sessionId: "" as string,
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
        });

        useVisibleTask$(
            async ({ track, cleanup }) => {
                const isOpen = track(() => open);
                const sourceUrl = track(() => url);
                const sourceKind = track(() => kind);
                const sourceTask = track(() => taskId) ?? "";
                const sourceFileIndex = track(() => fileIndex) ?? null;
                const picked = track(() => chosen.value);
                const sourceFromTorrent = Boolean(track(() => fromTorrent));

                if (!isOpen || (!sourceUrl && !sourceTask)) {
                    return;
                }

                // Which of a torrent's files: picked in the menu, named by
                // whoever opened the player, or its largest. Known here rather
                // than left to the API, so the menu can show it (#98).
                const playIndex =
                    picked ?? sourceFileIndex ?? defaultFileIndex(files ?? []);
                store.currentFileIndex = playIndex;

                store.isLoading = true;
                store.error = "";
                store.sessionId = "";
                store.streamStatus = "";
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
                        session = await startTaskStream(sourceTask, playIndex);
                    } else if (sourceTask) {
                        const media = await fetchMediaInfo(
                            sourceTask,
                            playIndex,
                        );
                        store.hasVideo = media.hasVideo;
                        store.currentFileIndex = media.fileIndex;
                        const probe = document.createElement("video");
                        if (
                            choosePlayback(media, (type) =>
                                probe.canPlayType(type),
                            ) === "native"
                        ) {
                            native = media;
                        } else {
                            session = await startTaskStream(
                                sourceTask,
                                media.fileIndex,
                            );
                        }
                    } else {
                        session = await startStream(
                            sourceUrl,
                            sourceKind,
                            playIndex,
                        );
                    }

                    let ready = true;
                    if (session) {
                        store.sessionId = session.sessionId;
                        store.streamStatus = session.status;
                        store.hasVideo = session.hasVideo ?? true;
                        ready = session.status !== "connecting";
                    }
                    if (session && session.status === "connecting") {
                        const connecting = session;
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
                                    if (data.id !== connecting.sessionId) {
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
                                let fellBack = false;
                                const fallBack = async () => {
                                    if (fellBack) return;
                                    fellBack = true;
                                    const resumeAt = video.currentTime;
                                    video.removeAttribute("src");
                                    video.load();
                                    try {
                                        const taken = await startTaskStream(
                                            sourceTask,
                                            media.fileIndex,
                                        );
                                        store.sessionId = taken.sessionId;
                                        store.streamStatus = taken.status;
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

        useVisibleTask$(({ track, cleanup }) => {
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
            cleanup(() => document.removeEventListener("keydown", onKeyDown));
        });

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
                                files={files}
                                currentFileIndex={store.currentFileIndex}
                                onPickFile={handlePickFile}
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
