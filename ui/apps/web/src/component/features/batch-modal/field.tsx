import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import { LuX } from "@/component/core/icons";
import {
    addBatch,
    batchSummary,
    hasLinks,
    linkCount,
    previewBatch,
    previewHead,
    type BatchItem,
    type BatchPreview,
    type BatchSource,
} from "@/lib/api/batch";
import { PRESET_OPTIONS } from "@/lib/prefs";
import { errorMessage } from "@/lib/toast";
import "./field.css";

/** How long typing has to pause before the preview is asked for again. */
const PREVIEW_DELAY_MS = 400;

export interface BatchModalProps {
    /** Lines pasted into the add box, to start the list with. */
    initialText?: string;
    /** What the quality select starts at, from the person's preferences. */
    defaultPreset: string;
    onClose: () => void;
    /** After an add landed: the page asks for its counts again. */
    onAdded: () => void;
    /** Show a download the list holds, from a result's Open. */
    onOpen: (id: string) => void;
}

const OUTCOME_LABEL: Record<BatchItem["result"], string> = {
    added: "Added",
    duplicate: "In your list",
    error: "Failed",
};

/**
 * What the dialog's fields add up to. At module level, not in the component:
 * a closure there would be captured by its handlers, and Qwik cannot carry a
 * plain function across.
 */
function sourceOf(fields: {
    kind: BatchSource["kind"];
    list: string;
    pattern: string;
}): BatchSource {
    return fields.kind === "list"
        ? { kind: "list", text: fields.list }
        : { kind: "pattern", text: fields.pattern };
}

/**
 * Add many links at once: a pasted list, or one pattern such as
 * `img[001-120].png`. The API previews what either names before anything is
 * added, then answers for every link.
 */
