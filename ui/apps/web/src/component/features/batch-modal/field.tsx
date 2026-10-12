import { useEffect, useRef } from "react";
import { usePatch } from "@/hook/use-patch";
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
import type { CategoryItem } from "@/lib/api/category";
import { CategorySelect } from "@/component/shared/category-select";
import "./field.css";

/** How long typing has to pause before the preview is asked for again. */
const PREVIEW_DELAY_MS = 400;

export interface BatchModalProps {
    /** Lines pasted into the add box, to start the list with. */
    initialText?: string;
    /** What the quality select starts at, from the person's preferences. */
    defaultPreset: string;
    /** Every category, for the picker beside the quality select. */
    categories: CategoryItem[];
    /** Where the batch saves at first; the picker can change it for this batch. */
    addCategory: string;
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
 * What the dialog's fields add up to. At module level because it reads only
 * its argument, so any render's fields or the latest ones give the same answer.
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
export function BatchModal({
    initialText = "",
    defaultPreset,
    categories,
    addCategory,
    onClose,
    onAdded,
    onOpen,
}: BatchModalProps) {
    const [
        store,
        patch,
    ] = usePatch({
        kind: "list" as BatchSource["kind"],
        list: initialText,
        pattern: "",
        preset: defaultPreset,
        category: addCategory,
        previewing: false,
        preview: null as BatchPreview | null,
        previewError: "",
        adding: false,
        addError: "",
        results: null as BatchItem[] | null,
    });

    // What the debounced timer compares against when its answer lands.
    const latest = useRef(store);
    latest.current = store;

    /**
     * Ask the API what the source names once typing pauses. An answer for
     * a source that has since changed is dropped.
     */
    const kind = store.kind;
    const text = kind === "list" ? store.list : store.pattern;
    useEffect(() => {
        const asked: BatchSource =
            kind === "list"
                ? { kind: "list", text }
                : { kind: "pattern", text };
        patch({ previewError: "" });
        if (!hasLinks(asked)) {
            patch({ preview: null, previewing: false });
            return;
        }
        patch({ previewing: true });
        const timer = setTimeout(async () => {
            const current = () => {
                const now = sourceOf(latest.current);
                return now.kind === asked.kind && now.text === asked.text;
            };
            try {
                const preview = await previewBatch(asked);
                if (!current()) return;
                patch({ preview });
            } catch (err) {
                if (!current()) return;
                patch({ preview: null, previewError: errorMessage(err) });
            } finally {
                if (current()) patch({ previewing: false });
            }
        }, PREVIEW_DELAY_MS);
        return () => clearTimeout(timer);
    }, [
        kind,
        text,
        patch,
    ]);

    const handleAdd = async () => {
        if (store.adding) return;
        patch({ adding: true, addError: "" });
        try {
            const results = await addBatch(
                sourceOf(store),
                store.preset,
                undefined,
                store.category,
            );
            patch({ results });
            onAdded();
        } catch (err) {
            patch({ addError: errorMessage(err) });
        } finally {
            patch({ adding: false });
        }
    };

    const startOver = () => {
        patch({ results: null });
        patch({ addError: "" });
    };

    const ready =
        !!store.preview &&
        store.preview.count > 0 &&
        !store.previewing &&
        !store.previewError &&
        !store.adding;
    const shown = store.preview ? previewHead(store.preview) : null;

    return (
        <div
            className="batch-modal-overlay"
            role="dialog"
            aria-modal="true"
            aria-label="Add many links"
        >
            <div className="batch-modal-panel">
                <header className="batch-modal-header">
                    <h2 className="batch-modal-title">Add many links</h2>
                    <button
                        type="button"
                        className="batch-modal-close"
                        onClick={onClose}
                        aria-label="Close"
                    >
                        <LuX width="18" height="18" aria-hidden="true" />
                    </button>
                </header>

                {store.results ? (
                    <div className="batch-modal-body">
                        <p className="batch-modal-summary" aria-live="polite">
                            {batchSummary(store.results)}
                        </p>
                        <ul className="batch-modal-results">
                            {store.results.map((item, index) => (
                                <li
                                    key={`${index}-${item.url}`}
                                    className="batch-modal-result"
                                >
                                    <span
                                        className={`batch-modal-chip batch-modal-chip--${item.result}`}
                                    >
                                        {OUTCOME_LABEL[item.result]}
                                    </span>
                                    <span className="batch-modal-result-text">
                                        <span className="batch-modal-url">
                                            {item.url}
                                        </span>
                                        {item.result === "error" &&
                                            item.message && (
                                                <span className="batch-modal-message">
                                                    {item.message}
                                                </span>
                                            )}
                                    </span>
                                    {item.downloadId && (
                                        <button
                                            type="button"
                                            className="batch-modal-link"
                                            onClick={() => {
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
                        <footer className="batch-modal-actions">
                            <button
                                type="button"
                                className="batch-modal-btn"
                                onClick={startOver}
                            >
                                Add more
                            </button>
                            <button
                                type="button"
                                className="batch-modal-btn batch-modal-btn--primary"
                                onClick={onClose}
                            >
                                Done
                            </button>
                        </footer>
                    </div>
                ) : (
                    <div className="batch-modal-body">
                        <div
                            className="batch-modal-tabs"
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
                                        className={`batch-modal-tab ${store.kind === kind ? "batch-modal-tab--active" : ""}`}
                                        onClick={() => {
                                            patch({ kind: kind });
                                        }}
                                    >
                                        {label}
                                    </button>
                                ),
                            )}
                        </div>

                        {store.kind === "list" ? (
                            <textarea
                                className="batch-modal-input batch-modal-textarea"
                                rows={7}
                                placeholder="One link per line"
                                aria-label="Links, one per line"
                                value={store.list}
                                onChange={({ currentTarget: el }) => {
                                    patch({ list: el.value });
                                }}
                            />
                        ) : (
                            <input
                                type="text"
                                className="batch-modal-input"
                                placeholder="https://example.com/img[001-120].png"
                                aria-label="Pattern"
                                value={store.pattern}
                                onChange={({ currentTarget: el }) => {
                                    patch({ pattern: el.value });
                                }}
                            />
                        )}
                        {store.kind === "pattern" && (
                            <p className="batch-modal-hint">
                                Ranges like [01-50], [1-9] or [a-z] expand; a
                                leading zero keeps the padding.
                            </p>
                        )}

                        <div className="batch-modal-preview" aria-live="polite">
                            {store.previewError ? (
                                <p className="batch-modal-error">
                                    {store.previewError}
                                </p>
                            ) : store.previewing ? (
                                <p className="batch-modal-note">Checking…</p>
                            ) : store.preview && shown ? (
                                <>
                                    <p className="batch-modal-count">
                                        {linkCount(store.preview.count)}
                                    </p>
                                    <ul className="batch-modal-head">
                                        {shown.head.map((url, index) => (
                                            <li
                                                key={`${index}-${url}`}
                                                className="batch-modal-url"
                                            >
                                                {url}
                                            </li>
                                        ))}
                                    </ul>
                                    {shown.more > 0 && (
                                        <p className="batch-modal-note">
                                            …and{" "}
                                            {shown.more.toLocaleString("en-US")}{" "}
                                            more
                                        </p>
                                    )}
                                </>
                            ) : (
                                <p className="batch-modal-note">
                                    Paste links or type a pattern to see what
                                    will be added.
                                </p>
                            )}
                        </div>

                        {store.addError && (
                            <p className="batch-modal-error" role="alert">
                                {store.addError}
                            </p>
                        )}

                        <footer className="batch-modal-actions">
                            <CategorySelect
                                categories={categories}
                                value={store.category}
                                onChange={(id: string) => {
                                    patch({ category: id });
                                }}
                            />
                            <select
                                className="batch-modal-select"
                                aria-label="Quality for links on a site"
                                value={store.preset}
                                onChange={({ currentTarget: el }) => {
                                    patch({ preset: el.value });
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
                            <button
                                type="button"
                                className="batch-modal-btn"
                                onClick={onClose}
                                disabled={store.adding}
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                className="batch-modal-btn batch-modal-btn--primary"
                                onClick={handleAdd}
                                disabled={!ready}
                            >
                                {store.adding
                                    ? "Adding…"
                                    : store.preview && store.preview.count > 0
                                      ? `Add ${linkCount(store.preview.count)}`
                                      : "Add"}
                            </button>
                        </footer>
                    </div>
                )}
            </div>
        </div>
    );
}
