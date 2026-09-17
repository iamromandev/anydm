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
    isTorrentKind,
    normalizeStreamStatusEvent,
    startStream,
    stopStream,
} from "@/lib/api";
import { getBufferedPercent } from "./buffered-progress";
import { PlayerControls } from "./controls";
import { PlayerHud } from "./hud";
import { scrubberSegments } from "./scrubber-progress";
import "./field.css";

export interface PlayerModalProps {
    open: boolean;
    url: string;
    kind: string;
    onClose: () => void;
}

export const PlayerModal = component$<PlayerModalProps>(
    ({ open, url, kind, onClose }) => {
        const videoRef = useSignal<HTMLVideoElement>();
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
        });

        useVisibleTask$(
            async ({ track, cleanup }) => {
                const isOpen = track(() => open);
                const sourceUrl = track(() => url);
                const sourceKind = track(() => kind);

                if (!isOpen || !sourceUrl) {
                    return;
                }

                store.isLoading = true;
                store.error = "";
                store.sessionId = "";
                store.streamStatus = "";
                // Known synchronously from the picked kind, not from the
                // server response — for a magnet link, even the initial
                // POST /stream/start can take a while (metadata resolve),
                // and the HUD needs to be up for that whole wait, not just
                // after it resolves.
                store.isTorrent = isTorrentKind(sourceKind);
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
                    const session = await startStream(sourceUrl, sourceKind);
                    store.sessionId = session.sessionId;
                    store.streamStatus = session.status;
                    store.hasVideo = session.hasVideo ?? true;

                    let ready = session.status !== "connecting";
                    if (session.status === "connecting") {
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
                                apiUrl("/stream/events"),
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
                                    if (data.id !== session.sessionId) {
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

                            const videoListeners: [string, () => void][] = [
                                ["timeupdate", updateBufferedPercent],
                                ["progress", updateBufferedPercent],
                                ["play", updatePlaybackState],
                                ["pause", updatePlaybackState],
                                ["volumechange", updateVolumeState],
                                ["ratechange", updateRateState],
                                ["durationchange", updateDuration],
                                ["loadedmetadata", updateDuration],
                            ];
                            for (const [type, handler] of videoListeners) {
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
                            // hls.js first: Chromium's canPlayType("application/vnd.apple.mpegurl")
                            // reports "maybe" even though Chrome has no real native
                            // HLS support, which let this decode a simple mono test
                            // tone by luck and then fail outright on a real movie's
                            // stereo AAC audio. Hls.isSupported() (real MSE
                            // availability) is the reliable signal; native <video src>
                            // is the fallback for the few browsers without it (Safari).
                            const playlistUrl = apiUrl(session.playlistUrl);
                            const { default: Hls } = await import("hls.js");
                            if (Hls.isSupported()) {
                                hls = new Hls();
                                hls.loadSource(playlistUrl);
                                hls.attachMedia(video);
                            } else if (
                                video.canPlayType(
                                    "application/vnd.apple.mpegurl",
                                )
                            ) {
                                video.src = playlistUrl;
                            } else {
                                store.error =
                                    "This browser cannot play HLS streams.";
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
            onClose();
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
                                onPlaybackRateChange={
                                    handlePlaybackRateChange
                                }
                                onToggleFullscreen={handleToggleFullscreen}
                            />
                        )}
                    </div>
                </div>
            </div>
        );
    },
);
