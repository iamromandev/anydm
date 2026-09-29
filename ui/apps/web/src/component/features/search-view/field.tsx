import { component$, $ } from "@qwik.dev/core";
import {
    LuCopy,
    LuLoader2,
    LuPlus,
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
    canSearch,
    errorChip,
    formatAge,
    formatSize,
    indexerLabel,
    nextSort,
    seederTone,
    sortFound,
    statusLine,
    type SortKey,
} from "./present";
import { failureOf, type SearchState } from "./state";
import "./field.css";

export interface SearchViewProps {
    state: SearchState;
    indexerCount: number;
    onAdd: (initial: AddInitial) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

const COLUMNS: Array<{ key: SortKey; label: string; numeric: boolean }> = [
    { key: "title", label: "Name", numeric: false },
    { key: "size", label: "Size", numeric: true },
    { key: "seeders", label: "Seeders", numeric: true },
    { key: "leechers", label: "Leechers", numeric: true },
    { key: "published", label: "Age", numeric: true },
    { key: "indexer", label: "Indexer", numeric: false },
];

export const SearchView = component$<SearchViewProps>(
    ({ state, indexerCount, onAdd, onNotify }) => {
        // Declared before its callers: a $() captures only what is above it.
        const runSearch = $(async () => {
            if (!canSearch(state.q, state.busy)) return;
            const q = state.q.trim();
            state.busy = true;
            state.failure = null;
            try {
                state.answer = await searchIndexers(q, state.category);
                state.searched = q;
            } catch (error) {
                state.answer = null;
                state.searched = q;
                state.failure = failureOf(error);
            } finally {
                state.busy = false;
            }
        });

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
                    onSubmit$={runSearch}
                >
                    <input
                        class="search-view-query"
                        type="search"
                        placeholder="Search your indexers"
                        aria-label="Search your indexers"
                        value={state.q}
                        onInput$={(_, el) => {
                            state.q = el.value;
                        }}
                    />
                    <select
                        class="search-view-category"
                        aria-label="Category"
                        value={state.category}
                        onChange$={(_, el) => {
                            state.category =
                                el.value as SearchState["category"];
                        }}
                    >
                        {CATEGORY_OPTIONS.map((c) => (
                            <option key={c.id} value={c.id}>
                                {c.label}
                            </option>
                        ))}
                    </select>
                    <button
                        class="search-view-go"
                        type="submit"
                        // Disabled only while a search runs: a browser won't submit on Enter
                        // while the submit button is disabled, and on a first visit the input's
                        // handler is still loading, so the button can lag the typing.
                        // runSearch ignores a query under 2 characters itself.
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

                {state.answer && (
                    <div class="search-view-status" role="status">
                        <span>
                            {statusLine(
                                state.answer.results.length,
                                indexerCount - state.answer.errors.length,
                                state.answer.tookMs,
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
                    </div>
                )}

                {!state.answer && !state.failure && !state.busy && (
                    <p class="search-view-empty">Search your indexers</p>
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
                            onClick$={runSearch}
                        >
                            Try again
                        </button>
                    </div>
                )}

                {state.answer && rows.length === 0 && (
                    <p class="search-view-empty">
                        No results for “{state.searched}”
                    </p>
                )}

                {rows.length > 0 && (
                    <table class="search-view-table">
                        <thead>
                            <tr>
                                {COLUMNS.map((col) => (
                                    <th
                                        key={col.key}
                                        class={
                                            col.numeric ? "search-view-num" : ""
                                        }
                                        aria-sort={
                                            state.sort.key === col.key
                                                ? state.sort.descending
                                                    ? "descending"
                                                    : "ascending"
                                                : "none"
                                        }
                                    >
                                        <button
                                            type="button"
                                            onClick$={() => {
                                                state.sort = nextSort(
                                                    state.sort,
                                                    col.key,
                                                );
                                            }}
                                        >
                                            {col.label}
                                            {state.sort.key === col.key &&
                                                (state.sort.descending
                                                    ? " ↓"
                                                    : " ↑")}
                                        </button>
                                    </th>
                                ))}
                                <th>
                                    <span class="sr-only">Actions</span>
                                </th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map((r) => (
                                <tr
                                    key={`${r.infoHash ?? r.title}|${r.sizeBytes ?? ""}`}
                                >
                                    <td
                                        class="search-view-title"
                                        data-label="Name"
                                    >
                                        {r.title}
                                    </td>
                                    <td
                                        class="search-view-num"
                                        data-label="Size"
                                    >
                                        {formatSize(r.sizeBytes)}
                                    </td>
                                    <td
                                        class={`search-view-num search-view-seeders--${seederTone(r.seeders)}`}
                                        data-label="Seeders"
                                    >
                                        {r.seeders ?? "—"}
                                    </td>
                                    <td
                                        class="search-view-num"
                                        data-label="Leechers"
                                    >
                                        {r.leechers ?? "—"}
                                    </td>
                                    <td
                                        class="search-view-num"
                                        data-label="Age"
                                    >
                                        {formatAge(r.published, now)}
                                    </td>
                                    <td
                                        data-label="Indexer"
                                        title={r.indexers.join(", ")}
                                    >
                                        {indexerLabel(r.indexers)}
                                    </td>
                                    <td class="search-view-actions">
                                        <button
                                            type="button"
                                            class="search-view-add"
                                            aria-label={`Add ${r.title}`}
                                            disabled={
                                                state.fetching !== "" &&
                                                state.fetching === r.link
                                            }
                                            onClick$={() => add(r)}
                                        >
                                            {state.fetching !== "" &&
                                            state.fetching === r.link ? (
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
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </section>
        );
    },
);
