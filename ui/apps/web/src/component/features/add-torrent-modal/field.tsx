import { useEffect, useRef } from "react";
import { usePatch } from "@/hook/use-patch";
import {
    addAnyway,
    canAddAnyway,
    duplicateOf,
    type AddInput,
    type Duplicate,
} from "@/lib/api/duplicate";
import { DuplicateNotice } from "@/component/features/duplicate-notice";
import {
    LuMagnet,
    LuX,
    LuCheckCircle,
    LuLoader2,
    LuPlay,
} from "@/component/core/icons";
import type { ResolvedTorrent } from "@/lib/api";
import type { CategoryItem } from "@/lib/api/category";
import { CategorySelect } from "@/component/shared/category-select";
import type { AddInitial } from "@/lib/api/search";
import { hasMediaExtension, mediaFiles, type PlayableFile } from "@/lib/media";
import "./field.css";

export interface AddTorrentModalProps {
    open: boolean;
    /** A search result to start from: filled in, and resolved on opening. */
    initial?: AddInitial | null;
    onClose: () => void;
    onResolve: (torrent: string) => Promise<ResolvedTorrent>;
    /**
     * Play the torrent: one of its files when an index is given (#98), and the
     * media files the player may switch between.
     */
    onPlay: (
        value: string,
        kind: string,
        fileIndex?: number | null,
        files?: PlayableFile[],
    ) => void | Promise<void>;
    onAdd: (input: AddInput) => Promise<void> | void;
    /** Every category, for the picker in the footer. */
    categories: CategoryItem[];
    /** Where the add saves; the picker starts on it. */
    addCategory: string;
    onAddCategoryChange: (id: string) => void;
    /** Show a download the list already holds, from a refused add. */
    onOpen: (id: string, collectionId?: string) => void;
}

function formatBytes(bytes: number): string {
    if (bytes === 0) return "0 B";
    const units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB",
    ];
    let size = bytes;
    let unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