export const BatchModal = component$<BatchModalProps>(
    ({ initialText = "", defaultPreset, onClose, onAdded, onOpen }) => {
        const store = useStore({
            kind: "list" as BatchSource["kind"],
            list: initialText,
            pattern: "",
            preset: defaultPreset,
            previewing: false,
            preview: null as BatchPreview | null,
            previewError: "",
            adding: false,
            addError: "",
            results: null as BatchItem[] | null,
        });

        /**
         * Ask the API what the source names once typing pauses. An answer for
         * a source that has since changed is dropped. ``document-ready``: the
         * dialog is fixed over the page, where the default strategy may not run.
         */
        useVisibleTask$(
            ({ track, cleanup }) => {
                const kind = track(() => store.kind);
                const text = track(() =>
                    kind === "list" ? store.list : store.pattern,
                );
                const asked: BatchSource =
                    kind === "list"
                        ? { kind: "list", text }
                        : { kind: "pattern", text };
                store.previewError = "";
                if (!hasLinks(asked)) {
                    store.preview = null;
                    store.previewing = false;
                    return;
                }
                store.previewing = true;
                const timer = setTimeout(async () => {
                    const current = () => {
                        const now = sourceOf(store);
                        return (
                            now.kind === asked.kind && now.text === asked.text
                        );
                    };
                    try {
                        const preview = await previewBatch(asked);
                        if (!current()) return;
                        store.preview = preview;
                    } catch (err) {
                        if (!current()) return;
                        store.preview = null;
                        store.previewError = errorMessage(err);
                    } finally {
                        if (current()) store.previewing = false;
                    }
                }, PREVIEW_DELAY_MS);
                cleanup(() => clearTimeout(timer));
            },
            { strategy: "document-ready" },
        );

        const handleAdd = $(async () => {
            if (store.adding) return;
            store.adding = true;
            store.addError = "";
            try {
                store.results = await addBatch(sourceOf(store), store.preset);
                onAdded();
            } catch (err) {
                store.addError = errorMessage(err);
            } finally {
                store.adding = false;
            }
        });

        const startOver = $(() => {
            store.results = null;
            store.addError = "";
        });

        const ready =
            !!store.preview &&
            store.preview.count > 0 &&
            !store.previewing &&
            !store.previewError &&
            !store.adding;
        const shown = store.preview ? previewHead(store.preview) : null;

        return (
            <div
                class="batch-modal-overlay"
                role="dialog"
                aria-modal="true"
                aria-label="Add many links"
            >
                <div class="batch-modal-panel">
                    <header class="batch-modal-header">
                        <h2 class="batch-modal-title">Add many links</h2>
                        <button
                            type="button"
                            class="batch-modal-close"
                            onClick$={onClose}
                            aria-label="Close"
                        >
                            <LuX width="18" height="18" aria-hidden="true" />
                        </button>
                    </header>

                    {store.results ? (
                        <div class="batch-modal-body">
                            <p class="batch-modal-summary" aria-live="polite">
                                {batchSummary(store.results)}
                            </p>
                            <ul class="batch-modal-results">
                                {store.results.map((item, index) => (
                                    <li
                                        key={`${index}-${item.url}`}
                                        class="batch-modal-result"
                                    >
                                        <span
                                            class={`batch-modal-chip batch-modal-chip--${item.result}`}
                                        >
                                            {OUTCOME_LABEL[item.result]}
                                        </span>
                                        <span class="batch-modal-result-text">
                                            <span class="batch-modal-url">
                                                {item.url}
                                            </span>
                                            {item.result === "error" &&
                                                item.message && (
                                                    <span class="batch-modal-message">
                                                        {item.message}
                                                    </span>
                                                )}
                                        </span>
                                        {item.downloadId && (
                                            <button
                                                type="button"
                                                class="batch-modal-link"
                                                onClick$={() => {
                                                    if (item.downloadId)
                                                        onOpen(item.downloadId);
                                                }}
                                            >
                                                Open
                                            </button>
                                        )}
                                    </li>
                                ))}
                            </ul>
                            <footer class="batch-modal-actions">
                                <button
                                    type="button"
                                    class="batch-modal-btn"
                                    onClick$={startOver}
                                >
                                    Add more
                                </button>
                                <button
                                    type="button"
                                    class="batch-modal-btn batch-modal-btn--primary"
                                    onClick$={onClose}
                                >
                                    Done
                                </button>
                            </footer>
                        </div>
                    ) : (
                        <div class="batch-modal-body">
                            <div
                                class="batch-modal-tabs"
                                role="tablist"
                                aria-label="Links from"
                            >
                                {(
                                    [
                                        [
                                            "list",
                                            "List",
                                        ],
                                        [
                                            "pattern",
                                            "Pattern",
                                        ],
                                    ] as const
                                ).map(
                                    ([
                                        kind,
                                        label,
                                    ]) => (
                                        <button
                                            key={kind}
                                            type="button"
                                            role="tab"
                                            aria-selected={store.kind === kind}
                                            class={`batch-modal-tab ${store.kind === kind ? "batch-modal-tab--active" : ""}`}
                                            onClick$={() => {
                                                store.kind = kind;
                                            }}
                                        >
                                            {label}
                                        </button>
                                    ),
                                )}
                            </div>

                            {store.kind === "list" ? (
                                <textarea
                                    class="batch-modal-input batch-modal-textarea"
                                    rows={7}
                                    placeholder="One link per line"
                                    aria-label="Links, one per line"
                                    value={store.list}
                                    onInput$={(_, el) => {
                                        store.list = el.value;
                                    }}
                                />
                            ) : (
                                <input
                                    type="text"
                                    class="batch-modal-input"
                                    placeholder="https://example.com/img[001-120].png"
                                    aria-label="Pattern"
                                    value={store.pattern}
                                    onInput$={(_, el) => {
                                        store.pattern = el.value;
                                    }}
                                />
                            )}
                            {store.kind === "pattern" && (
                                <p class="batch-modal-hint">
                                    Ranges like [01-50], [1-9] or [a-z] expand;
                                    a leading zero keeps the padding.
                                </p>
                            )}

                            <div class="batch-modal-preview" aria-live="polite">
                                {store.previewError ? (
                                    <p class="batch-modal-error">
                                        {store.previewError}
                                    </p>
                                ) : store.previewing ? (
                                    <p class="batch-modal-note">Checking…</p>
                                ) : store.preview && shown ? (
                                    <>
                                        <p class="batch-modal-count">
                                            {linkCount(store.preview.count)}
                                        </p>
                                        <ul class="batch-modal-head">
                                            {shown.head.map((url, index) => (
                                                <li
                                                    key={`${index}-${url}`}
                                                    class="batch-modal-url"
                                                >
                                                    {url}
                                                </li>
                                            ))}
                                        </ul>
                                        {shown.more > 0 && (
                                            <p class="batch-modal-note">
                                                …and{" "}
                                                {shown.more.toLocaleString(
                                                    "en-US",
                                                )}{" "}
                                                more
                                            </p>
                                        )}
                                    </>
                                ) : (
                                    <p class="batch-modal-note">
                                        Paste links or type a pattern to see
                                        what will be added.
                                    </p>
                                )}
                            </div>

                            {store.addError && (
                                <p class="batch-modal-error" role="alert">
                                    {store.addError}
                                </p>
                            )}

                            <footer class="batch-modal-actions">
                                <select
                                    class="batch-modal-select"
                                    aria-label="Quality for links on a site"
                                    onChange$={(_, el) => {
                                        store.preset = el.value;
                                    }}
                                >
                                    {PRESET_OPTIONS.map((option) => (
                                        <option
                                            key={option.value}
                                            value={option.value}
                                            selected={
                                                option.value === store.preset
                                            }
                                        >
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                                <button
                                    type="button"
                                    class="batch-modal-btn"
                                    onClick$={onClose}
                                    disabled={store.adding}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="button"
                                    class="batch-modal-btn batch-modal-btn--primary"
                                    onClick$={handleAdd}
                                    disabled={!ready}
                                >
                                    {store.adding
                                        ? "Adding…"
                                        : store.preview &&
                                            store.preview.count > 0
                                          ? `Add ${linkCount(store.preview.count)}`
                                          : "Add"}
                                </button>
                            </footer>
                        </div>
                    )}
                </div>
            </div>
        );
    },
);
