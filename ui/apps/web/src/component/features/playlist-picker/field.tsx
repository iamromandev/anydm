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
    playlistRequest,
    stopListing,
    type ListingState,
    type PickerTarget,
    type PlaylistEntry,
    type PlaylistRequest,
} from "@/lib/api/playlist";
import { PRESET_OPTIONS, type Preset } from "@/lib/prefs";
import {
    filterEntries,
    isSelectable,
    presetHint,
    selectionSummary,
    setAll,
    tickArrivals,
    toggle,
    toggleRange,
} from "@/lib/selection";
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
    /** The preset the footer starts on. */
    defaultPreset: Preset;
    /** Add the ticked videos as one group; the shell closes the picker. */
    onAdd: (request: PlaylistRequest) => Promise<void>;
}

export const PlaylistPicker = component$<PlaylistPickerProps>(
    ({ target, onClose, onDownload, onPlay, defaultPreset, onAdd }) => {
        const listing = useSignal<ListingState>(EMPTY_LISTING);
        const selected = useSignal<Set<number>>(new Set());
        const listRef = useSignal<HTMLElement>();
        const store = useStore({
            /** Bumped by Retry, which lists again from the start. */
            attempt: 0,
            /** Stop was pressed, or the connection dropped. */
            stopped: false,
            scrollTop: 0,
            viewport: VIEWPORT_PX,
            rows: {} as Record<number, RowState>,
            /** The last row clicked, for a shift-click range. */
            anchor: null as number | null,
            query: "",
            preset: defaultPreset as Preset,
            adding: false,
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
                selected.value = new Set();
                const source = new EventSource(entriesUrl(target.url));
                for (const event of FRAMES) {
                    source.addEventListener(event, (e: Event) => {
                        const data = JSON.parse(
                            (e as MessageEvent<string>).data,
                        );
                        const before = listing.value.entries.length;
                        listing.value = applyFrame(listing.value, event, data);
                        // Everything arrives ticked except what's held or
                        // unavailable (spec: Picker selection).
                        selected.value = tickArrivals(
                            selected.value,
                            listing.value.entries.slice(before),
                        );
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

        const tick = $((entry: PlaylistEntry, range: boolean) => {
            const shown = filterEntries(listing.value.entries, store.query);
            selected.value =
                range && store.anchor !== null
                    ? toggleRange(selected.value, shown, store.anchor, entry)
                    : toggle(selected.value, entry);
            store.anchor = entry.index;
        });

        const setShown = $((on: boolean) => {
            const shown = filterEntries(listing.value.entries, store.query);
            selected.value = setAll(selected.value, shown, on);
        });

        const add = $(async () => {
            const request = playlistRequest(
                target,
                store.preset,
                listing.value.entries,
                selected.value,
            );
            if (request.entries.length === 0) return;
            store.adding = true;
            try {
                // The shell closes the picker once it's added.
                await onAdd(request);
            } catch {
                // The page has already said why, as a toast.
                store.adding = false;
            }
        });

        const state = listing.value;
        const shown = filterEntries(state.entries, store.query);
        const chosen = state.entries.filter((e) =>
            selected.value.has(e.index),
        ).length;
        const hint = presetHint(store.preset);
        const win = virtualWindow(
            shown.length,
            ROW_PX,
            store.scrollTop,
            store.viewport,
        );
        const visible = shown.slice(win.start, win.end);

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

                    <div class="playlist-picker-tools">
                        <input
                            type="search"
                            class="playlist-picker-filter"
                            placeholder="Filter by title"
                            aria-label="Filter by title"
                            value={store.query}
                            onInput$={(_, el) => {
                                store.query = el.value;
                                store.scrollTop = 0;
                                if (listRef.value) listRef.value.scrollTop = 0;
                            }}
                        />
                        <button
                            type="button"
                            class="playlist-picker-button"
                            onClick$={() => setShown(true)}
                        >
                            All
                        </button>
                        <button
                            type="button"
                            class="playlist-picker-button"
                            onClick$={() => setShown(false)}
                        >
                            None
                        </button>
                    </div>

                    <div
                        ref={listRef}
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
                                    <input
                                        type="checkbox"
                                        class="playlist-picker-check"
                                        checked={selected.value.has(
                                            entry.index,
                                        )}
                                        disabled={!isSelectable(entry)}
                                        onClick$={(e: MouseEvent) =>
                                            tick(entry, e.shiftKey)
                                        }
                                        aria-label={`Choose ${label}`}
                                    />
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
                        <div class="playlist-picker-choice">
                            <select
                                class="playlist-picker-preset"
                                aria-label="Quality"
                                value={store.preset}
                                onChange$={(_, el) => {
                                    store.preset = el.value as Preset;
                                }}
                            >
                                {PRESET_OPTIONS.map((option) => (
                                    <option
                                        key={option.value}
                                        value={option.value}
                                    >
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                            <span
                                class="playlist-picker-count"
                                aria-live="polite"
                            >
                                {selectionSummary(
                                    state.entries,
                                    selected.value,
                                )}
                            </span>
                            {hint && (
                                <span class="playlist-picker-hint">{hint}</span>
                            )}
                        </div>
                        <div class="playlist-picker-buttons">
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
                            <button
                                type="button"
                                class="playlist-picker-button playlist-picker-button--primary"
                                disabled={chosen === 0 || store.adding}
                                onClick$={add}
                            >
                                {store.adding
                                    ? "Adding…"
                                    : `Add ${chosen.toLocaleString("en-US")} ${chosen === 1 ? "video" : "videos"}`}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        );
    },
);
