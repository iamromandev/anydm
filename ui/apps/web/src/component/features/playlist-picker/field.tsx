import { useEffect, useRef } from "react";
import { usePatch } from "@/hook/use-patch";
import { useSynced } from "@/hook/use-synced";
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
import type { CategoryItem } from "@/lib/api/category";
import { CategorySelect } from "@/component/shared/category-select";
import {
    filterEntries,
    canTick,
    presetHint,
    selectionSummary,
    setAll,
    tickArrivals,
    toggle,
    toggleRange,
    tickNewest,
    tickUploadedAfter,
    hasDates,
    approxAge,
} from "@/lib/selection";
import { queueFromEntries, type QueueItem } from "@/lib/queue";
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
    /** Play the ticked videos in order, from `start` (part 4). */
    onPlayAll?: (items: QueueItem[], start: number) => void;
    /** The preset the footer starts on. */
    defaultPreset: Preset;
    /** Every category, for the picker in the footer. */
    categories: CategoryItem[];
    /** Where the group saves at first; the footer can change it for this group. */
    addCategory: string;
    /** Add the ticked videos as one group; the shell closes the picker. */
    onAdd: (request: PlaylistRequest) => Promise<void>;
}

export function PlaylistPicker({
    target,
    onClose,
    onDownload,
    onPlay,
    onPlayAll,
    defaultPreset,
    categories,
    addCategory,
    onAdd,
}: PlaylistPickerProps) {
    const [
        listing,
        setListing,
        listingRef,
    ] = useSynced<ListingState>(EMPTY_LISTING);
    const [
        selected,
        setSelected,
        selectedRef,
    ] = useSynced<Set<number>>(new Set());
    const listRef = useRef<HTMLDivElement>(null);
    const [
        store,
        patch,
    ] = usePatch({
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
        category: addCategory,
        adding: false,
        /** "Tick newest [N]" (part 3). */
        newest: 50,
        /** The "Uploaded after" date input's value, as it gives it. */
        since: "",
    });

    /** Open the stream, and add each frame to the list. */
    const { attempt, stopped } = store;
    useEffect(() => {
        if (stopped) {
            setListing((held) => stopListing(held));
            return;
        }
        setListing(EMPTY_LISTING);
        setSelected(new Set());
        const source = new EventSource(entriesUrl(target.url));
        for (const event of FRAMES) {
            source.addEventListener(event, (e) => {
                const data = JSON.parse((e as MessageEvent<string>).data);
                const before = listingRef.current.entries.length;
                setListing(applyFrame(listingRef.current, event, data));
                // Everything arrives ticked except what's held or
                // unavailable (spec: Picker selection).
                setSelected(
                    tickArrivals(
                        selectedRef.current,
                        listingRef.current.entries.slice(before),
                    ),
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
            patch({ stopped: true });
        };
        return () => source.close();
    }, [
        attempt,
        stopped,
        target.url,
    ]);

    const stop = () => patch({ stopped: true });

    const retry = () =>
        patch((held) => ({
            rows: {},
            stopped: false,
            attempt: held.attempt + 1,
        }));

    const download = async (entry: PlaylistEntry) => {
        const mark = (row: RowState) =>
            patch((held) => ({ rows: { ...held.rows, [entry.index]: row } }));
        mark("adding");
        try {
            await onDownload(entry.url);
            mark("added");
        } catch {
            // The page has already shown why, as a toast.
            mark("failed");
        }
    };

    const tick = (entry: PlaylistEntry, range: boolean) => {
        const shown = filterEntries(listing.entries, store.query);
        setSelected(
            range && store.anchor !== null
                ? toggleRange(selected, shown, store.anchor, entry)
                : toggle(selected, entry),
        );
        patch({ anchor: entry.index });
    };

    const setShown = (on: boolean) => {
        const shown = filterEntries(listing.entries, store.query);
        setSelected(setAll(selected, shown, on));
    };

    const pickNewest = () => {
        const n = Math.max(0, Math.floor(store.newest));
        setSelected(tickNewest(listing.entries, n));
    };

    const pickSince = (value: string) => {
        patch({ since: value });
        if (!value) return;
        // The start of that day, where the person is.
        const since = new Date(`${value}T00:00:00`).getTime() / 1000;
        setSelected(tickUploadedAfter(listing.entries, since));
    };

    /** Play all, or a row's Play: the ticked videos in order, from that row. */
    const playFrom = (entry: PlaylistEntry | null) => {
        const items = queueFromEntries(listing.entries, selected);
        if (!onPlayAll || items.length === 0) {
            if (entry) onPlay(entry.url);
            return;
        }
        // A row's Play starts at that row when it's ticked; else it plays alone.
        const at = entry ? items.findIndex((i) => i.key === entry.url) : 0;
        if (entry && at === -1) {
            onPlay(entry.url);
            return;
        }
        onPlayAll(items, Math.max(0, at));
    };

    const add = async () => {
        const request = playlistRequest(
            target,
            store.preset,
            listing.entries,
            selected,
            store.category,
        );
        if (request.entries.length === 0) return;
        patch({ adding: true });
        try {
            // The shell closes the picker once it's added.
            await onAdd(request);
        } catch {
            // The page has already said why, as a toast.
            patch({ adding: false });
        }
    };

    const state = listing;
    const shown = filterEntries(state.entries, store.query);
    const dated = hasDates(state.entries);
    const chosen = state.entries.filter((e) => selected.has(e.index)).length;
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
            className="modal-overlay playlist-picker-overlay"
            onClick={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div
                className="modal-panel playlist-picker"
                role="dialog"
                aria-modal="true"
                aria-labelledby="playlist-picker-title"
            >
                <div className="modal-header">
                    <div className="playlist-picker-heading">
                        <h2 id="playlist-picker-title" className="modal-title">
                            {target.title || "Playlist"}
                        </h2>
                        <span
                            className="playlist-picker-summary"
                            aria-live="polite"
                        >
                            {listingSummary(state)}
                        </span>
                    </div>
                    <button
                        type="button"
                        className="modal-close"
                        onClick={onClose}
                        aria-label="Close"
                    >
                        <LuX width="18" height="18" aria-hidden="true" />
                    </button>
                </div>

                <div className="playlist-picker-tools">
                    <input
                        type="search"
                        className="playlist-picker-filter"
                        placeholder="Filter by title"
                        aria-label="Filter by title"
                        value={store.query}
                        onChange={({ currentTarget: el }) => {
                            patch({ query: el.value, scrollTop: 0 });
                            if (listRef.current) listRef.current.scrollTop = 0;
                        }}
                    />
                    <button
                        type="button"
                        className="playlist-picker-button"
                        onClick={() => setShown(true)}
                    >
                        All
                    </button>
                    <button
                        type="button"
                        className="playlist-picker-button"
                        onClick={() => setShown(false)}
                    >
                        None
                    </button>
                </div>

                {(target.channelTab || dated) && (
                    <div className="playlist-picker-archive">
                        {target.channelTab && (
                            <label className="playlist-picker-newest">
                                <button
                                    type="button"
                                    className="playlist-picker-button"
                                    onClick={pickNewest}
                                >
                                    Tick newest
                                </button>
                                <input
                                    type="number"
                                    min={1}
                                    max={10000}
                                    className="playlist-picker-number"
                                    aria-label="How many of the newest"
                                    value={store.newest}
                                    onChange={({ currentTarget: el }) =>
                                        patch({
                                            newest: Number(el.value) || 0,
                                        })
                                    }
                                />
                            </label>
                        )}
                        {dated && (
                            <label className="playlist-picker-since">
                                <span>Uploaded after</span>
                                <input
                                    type="date"
                                    className="playlist-picker-date"
                                    value={store.since}
                                    onChange={({ currentTarget: el }) =>
                                        pickSince(el.value)
                                    }
                                />
                                <span className="playlist-picker-hint">
                                    Older dates are rough
                                </span>
                            </label>
                        )}
                    </div>
                )}

                <div
                    ref={listRef}
                    className="playlist-picker-list"
                    onScroll={({ currentTarget: el }) =>
                        patch({
                            scrollTop: el.scrollTop,
                            viewport: el.clientHeight || VIEWPORT_PX,
                        })
                    }
                >
                    <div
                        style={{ height: `${win.padTop}px` }}
                        aria-hidden="true"
                    />
                    {visible.map((entry) => {
                        const row = store.rows[entry.index];
                        const label = entryLabel(entry);
                        const meta = [
                            entry.duration ? formatTime(entry.duration) : "",
                            entry.timestamp !== null
                                ? approxAge(entry.timestamp, Date.now())
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
                                className={`playlist-picker-row ${entry.available ? "" : "playlist-picker-row--unavailable"}`}
                                style={{ height: `${ROW_PX}px` }}
                            >
                                <input
                                    type="checkbox"
                                    className="playlist-picker-check"
                                    checked={selected.has(entry.index)}
                                    readOnly
                                    disabled={!canTick(entry)}
                                    onClick={(e) => tick(entry, e.shiftKey)}
                                    aria-label={`Choose ${label}`}
                                />
                                <span className="playlist-picker-index">
                                    {entry.index}
                                </span>
                                {entry.thumbnail ? (
                                    <img
                                        className="playlist-picker-thumb"
                                        src={entry.thumbnail}
                                        alt=""
                                        width={64}
                                        height={36}
                                        loading="lazy"
                                        referrerPolicy="no-referrer"
                                    />
                                ) : (
                                    <span
                                        className="playlist-picker-thumb"
                                        aria-hidden="true"
                                    />
                                )}
                                <div className="playlist-picker-text">
                                    <span className="playlist-picker-title">
                                        {label}
                                    </span>
                                    {meta && (
                                        <span className="playlist-picker-meta">
                                            {meta}
                                        </span>
                                    )}
                                </div>
                                <div className="playlist-picker-actions">
                                    <button
                                        type="button"
                                        className="playlist-picker-action"
                                        disabled={!entry.available}
                                        onClick={() => playFrom(entry)}
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
                                        className="playlist-picker-action"
                                        disabled={
                                            !entry.available ||
                                            row === "adding" ||
                                            row === "added"
                                        }
                                        onClick={() => download(entry)}
                                        aria-label={`Download ${label}`}
                                    >
                                        {row === "adding" ? (
                                            <LuLoader2
                                                width="18"
                                                height="18"
                                                className="hero-input-spin"
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

                <div className="playlist-picker-footer">
                    <div className="playlist-picker-choice">
                        <CategorySelect
                            categories={categories}
                            value={store.category}
                            onChange={(id: string) => patch({ category: id })}
                        />
                        <select
                            className="playlist-picker-preset"
                            aria-label="Quality"
                            value={store.preset}
                            onChange={({ currentTarget: el }) =>
                                patch({ preset: el.value as Preset })
                            }
                        >
                            {PRESET_OPTIONS.map((option) => (
                                <option key={option.value} value={option.value}>
                                    {option.label}
                                </option>
                            ))}
                        </select>
                        <span
                            className="playlist-picker-count"
                            aria-live="polite"
                        >
                            {selectionSummary(state.entries, selected)}
                        </span>
                        {hint && (
                            <span className="playlist-picker-hint">{hint}</span>
                        )}
                    </div>
                    <div className="playlist-picker-buttons">
                        {state.status === "listing" && (
                            <button
                                type="button"
                                className="playlist-picker-button"
                                onClick={stop}
                            >
                                Stop
                            </button>
                        )}
                        {(state.status === "stopped" ||
                            state.status === "failed") && (
                            <button
                                type="button"
                                className="playlist-picker-button"
                                onClick={retry}
                            >
                                Retry
                            </button>
                        )}
                        <button
                            type="button"
                            className="playlist-picker-button"
                            disabled={chosen === 0}
                            onClick={() => playFrom(null)}
                        >
                            Play all
                        </button>
                        <button
                            type="button"
                            className="playlist-picker-button playlist-picker-button--primary"
                            disabled={chosen === 0 || store.adding}
                            onClick={add}
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
}
