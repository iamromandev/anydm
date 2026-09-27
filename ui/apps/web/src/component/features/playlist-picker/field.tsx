import {
    component$,
    $,
    useSignal,
    useStore,
    useVisibleTask$,
} from "@qwik.dev/core";
import {
    LuX,
    LuDownload,
    LuPlay,
    LuLoader2,
    LuCheckCircle,
} from "@/component/core/icons";
import { formatTime } from "@/component/core/utils";
import {
    EMPTY_LISTING,
    applyFrame,
    entriesUrl,
    entryLabel,
    listingSummary,
    stopListing,
    type ListingState,
    type PickerTarget,
    type PlaylistEntry,
} from "@/lib/api/playlist";
import { virtualWindow } from "@/lib/virtual";
import "./field.css";

/** Every row is this tall; it's what lets the list draw only what's in view. */
const ROW_PX = 56;
/** The list's height until it has been scrolled and measured: its CSS maximum. */
const VIEWPORT_PX = 420;
/** The API's frames. Not `error`, which an EventSource fires on its own. */
const FRAMES = [
    "entries",
    "done",
    "failed",
] as const;
const HAVE_LABELS = {
    complete: "Downloaded",
    queued: "Queued",
    failed: "Failed",
} as const;

type RowState = "adding" | "added" | "failed";

export interface PlaylistPickerProps {
    /** Mount with `key={target.url}`: the URL is read once, not tracked. */
    target: PickerTarget;
    onClose: () => void;
    /** Queue one video, the way a pasted link is queued. */
    onDownload: (url: string) => Promise<void>;
    onPlay: (url: string) => void;
}

