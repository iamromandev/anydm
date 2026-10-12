import { useEffect, useRef, type CSSProperties } from "react";
import {
    LuCopy,
    LuLoader2,
    LuPlay,
    LuPlus,
    LuRefreshCw,
    LuSearch,
    LuAlertTriangle,
} from "@/component/core/icons";
import {
    fetchFoundTorrent,
    searchIndexers,
    type AddInitial,
    type FoundTorrent,
} from "@/lib/api/search";
import { errorMessage } from "@/lib/toast";
import {
    CATEGORY_OPTIONS,
    canRun,
    defaultSort,
    emptyText,
    errorChip,
    formatAge,
    formatSize,
    modeFor,
    playKind,
    playSource,
    rowKey,
    SORT_OPTIONS,
    sortFound,
    sortOptionId,
    sourceLabel,
    statusLine,
    SWARM_TICKS,
    swarmTicks,
    seederTone,
} from "./present";
import type { Patch } from "@/hook/use-patch";
import { failureOf, type SearchState } from "./state";
import { VideoPanel } from "./video-panel";
import "./field.css";

export interface SearchViewProps {
    state: SearchState;
    /** Merge into the owner's state; a function sees the latest. */
    patch: (changes: Patch<SearchState>) => void;
    /** Whether the API has torrent sources, and whether it can search YouTube. */
    torrents: boolean;
    youtube: boolean;
    onAdd: (initial: AddInitial) => void;
    /** Add on a video: the same path as pasting its link into the add box. */
    onAddVideo: (url: string) => Promise<void> | void;
    /** Open the player on a result's copy; the player starts the stream itself. */
    onPlay: (value: string, kind: string) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

export function SearchView({
    state,
    patch,
    torrents,
    youtube,
    onAdd,
    onAddVideo,
    onPlay,
    onNotify,
}: SearchViewProps) {
    // A view can't show a tab that isn't there.
    const source = !youtube ? "torrents" : !torrents ? "youtube" : state.source;

    /**
     * Play a result: a magnet is already the copy, so the player opens on it
     * at once. A link has to be fetched from its indexer first, which is the
     * one wait this row has; the player's own connecting state then covers
     * the metadata wait.
     */
    const play = async (result: FoundTorrent) => {
        const source = playSource(result);
        if (!source) return;
        let value = source.value;
        let kind: string = source.kind;
        if (source.kind === "link") {
            if (state.fetching) return;
            patch({ fetching: rowKey(result) });
            try {
                const fetched = await fetchFoundTorrent(value);
                value = fetched.value;
                kind = playKind(fetched);
            } catch (error) {
                onNotify(
                    "error",
                    `Couldn't fetch the torrent from ${result.copyFrom || "the source"}: ${errorMessage(error)}`,
                );
                return;
            } finally {
                patch({ fetching: "" });
            }
        }
        onPlay(value, kind);
    };

    /** Ask for `text` ("" browses the latest); `fresh` skips the API's browse cache. */
    const run = async (
        text: string,
        fresh: boolean,
        category = state.category,
    ) => {
        if (!canRun(text, state.busy)) return;
        const mode = modeFor(text) as "browse" | "search";
        const q = text.trim();
        patch({ busy: true, failure: null });
        try {
            const answer = await searchIndexers(q, category, { fresh });
            patch((held) => ({
                answer,
                searched: q,
                sort: held.mode !== mode ? defaultSort(mode) : held.sort,
                mode,
            }));
        } catch (error) {
            patch({
                answer: null,
                searched: q,
                mode,
                failure: failureOf(error),
            });
        } finally {
            patch({ busy: false });
        }
    };

    // Opening the view lists the latest releases. Tasks can re-run, so the guard
    // is the state itself: an answer, a failure or a request in flight means done.
    // Effects run twice under StrictMode; the ref keeps that to one request.
    const started = useRef(false);
    useEffect(() => {
        if (started.current) return;
        started.current = true;
        if (!torrents) return;
        if (state.answer || state.failure || state.busy) return;
        void run(state.q, false);
    }, []);

    const add = async (result: FoundTorrent) => {
        if (result.magnet) {
            onAdd({ type: "magnet", value: result.magnet });
            return;
        }
        if (!result.link || state.fetching) return;
        patch({ fetching: result.link });
        try {
            onAdd(await fetchFoundTorrent(result.link));
        } catch (error) {
            onNotify(
                "error",
                `Couldn't fetch the torrent from ${result.indexers[0] ?? "the indexer"}: ${errorMessage(error)}`,
            );
        } finally {
            patch({ fetching: "" });
        }
    };

    const copyMagnet = async (magnet: string) => {
        try {
            await navigator.clipboard.writeText(magnet);
            onNotify("info", "Magnet copied");
        } catch {
            onNotify("error", "Couldn't copy the magnet");
        }
    };

    const rows = state.answer
        ? sortFound(state.answer.results, state.sort)
        : [];
    const now = Date.now();

    return (
        <section className="search-view" aria-label="Search indexers">
            {torrents && youtube && (
                <div
                    className="search-view-switch"
                    role="tablist"
                    aria-label="Search in"
                >
                    <button
                        type="button"
                        role="tab"
                        aria-selected={source === "torrents"}
                        onClick={() => patch({ source: "torrents" })}
                    >
                        Torrents
                    </button>
                    <button
                        type="button"
                        role="tab"
                        aria-selected={source === "youtube"}
                        onClick={() => patch({ source: "youtube" })}
                    >
                        YouTube
                    </button>
                </div>
            )}
            {source === "youtube" && (
                <VideoPanel
                    state={state}
                    patch={patch}
                    onAddVideo={onAddVideo}
                />
            )}
            {source === "torrents" && (
                <>
                    <form
                        className="search-view-bar"
                        onSubmit={(event) => {
                            event.preventDefault();
                            void run(state.q, false);
                        }}
                    >
                        <input
                            className="search-view-query"
                            type="search"
                            placeholder="Search"
                            aria-label="Search"
                            value={state.q}
                            onChange={({ currentTarget: el }) =>
                                patch({ q: el.value })
                            }
                        />
                        <button
                            className="search-view-go"
                            type="submit"
                            // Disabled only while a request runs: a browser won't submit on Enter
                            // while the submit button is disabled, and on a first visit the input's
                            // handler is still loading, so the button can lag the typing.
                            // run() ignores exactly one character itself.
                            disabled={state.busy}
                        >
                            {state.busy ? (
                                <span className="search-view-spin">
                                    <LuLoader2
                                        width="16"
                                        height="16"
                                        aria-hidden="true"
                                    />
                                </span>
                            ) : (
                                <LuSearch
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            )}
                            <span>Search</span>
                        </button>
                    </form>

                    <div
                        className="search-view-chips"
                        role="tablist"
                        aria-label="Category"
                    >
                        {CATEGORY_OPTIONS.map((c) => (
                            <button
                                key={c.id}
                                type="button"
                                role="tab"
                                className="search-view-tab"
                                aria-selected={state.category === c.id}
                                disabled={state.busy}
                                onClick={() => {
                                    patch({ category: c.id });
                                    void run(state.q, false, c.id);
                                }}
                            >
                                {c.label}
                            </button>
                        ))}
                    </div>

                    {state.answer && (
                        <div className="search-view-status" role="status">
                            <span>
                                {statusLine(
                                    state.answer.results.length,
                                    state.answer.asked.length -
                                        state.answer.errors.length,
                                    state.answer.tookMs,
                                    state.mode,
                                )}
                            </span>
                            {state.answer.errors.map((e) => (
                                <span
                                    key={e.indexer}
                                    className="search-view-chip"
                                    title={e.message}
                                >
                                    <LuAlertTriangle
                                        width="12"
                                        height="12"
                                        aria-hidden="true"
                                    />
                                    {errorChip(e)}
                                </span>
                            ))}
                            {rows.length > 1 && (
                                <label className="search-view-sort">
                                    Sort by
                                    <select
                                        value={sortOptionId(state.sort)}
                                        onChange={({ currentTarget: el }) => {
                                            const pick = SORT_OPTIONS.find(
                                                (o) => o.id === el.value,
                                            );
                                            if (pick)
                                                patch({ sort: pick.sort });
                                        }}
                                    >
                                        {SORT_OPTIONS.map((o) => (
                                            <option key={o.id} value={o.id}>
                                                {o.label}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                            {state.mode === "browse" && (
                                <button
                                    type="button"
                                    className="search-view-refresh"
                                    disabled={state.busy}
                                    onClick={() => run(state.searched, true)}
                                >
                                    <LuRefreshCw
                                        width="12"
                                        height="12"
                                        aria-hidden="true"
                                    />
                                    Refresh
                                </button>
                            )}
                        </div>
                    )}

                    {!state.answer && !state.failure && (
                        <p className="search-view-empty">
                            {state.busy ? (
                                <span className="search-view-spin">
                                    <LuLoader2
                                        width="20"
                                        height="20"
                                        aria-hidden="true"
                                    />
                                </span>
                            ) : (
                                "Search"
                            )}
                        </p>
                    )}

                    {state.failure && (
                        <div className="search-view-failure" role="alert">
                            <p>{state.failure.message}</p>
                            <ul>
                                {state.failure.causes.map((c) => (
                                    <li key={c.indexer}>{errorChip(c)}</li>
                                ))}
                            </ul>
                            <button
                                type="button"
                                className="search-view-retry"
                                onClick={() => run(state.searched, false)}
                            >
                                Try again
                            </button>
                        </div>
                    )}

                    {state.answer && rows.length === 0 && (
                        <p className="search-view-empty">
                            {emptyText(state.mode, state.searched)}
                        </p>
                    )}

                    {rows.length > 0 && (
                        <ul className="search-view-list">
                            {rows.map((r, i) => {
                                const ticks = swarmTicks(r.seeders);
                                const fetching =
                                    state.fetching !== "" &&
                                    state.fetching === rowKey(r);
                                return (
                                    <li
                                        key={rowKey(r)}
                                        className="search-view-row"
                                    >
                                        <span
                                            className={`search-view-swarm search-view-swarm--${seederTone(r.seeders)}`}
                                            role="img"
                                            aria-label={`${r.seeders ?? "Unknown"} seeders`}
                                            style={
                                                {
                                                    "--i": String(
                                                        Math.min(i, 12),
                                                    ),
                                                } as CSSProperties
                                            }
                                        >
                                            {Array.from(
                                                { length: SWARM_TICKS },
                                                (_, t) => (
                                                    <i
                                                        key={t}
                                                        className={
                                                            t < ticks
                                                                ? "on"
                                                                : undefined
                                                        }
                                                        style={
                                                            {
                                                                "--t": String(
                                                                    t,
                                                                ),
                                                            } as CSSProperties
                                                        }
                                                    />
                                                ),
                                            )}
                                        </span>
                                        <div className="search-view-body">
                                            <p className="search-view-title">
                                                {r.title}
                                            </p>
                                            <p className="search-view-facts">
                                                {r.sizeBytes !== null && (
                                                    <span>
                                                        {formatSize(
                                                            r.sizeBytes,
                                                        )}
                                                    </span>
                                                )}
                                                {r.seeders !== null && (
                                                    <span
                                                        className={`search-view-seeders--${seederTone(r.seeders)}`}
                                                    >
                                                        {r.seeders} seeders
                                                    </span>
                                                )}
                                                {r.leechers !== null && (
                                                    <span>
                                                        {r.leechers} leechers
                                                    </span>
                                                )}
                                                {r.published !== null && (
                                                    <span>
                                                        {formatAge(
                                                            r.published,
                                                            now,
                                                        )}
                                                    </span>
                                                )}
                                                <span
                                                    className="search-view-source"
                                                    title={r.indexers.join(
                                                        ", ",
                                                    )}
                                                >
                                                    {sourceLabel(r)}
                                                </span>
                                            </p>
                                        </div>
                                        <div className="search-view-actions">
                                            {r.magnet && (
                                                <button
                                                    type="button"
                                                    className="search-view-copy"
                                                    aria-label={`Copy magnet for ${r.title}`}
                                                    title="Copy magnet"
                                                    onClick={() =>
                                                        copyMagnet(
                                                            r.magnet as string,
                                                        )
                                                    }
                                                >
                                                    <LuCopy
                                                        width="14"
                                                        height="14"
                                                        aria-hidden="true"
                                                    />
                                                </button>
                                            )}
                                            <button
                                                type="button"
                                                className="search-view-play"
                                                aria-label={`Play ${r.title}`}
                                                disabled={fetching}
                                                onClick={() => play(r)}
                                            >
                                                <LuPlay
                                                    width="14"
                                                    height="14"
                                                    aria-hidden="true"
                                                />
                                                {fetching
                                                    ? "Fetching…"
                                                    : "Play"}
                                            </button>
                                            <button
                                                type="button"
                                                className="search-view-add"
                                                aria-label={`Add ${r.title}`}
                                                disabled={fetching}
                                                onClick={() => add(r)}
                                            >
                                                {fetching ? (
                                                    <>
                                                        <span className="search-view-spin">
                                                            <LuLoader2
                                                                width="14"
                                                                height="14"
                                                                aria-hidden="true"
                                                            />
                                                        </span>
                                                        Fetching…
                                                    </>
                                                ) : (
                                                    <>
                                                        <LuPlus
                                                            width="14"
                                                            height="14"
                                                            aria-hidden="true"
                                                        />
                                                        Add
                                                    </>
                                                )}
                                            </button>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    )}
                </>
            )}
        </section>
    );
}
