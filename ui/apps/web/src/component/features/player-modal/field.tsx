import { component$, $, useSignal, useStore, useVisibleTask$ } from "@qwik.dev/core";
import { LuX } from "@/component/core/icons";
import { apiUrl, startStream, stopStream } from "@/lib/api";
import "./field.css";

export interface PlayerModalProps {
    open: boolean;
    url: string;
    onClose: () => void;
}

export const PlayerModal = component$<PlayerModalProps>(({ open, url, onClose }) => {
    const videoRef = useSignal<HTMLVideoElement>();
    const store = useStore({
        isLoading: false,
        error: "" as string,
        sessionId: "" as string,
        hasVideo: true,
    });

    useVisibleTask$(async ({ track, cleanup }) => {
        const isOpen = track(() => open);
        const sourceUrl = track(() => url);

        if (!isOpen || !sourceUrl) {
            return;
        }

        store.isLoading = true;
        store.error = "";
        store.sessionId = "";

        // Reassigned inside the try block below; declared here so `cleanup`
        // below can reach whichever instance (if any) actually got created.
        let hls: import("hls.js").default | null = null;

        try {
            const session = await startStream(sourceUrl);
            store.sessionId = session.sessionId;
            store.hasVideo = session.hasVideo;

            const video = videoRef.value;
            if (!video) {
                return;
            }

            const playlistUrl = apiUrl(session.playlistUrl);
            if (video.canPlayType("application/vnd.apple.mpegurl")) {
                // Safari plays HLS natively; no library needed.
                video.src = playlistUrl;
            } else {
                const { default: Hls } = await import("hls.js");
                if (Hls.isSupported()) {
                    hls = new Hls();
                    hls.loadSource(playlistUrl);
                    hls.attachMedia(video);
                } else {
                    store.error = "This browser cannot play HLS streams.";
                }
            }
        } catch (err) {
            store.error =
                err instanceof Error ? err.message : "Failed to start the stream";
        } finally {
            store.isLoading = false;
        }

        cleanup(() => {
            hls?.destroy();
            if (store.sessionId) {
                const sessionId = store.sessionId;
                store.sessionId = "";
                stopStream(sessionId).catch(() => {
                    // Best-effort: the idle sweeper cleans this up anyway.
                });
            }
        });
    });

    const handleClose = $(() => {
        onClose();
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
                            controls
                            autoplay
                        />
                    )}
                    {store.isLoading && (
                        <p class="player-modal-loading">Starting stream…</p>
                    )}
                </div>
            </div>
        </div>
    );
});