export const PlaylistPicker = component$<PlaylistPickerProps>(
    ({ target, onClose, onDownload, onPlay }) => {
        const listing = useSignal<ListingState>(EMPTY_LISTING);
        const store = useStore({
            /** Bumped by Retry, which lists again from the start. */
            attempt: 0,
            /** Stop was pressed, or the connection dropped. */
            stopped: false,
            scrollTop: 0,
            viewport: VIEWPORT_PX,
            rows: {} as Record<number, RowState>,
        });

        /**
         * Open the stream, and add each frame to the list.
         *
         * Tracks the store rather than the `target` prop: prop tracking runs a
         * task only once in this Qwik beta, and the shell remounts the picker
         * for another list anyway. `document-ready`, because a modal can open
         * where the default strategy never fires.
         */
        useVisibleTask$(
            ({ track, cleanup }) => {
                track(() => store.attempt);
                const stopped = track(() => store.stopped);
                if (stopped) {
                    listing.value = stopListing(listing.value);
                    return;
                }
                listing.value = EMPTY_LISTING;
                const source = new EventSource(entriesUrl(target.url));
                for (const event of FRAMES) {
                    source.addEventListener(event, (e: Event) => {
                        const data = JSON.parse(
                            (e as MessageEvent<string>).data,
                        );
                        listing.value = applyFrame(listing.value, event, data);
                        // Closed before the server hangs up. Left open, the
                        // EventSource would reconnect and list everything again.
                        if (event !== "entries") source.close();
                    });
                }
                source.onerror = () => {
                    if (source.readyState === EventSource.CLOSED) return;
                    // A dropped connection. Reconnecting would start the list
                    // over under the same rows, so it stops here, with Retry.
                    source.close();
                    store.stopped = true;
                };
                cleanup(() => source.close());
            },
            { strategy: "document-ready" },
        );

        const stop = $(() => {
            store.stopped = true;
        });

        const retry = $(() => {
            store.rows = {};
            store.stopped = false;
            store.attempt += 1;
        });

        const download = $(async (entry: PlaylistEntry) => {
            store.rows = { ...store.rows, [entry.index]: "adding" };
            try {
                await onDownload(entry.url);
                store.rows = { ...store.rows, [entry.index]: "added" };
            } catch {
                // The page has already shown why, as a toast.
                store.rows = { ...store.rows, [entry.index]: "failed" };
            }
        });

        const state = listing.value;
        const win = virtualWindow(
            state.entries.length,
            ROW_PX,
            store.scrollTop,
            store.viewport,
        );
        const visible = state.entries.slice(win.start, win.end);

        return (
            <div
                class="modal-overlay playlist-picker-overlay"
                onClick$={(e: MouseEvent, el: HTMLElement) => {
                    if (e.target === el) onClose();
                }}
            >
                <div
                    class="modal-panel playlist-picker"
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="playlist-picker-title"
                >
                    <div class="modal-header">
                        <div class="playlist-picker-heading">
                            <h2 id="playlist-picker-title" class="modal-title">
                                {target.title || "Playlist"}
                            </h2>
                            <span
                                class="playlist-picker-summary"
                                aria-live="polite"
                            >
                                {listingSummary(state)}
                            </span>
                        </div>
                        <button
                            type="button"
                            class="modal-close"
                            onClick$={onClose}
                            aria-label="Close"
                        >
                            <LuX width="18" height="18" aria-hidden="true" />
                        </button>
                    </div>

                    <div
                        class="playlist-picker-list"
                        onScroll$={(_: Event, el: HTMLElement) => {
                            store.scrollTop = el.scrollTop;
                            store.viewport = el.clientHeight || VIEWPORT_PX;
                        }}
                    >
                        <div
                            style={{ height: `${win.padTop}px` }}
                            aria-hidden="true"
                        />
                        {visible.map((entry) => {
                            const row = store.rows[entry.index];
                            const label = entryLabel(entry);
                            const meta = [
                                entry.duration
                                    ? formatTime(entry.duration)
                                    : "",
                                entry.available ? "" : "Unavailable",
                                entry.have ? HAVE_LABELS[entry.have] : "",
                                row === "added" ? "Added" : "",
                            ]
                                .filter(Boolean)
                                .join(" · ");
                            return (
                                <div
                                    key={entry.index}
                                    class={`playlist-picker-row ${entry.available ? "" : "playlist-picker-row--unavailable"}`}
                                    style={{ height: `${ROW_PX}px` }}
                                >
                                    <span class="playlist-picker-index">
                                        {entry.index}
                                    </span>
                                    {entry.thumbnail ? (
                                        <img
                                            class="playlist-picker-thumb"
                                            src={entry.thumbnail}
                                            alt=""
                                            width={64}
                                            height={36}
                                            loading="lazy"
                                            referrerPolicy="no-referrer"
                                        />
                                    ) : (
                                        <span
                                            class="playlist-picker-thumb"
                                            aria-hidden="true"
                                        />
                                    )}
                                    <div class="playlist-picker-text">
                                        <span class="playlist-picker-title">
                                            {label}
                                        </span>
                                        {meta && (
                                            <span class="playlist-picker-meta">
                                                {meta}
                                            </span>
                                        )}
                                    </div>
                                    <div class="playlist-picker-actions">
                                        <button
                                            type="button"
                                            class="playlist-picker-action"
                                            disabled={!entry.available}
                                            onClick$={() => onPlay(entry.url)}
                                            aria-label={`Play ${label}`}
                                        >
                                            <LuPlay
                                                width="18"
                                                height="18"
                                                aria-hidden="true"
                                            />
                                        </button>
                                        <button
                                            type="button"
                                            class="playlist-picker-action"
                                            disabled={
                                                !entry.available ||
                                                row === "adding" ||
                                                row === "added"
                                            }
                                            onClick$={() => download(entry)}
                                            aria-label={`Download ${label}`}
                                        >
                                            {row === "adding" ? (
                                                <LuLoader2
                                                    width="18"
                                                    height="18"
                                                    class="hero-input-spin"
                                                    aria-hidden="true"
                                                />
                                            ) : row === "added" ? (
                                                <LuCheckCircle
                                                    width="18"
                                                    height="18"
                                                    aria-hidden="true"
                                                />
                                            ) : (
                                                <LuDownload
                                                    width="18"
                                                    height="18"
                                                    aria-hidden="true"
                                                />
                                            )}
                                        </button>
                                    </div>
                                </div>
                            );
                        })}
                        <div
                            style={{ height: `${win.padBottom}px` }}
                            aria-hidden="true"
                        />
                    </div>

                    <div class="playlist-picker-footer">
                        {state.status === "listing" && (
                            <button
                                type="button"
                                class="playlist-picker-button"
                                onClick$={stop}
                            >
                                Stop
                            </button>
                        )}
                        {(state.status === "stopped" ||
                            state.status === "failed") && (
                            <button
                                type="button"
                                class="playlist-picker-button"
                                onClick$={retry}
                            >
                                Retry
                            </button>
                        )}
                    </div>
                </div>
            </div>
        );
    },
);