export function AddTorrentModal({
    open,
    initial,
    onClose,
    onResolve,
    onPlay,
    onAdd,
    categories,
    addCategory,
    onAddCategoryChange,
    onOpen,
}: AddTorrentModalProps) {
    const [
        store,
        patch,
    ] = usePatch({
        inputType: (initial?.type ?? "magnet") as "magnet" | "file" | "url",
        inputValue: (initial?.value ?? "") as string,
        inputPreset: "best" as
            "best" | "2160" | "1440" | "1080" | "720" | "480" | "mp3",
        isAdding: false,
        // Step two. `resolved` is null until the magnet has been inspected.
        isResolving: false,
        resolved: null as ResolvedTorrent | null,
        selected: [] as number[],
        resolveError: "" as string,
        // A refused add that names a download the list already holds.
        duplicate: null as Duplicate | null,
        retry: null as AddInput | null,
    });

    const handleResolve = async () => {
        const value = store.inputValue.trim();
        if (!value) return;

        patch({ isResolving: true });
        patch({ resolveError: "" });
        try {
            const resolved = await onResolve(value);
            patch({ resolved: resolved });
            // Everything ticked. The picker is a way to remove files, not
            // a puzzle to solve before downloading.
            patch({ selected: resolved.files.map((file) => file.index) });
        } catch (err) {
            patch({
                resolveError:
                    err instanceof Error
                        ? err.message
                        : "Could not read that torrent",
            });
        } finally {
            patch({ isResolving: false });
        }
    };

    // A search result opens the dialog already filled in. The shell remounts
    // it with a new key for each result, so this reads `initial` once, on
    // mount; the guard stops a spurious re-run from resolving twice.
    const autoResolved = useRef(false);
    useEffect(() => {
        if (!open || !initial || autoResolved.current) return;
        autoResolved.current = true;
        void handleResolve();
    }, []);

    const toggleFile = (index: number) => {
        patch((held) => ({
            selected: held.selected.includes(index)
                ? held.selected.filter((value) => value !== index)
                : [
                      ...held.selected,
                      index,
                  ],
        }));
    };

    const selectAll = () => {
        patch({
            selected: (store.resolved?.files ?? []).map((file) => file.index),
        });
    };

    const selectNone = () => {
        patch({ selected: [] });
    };

    const resetTorrentStep = () => {
        patch({ duplicate: null });
        patch({ resolved: null });
        patch({ selected: [] });
        patch({ resolveError: "" });
    };

    /** Send an add; a refusal naming a download offers Open and Add anyway. */
    const submit = async (input: AddInput) => {
        // This modal collects input; the page owns the network call. It
        // used to POST here *and* call onAdd, which POSTed again — every
        // URL added created two tasks.
        patch({ isAdding: true });
        patch({ duplicate: null });
        try {
            await onAdd(input);
            patch({ isAdding: false });
            patch({ inputValue: "" });
            patch({ resolved: null });
            patch({ selected: [] });
            patch({ resolveError: "" });
            onClose();
        } catch (err) {
            const held = duplicateOf(err);
            if (held) {
                patch({ duplicate: held });
                patch({ retry: input });
            } else {
                console.error(err);
            }
            patch({ isAdding: false });
        }
    };

    const handleAdd = async () => {
        const { inputType, inputValue, inputPreset, selected } = store;
        if (!inputValue.trim()) {
            return;
        }

        await submit({
            // Its URL tab has no preview, so the page asks what the
            // link is and routes it: a site page or a plain file.
            type: inputType === "url" ? "link" : inputType,
            value: inputValue,
            preset: inputType !== "url" ? undefined : inputPreset,
            files: inputType === "url" ? undefined : selected,
        });
    };

    const openHeld = () => {
        const held = store.duplicate;
        if (!held) return;
        patch({ duplicate: null });
        onOpen(held.id, held.collectionId);
    };

    const addSecondCopy = async () => {
        if (store.retry) await submit(addAnyway(store.retry));
    };

    // Compute button label - simple string ternary
    const isTorrentTab =
        store.inputType === "magnet" || store.inputType === "file";
    const buttonLabel = store.isAdding ? "Adding…" : "Add";

    // Use string type for preset to avoid type narrowing issues with select
    const presetValues = [
        "best",
        "2160",
        "1440",
        "1080",
        "720",
        "480",
        "mp3",
    ] as const;
    type Preset = (typeof presetValues)[number];

    // Spinner SVG shown when adding
    const spinner = store.isAdding ? (
        <svg
            className="spin"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
        >
            <path d="M21 12a9 9 0 1 1-6.219-8.56" />
        </svg>
    ) : null;

    return open ? (
        <div
            className="modal-overlay"
            role="dialog"
            aria-modal="true"
            aria-label="Add torrent"
        >
            <div className="modal-panel">
                <header className="modal-header">
                    <h2 className="modal-title">Add Torrent</h2>
                    <button
                        type="button"
                        className="modal-close"
                        onClick={onClose}
                        aria-label="Close modal"
                    >
                        <svg
                            width="24"
                            height="24"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            aria-hidden="true"
                        >
                            <line x1="18" y1="6" x2="6" y2="18" />
                            <line x1="6" y1="6" x2="18" y2="18" />
                        </svg>
                    </button>
                </header>

                <main className="modal-body">
                    <section className="modal-tabs">
                        <button
                            type="button"
                            className={`modal-tab ${store.inputType === "magnet" ? "modal-tab--active" : ""}`}
                            onClick={() => {
                                patch({ inputType: "magnet" });
                                resetTorrentStep();
                            }}
                            aria-selected={store.inputType === "magnet"}
                            role="tab"
                        >
                            <LuMagnet
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                            <span>Magnet Link</span>
                        </button>
                        <button
                            type="button"
                            className={`modal-tab ${store.inputType === "file" ? "modal-tab--active" : ""}`}
                            onClick={() => {
                                patch({ inputType: "file" });
                                resetTorrentStep();
                            }}
                            aria-selected={store.inputType === "file"}
                            role="tab"
                        >
                            <svg
                                width="18"
                                height="18"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                                aria-hidden="true"
                            >
                                <rect
                                    x="3"
                                    y="3"
                                    width="18"
                                    height="18"
                                    rx="2"
                                    ry="2"
                                />
                                <polyline points="17 8 21 12 17 16" />
                            </svg>
                            <span>.torrent File</span>
                        </button>
                        <button
                            type="button"
                            className={`modal-tab ${store.inputType === "url" ? "modal-tab--active" : ""}`}
                            onClick={() => {
                                patch({ inputType: "url" });
                                patch({ duplicate: null });
                            }}
                            aria-selected={store.inputType === "url"}
                            role="tab"
                        >
                            <svg
                                width="18"
                                height="18"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                                aria-hidden="true"
                            >
                                <circle cx="12" cy="12" r="10" />
                                <line x1="2" y1="12" x2="22" y2="12" />
                                <path d="M12 2a20 20 0 0 1 4 10 20 20 0 0 1-4 10 20 20 0 0 1-4-10 20 20 0 0 1 4-10z" />
                            </svg>
                            <span>Link (any site or file)</span>
                        </button>
                    </section>

                    <div className="modal-input-section">
                        {store.inputType === "magnet" && (
                            <div className="magnet-input">
                                <input
                                    type="text"
                                    className="magnet-input-field"
                                    placeholder="magnet:?xt=..."
                                    value={store.inputValue}
                                    onChange={(e) => {
                                        const target =
                                            e.target as HTMLInputElement;
                                        patch({ inputValue: target.value });
                                        resetTorrentStep();
                                    }}
                                    aria-label="Magnet link"
                                />
                            </div>
                        )}

                        {store.inputType === "file" && (
                            <div className="file-input">
                                <input
                                    type="file"
                                    className="file-input-field"
                                    accept=".torrent"
                                    onChange={(e) => {
                                        const target =
                                            e.target as HTMLInputElement;
                                        if (target.files && target.files[0]) {
                                            const reader = new FileReader();
                                            reader.onload = () => {
                                                const result =
                                                    reader.result as string;
                                                const base64 =
                                                    result?.split(",")[1] || "";
                                                patch({ inputValue: base64 });
                                                resetTorrentStep();
                                            };
                                            reader.readAsDataURL(
                                                target.files[0],
                                            );
                                        }
                                    }}
                                    aria-label="Select .torrent file"
                                />
                                <p className="file-input-hint">
                                    Select a .torrent file
                                </p>
                            </div>
                        )}

                        {store.inputType === "url" && (
                            <div className="url-input-section">
                                <input
                                    type="url"
                                    className="url-input-field"
                                    placeholder="A video page, or any file link"
                                    value={store.inputValue}
                                    onChange={(e) => {
                                        const target =
                                            e.target as HTMLInputElement;
                                        patch({ inputValue: target.value });
                                        patch({ duplicate: null });
                                    }}
                                    aria-label="Video URL"
                                />
                                <select
                                    className="url-preset-select"
                                    value={store.inputPreset}
                                    onChange={(e) => {
                                        const target =
                                            e.target as HTMLSelectElement;
                                        patch({
                                            inputPreset: target.value as Preset,
                                        });
                                    }}
                                >
                                    <option value="best">Best Quality</option>
                                    <option value="2160">4K (2160p)</option>
                                    <option value="1440">1440p</option>
                                    <option value="1080">1080p</option>
                                    <option value="720">720p</option>
                                    <option value="480">480p</option>
                                    <option value="mp3">Audio (MP3)</option>
                                </select>
                            </div>
                        )}

                        {isTorrentTab && store.resolveError && (
                            <p className="modal-error">{store.resolveError}</p>
                        )}

                        {isTorrentTab && store.resolved && (
                            <section className="torrent-files">
                                <header className="torrent-files-header">
                                    <span>
                                        {store.selected.length} of{" "}
                                        {store.resolved.files.length} files
                                    </span>
                                    <span>
                                        <button
                                            type="button"
                                            className="link-btn"
                                            onClick={selectAll}
                                        >
                                            All
                                        </button>
                                        <button
                                            type="button"
                                            className="link-btn"
                                            onClick={selectNone}
                                        >
                                            None
                                        </button>
                                    </span>
                                </header>
                                <ul className="torrent-files-list">
                                    {store.resolved.files.map((file) => (
                                        <li
                                            key={file.index}
                                            className="torrent-file"
                                        >
                                            <label>
                                                <input
                                                    type="checkbox"
                                                    checked={store.selected.includes(
                                                        file.index,
                                                    )}
                                                    onChange={() =>
                                                        toggleFile(file.index)
                                                    }
                                                />
                                                <span className="torrent-file-path">
                                                    {file.path}
                                                </span>
                                                <span className="torrent-file-size">
                                                    {formatBytes(
                                                        file.sizeBytes,
                                                    )}
                                                </span>
                                            </label>
                                            {hasMediaExtension(file.path) && (
                                                <button
                                                    type="button"
                                                    className="torrent-file-play"
                                                    aria-label={`Play ${file.path}`}
                                                    onClick={() =>
                                                        onPlay(
                                                            store.inputValue,
                                                            store.inputType ===
                                                                "file"
                                                                ? "torrent"
                                                                : "magnet",
                                                            file.index,
                                                            mediaFiles(
                                                                store.resolved
                                                                    ?.files ??
                                                                    [],
                                                            ),
                                                        )
                                                    }
                                                >
                                                    <LuPlay
                                                        width="14"
                                                        height="14"
                                                        aria-hidden="true"
                                                    />
                                                </button>
                                            )}
                                        </li>
                                    ))}
                                </ul>
                            </section>
                        )}
                    </div>
                </main>

                {store.duplicate && (
                    <div className="modal-duplicate">
                        <DuplicateNotice
                            duplicate={store.duplicate}
                            busy={store.isAdding}
                            onOpen={openHeld}
                            onAddAnyway={
                                store.retry && canAddAnyway(store.retry.type)
                                    ? addSecondCopy
                                    : undefined
                            }
                        />
                    </div>
                )}

                <div className="add-torrent-category">
                    <CategorySelect
                        categories={categories}
                        value={addCategory}
                        onChange={(id: string) => onAddCategoryChange(id)}
                    />
                </div>

                <footer className="modal-footer">
                    <button
                        type="button"
                        className="modal-btn modal-btn--secondary"
                        onClick={onClose}
                        disabled={store.isAdding}
                    >
                        Cancel
                    </button>
                    {isTorrentTab && !store.resolved ? (
                        <button
                            type="button"
                            className="modal-btn modal-btn--primary"
                            onClick={handleResolve}
                            disabled={
                                store.isResolving || !store.inputValue.trim()
                            }
                        >
                            <span className="button-text">
                                {store.isResolving
                                    ? "Fetching…"
                                    : "Fetch Files"}
                            </span>
                            {store.isResolving && (
                                <svg
                                    className="spin"
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    aria-hidden="true"
                                    style={{ marginLeft: "0.5rem" }}
                                >
                                    <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                                </svg>
                            )}
                        </button>
                    ) : (
                        <>
                            {isTorrentTab && (
                                <button
                                    type="button"
                                    className="modal-btn modal-btn--secondary"
                                    onClick={() =>
                                        onPlay(
                                            store.inputValue,
                                            store.inputType === "file"
                                                ? "torrent"
                                                : "magnet",
                                            null,
                                            mediaFiles(
                                                store.resolved?.files ?? [],
                                            ),
                                        )
                                    }
                                >
                                    <LuPlay
                                        width="16"
                                        height="16"
                                        aria-hidden="true"
                                    />
                                    <span className="button-text">Play</span>
                                </button>
                            )}
                            <button
                                type="button"
                                className="modal-btn modal-btn--primary"
                                onClick={handleAdd}
                                disabled={
                                    store.isAdding ||
                                    (isTorrentTab &&
                                        store.selected.length === 0)
                                }
                            >
                                <span className="button-text">
                                    {buttonLabel}
                                </span>
                                {spinner && (
                                    <svg
                                        className="spin"
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="2"
                                        aria-hidden="true"
                                        style={{ marginLeft: "0.5rem" }}
                                    >
                                        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                                    </svg>
                                )}
                            </button>
                        </>
                    )}
                </footer>
            </div>
        </div>
    ) : null;
}
