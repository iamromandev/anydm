import { component$, $, useVisibleTask$ } from "@qwik.dev/core";
import {
    LuCopy,
    LuLoader2,
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
    indexerLabel,
    modeFor,
    SORT_OPTIONS,
    sortFound,
    sortOptionId,
    statusLine,
    SWARM_TICKS,
    swarmTicks,
    seederTone,
} from "./present";
import { failureOf, type SearchState } from "./state";
import "./field.css";

export interface SearchViewProps {
    state: SearchState;
    onAdd: (initial: AddInitial) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

export const SearchView = component$<SearchViewProps>(
    ({ state, onAdd, onNotify }) => {
        // Declared before its callers: a $() captures only what is above it.
        /** Ask for `text` ("" browses the latest); `fresh` skips the API's browse cache. */
        const run = $(async (text: string, fresh: boolean) => {
            if (!canRun(text, state.busy)) return;
            const mode = modeFor(text) as "browse" | "search";
            const q = text.trim();
            state.busy = true;
            state.failure = null;
            try {
                const answer = await searchIndexers(q, state.category, {
                    fresh,
                });
                state.answer = answer;
                state.searched = q;
                if (state.mode !== mode) state.sort = defaultSort(mode);
                state.mode = mode;
            } catch (error) {
                state.answer = null;
                state.searched = q;
                state.mode = mode;
                state.failure = failureOf(error);
            } finally {
                state.busy = false;
            }
        });

        // Opening the view lists the latest releases. Tasks can re-run, so the guard
        // is the state itself: an answer, a failure or a request in flight means done.
        useVisibleTask$(
            () => {
                if (state.answer || state.failure || state.busy) return;
                run(state.q, false);
            },
            { strategy: "document-ready" },
        );

        const add = $(async (result: FoundTorrent) => {
            if (result.magnet) {
                onAdd({ type: "magnet", value: result.magnet });
                return;
            }
            if (!result.link || state.fetching) return;
            state.fetching = result.link;
            try {
                onAdd(await fetchFoundTorrent(result.link));
            } catch (error) {
                onNotify(
                    "error",
                    `Couldn't fetch the torrent from ${result.indexers[0] ?? "the indexer"}: ${errorMessage(error)}`,
                );
            } finally {
                state.fetching = "";
            }
        });

        const copyMagnet = $(async (magnet: string) => {
            try {
                await navigator.clipboard.writeText(magnet);
                onNotify("info", "Magnet copied");
            } catch {
                onNotify("error", "Couldn't copy the magnet");
            }
        });

        const rows = state.answer
            ? sortFound(state.answer.results, state.sort)
            : [];
        const now = Date.now();

        return (
            <section class="search-view" aria-label="Search indexers">
                <form
                    class="search-view-bar"
                    preventdefault:submit
                    onSubmit$={() => run(state.q, false)}
                >
                    <input
                        class="search-view-query"
                        type="search"
                        placeholder="Search"
                        aria-label="Search"
                        value={state.q}
                        onInput$={(_, el) => {
                            state.q = el.value;
                        }}
                    />
                    <button
                        class="search-view-go"
                        type="submit"
                        // Disabled only while a request runs: a browser won't submit on Enter
                        // while the submit button is disabled, and on a first visit the input's
                        // handler is still loading, so the button can lag the typing.
                        // run() ignores exactly one character itself.
                        disabled={state.busy}
                    >
                        {state.busy ? (
                            <span class="search-view-spin">
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
                    class="search-view-chips"
                    role="tablist"
                    aria-label="Category"
                >
                    {CATEGORY_OPTIONS.map((c) => (
                        <button
                            key={c.id}
                            type="button"
                            role="tab"
                            class="search-view-tab"
                            aria-selected={state.category === c.id}
                            disabled={state.busy}
                            onClick$={() => {
                                state.category = c.id;
                                run(state.q, false);
                            }}
                        >
                            {c.label}
                        </button>
                    ))}
                </div>

                {state.answer && (
                    <div class="search-view-status" role="status">
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
                                class="search-view-chip"
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
                            <label class="search-view-sort">
                                Sort by
                                <select
                                    value={sortOptionId(state.sort)}
                                    onChange$={(_, el) => {
                                        const pick = SORT_OPTIONS.find(
                                            (o) => o.id === el.value,
                                        );
                                        if (pick) state.sort = pick.sort;
                                    }}
                                >
                                    {SORT_OPTIONS.map((o) => (
                                        <option
                                            key={o.id}
                                            value={o.id}
                                            selected={
                                                sortOptionId(state.sort) ===
                                                o.id
                                            }
                                        >
                                            {o.label}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        )}
                        {state.mode === "browse" && (
                            <button
                                type="button"
                                class="search-view-refresh"
                                disabled={state.busy}
                                onClick$={() => run(state.searched, true)}
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
                    <p class="search-view-empty">
                        {state.busy ? (
                            <span class="search-view-spin">
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
                    <div class="search-view-failure" role="alert">
                        <p>{state.failure.message}</p>
                        <ul>
                            {state.failure.causes.map((c) => (
                                <li key={c.indexer}>{errorChip(c)}</li>
                            ))}
                        </ul>
                        <button
                            type="button"
                            class="search-view-retry"
                            onClick$={() => run(state.searched, false)}
                        >
                            Try again
                        </button>
                    </div>
                )}

                {state.answer && rows.length === 0 && (
                    <p class="search-view-empty">
                        {emptyText(state.mode, state.searched)}
                    </p>
                )}

                {rows.length > 0 && (
                    <ul class="search-view-list">
                        {rows.map((r, i) => {
                            const ticks = swarmTicks(r.seeders);
                            const fetching =
                                state.fetching !== "" &&
                                state.fetching === r.link;
                            return (
                                <li
                                    key={`${r.infoHash ?? r.title}|${r.sizeBytes ?? ""}`}
                                    class="search-view-row"
                                >
                                    <span
                                        class={`search-view-swarm search-view-swarm--${seederTone(r.seeders)}`}
                                        role="img"
                                        aria-label={`${r.seeders ?? "Unknown"} seeders`}
                                        style={{
                                            "--i": String(Math.min(i, 12)),
                                        }}
                                    >
                                        {Array.from(
                                            { length: SWARM_TICKS },
                                            (_, t) => (
                                                <i
                                                    key={t}
                                                    class={
                                                        t < ticks
                                                            ? "on"
                                                            : undefined
                                                    }
                                                    style={{
                                                        "--t": String(t),
                                                    }}
                                                />
                                            ),
                                        )}
                                    </span>
                                    <div class="search-view-body">
                                        <p class="search-view-title">
                                            {r.title}
                                        </p>
                                        <p class="search-view-facts">
                                            {r.sizeBytes !== null && (
                                                <span>
                                                    {formatSize(r.sizeBytes)}
                                                </span>
                                            )}
                                            {r.seeders !== null && (
                                                <span
                                                    class={`search-view-seeders--${seederTone(r.seeders)}`}
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
                                                class="search-view-source"
                                                title={r.indexers.join(", ")}
                                            >
                                                {indexerLabel(r.indexers)}
                                            </span>
                                        </p>
                                    </div>
                                    <div class="search-view-actions">
                                        {r.magnet && (
                                            <button
                                                type="button"
                                                class="search-view-copy"
                                                aria-label={`Copy magnet for ${r.title}`}
                                                title="Copy magnet"
                                                onClick$={() =>
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
                                            class="search-view-add"
                                            aria-label={`Add ${r.title}`}
                                            disabled={fetching}
                                            onClick$={() => add(r)}
                                        >
                                            {fetching ? (
                                                <>
                                                    <span class="search-view-spin">
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
            </section>
        );
    },
);
