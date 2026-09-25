import type { AddType } from "@/lib/api/site";
import { component$, $, useStore } from "@qwik.dev/core";
import {
    LuMagnet,
    LuX,
    LuCheckCircle,
    LuLoader2,
    LuPlay,
} from "@/component/core/icons";
import type { ResolvedTorrent } from "@/lib/api";
import { hasMediaExtension, mediaFiles, type PlayableFile } from "@/lib/media";
import "./field.css";

export interface AddTorrentModalProps {
    open: boolean;
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
    onAdd: (input: {
        type: AddType;
        value: string;
        preset?: string;
        files?: number[];
    }) => Promise<void> | void;
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

export const AddTorrentModal = component$<AddTorrentModalProps>(
    ({ open, onClose, onResolve, onPlay, onAdd }) => {
        const store = useStore({
            inputType: "magnet" as "magnet" | "file" | "url",
            inputValue: "" as string,
            inputPreset: "best" as
                "best" | "2160" | "1440" | "1080" | "720" | "480" | "mp3",
            isAdding: false,
            // Step two. `resolved` is null until the magnet has been inspected.
            isResolving: false,
            resolved: null as ResolvedTorrent | null,
            selected: [] as number[],
            resolveError: "" as string,
        });

        const handleResolve = $(async () => {
            const value = store.inputValue.trim();
            if (!value) return;

            store.isResolving = true;
            store.resolveError = "";
            try {
                const resolved = await onResolve(value);
                store.resolved = resolved;
                // Everything ticked. The picker is a way to remove files, not
                // a puzzle to solve before downloading.
                store.selected = resolved.files.map((file) => file.index);
            } catch (err) {
                store.resolveError =
                    err instanceof Error
                        ? err.message
                        : "Could not read that torrent";
            } finally {
                store.isResolving = false;
            }
        });

        const toggleFile = $((index: number) => {
            store.selected = store.selected.includes(index)
                ? store.selected.filter((value) => value !== index)
                : [
                      ...store.selected,
                      index,
                  ];
        });

        const selectAll = $(() => {
            store.selected = (store.resolved?.files ?? []).map(
                (file) => file.index,
            );
        });

        const selectNone = $(() => {
            store.selected = [];
        });

        const resetTorrentStep = $(() => {
            store.resolved = null;
            store.selected = [];
            store.resolveError = "";
        });

        const handleAdd = $(async () => {
            const { inputType, inputValue, inputPreset, selected } = store;
            if (!inputValue.trim()) {
                return;
            }

            // This modal collects input; the page owns the network call. It
            // used to POST here *and* call onAdd, which POSTed again — every
            // URL added created two tasks.
            store.isAdding = true;
            try {
                await onAdd({
                    // Its URL tab has no preview, so the page asks what the
                    // link is and routes it: a site page or a plain file.
                    type: inputType === "url" ? "link" : inputType,
                    value: inputValue,
                    preset: inputType !== "url" ? undefined : inputPreset,
                    files: inputType === "url" ? undefined : selected,
                });
                store.isAdding = false;
                store.inputValue = "";
                store.resolved = null;
                store.selected = [];
                store.resolveError = "";
                onClose();
            } catch (err) {
                console.error(err);
                store.isAdding = false;
            }
        });

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
                class="spin"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                aria-hidden="true"
            >
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
            </svg>
        ) : null;

        return open ? (
            <div
                class="modal-overlay"
                role="dialog"
                aria-modal="true"
                aria-label="Add torrent"
            >
                <div class="modal-panel">
                    <header class="modal-header">
                        <h2 class="modal-title">Add Torrent</h2>
                        <button
                            type="button"
                            class="modal-close"
                            onClick$={onClose}
                            aria-label="Close modal"
                        >
                            <svg
                                width="24"
                                height="24"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                aria-hidden="true"
                            >
                                <line x1="18" y1="6" x2="6" y2="18" />
                                <line x1="6" y1="6" x2="18" y2="18" />
                            </svg>
                        </button>
                    </header>

                    <main class="modal-body">
                        <section class="modal-tabs">
                            <button
                                type="button"
                                class={`modal-tab ${store.inputType === "magnet" ? "modal-tab--active" : ""}`}
                                onClick$={() => {
                                    store.inputType = "magnet";
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
                                class={`modal-tab ${store.inputType === "file" ? "modal-tab--active" : ""}`}
                                onClick$={() => {
                                    store.inputType = "file";
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
                                    stroke-width="2"
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
                                class={`modal-tab ${store.inputType === "url" ? "modal-tab--active" : ""}`}
                                onClick$={() => {
                                    store.inputType = "url";
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
                                    stroke-width="2"
                                    aria-hidden="true"
                                >
                                    <circle cx="12" cy="12" r="10" />
                                    <line x1="2" y1="12" x2="22" y2="12" />
                                    <path d="M12 2a20 20 0 0 1 4 10 20 20 0 0 1-4 10 20 20 0 0 1-4-10 20 20 0 0 1 4-10z" />
                                </svg>
                                <span>Link (any site or file)</span>
                            </button>
                        </section>

                        <div class="modal-input-section">
                            {store.inputType === "magnet" && (
                                <div class="magnet-input">
                                    <input
                                        type="text"
                                        class="magnet-input-field"
                                        placeholder="magnet:?xt=..."
                                        value={store.inputValue}
                                        onInput$={(e: Event) => {
                                            const target =
                                                e.target as HTMLInputElement;
                                            store.inputValue = target.value;
                                            resetTorrentStep();
                                        }}
                                        aria-label="Magnet link"
                                    />
                                </div>
                            )}

                            {store.inputType === "file" && (
                                <div class="file-input">
                                    <input
                                        type="file"
                                        class="file-input-field"
                                        accept=".torrent"
                                        onChange$={(e: Event) => {
                                            const target =
                                                e.target as HTMLInputElement;
                                            if (
                                                target.files &&
                                                target.files[0]
                                            ) {
                                                const reader = new FileReader();
                                                reader.onload = () => {
                                                    const result =
                                                        reader.result as string;
                                                    const base64 =
                                                        result?.split(",")[1] ||
                                                        "";
                                                    store.inputValue = base64;
                                                    resetTorrentStep();
                                                };
                                                reader.readAsDataURL(
                                                    target.files[0],
                                                );
                                            }
                                        }}
                                        aria-label="Select .torrent file"
                                    />
                                    <p class="file-input-hint">
                                        Select a .torrent file
                                    </p>
                                </div>
                            )}

                            {store.inputType === "url" && (
                                <div class="url-input-section">
                                    <input
                                        type="url"
                                        class="url-input-field"
                                        placeholder="A video page, or any file link"
                                        value={store.inputValue}
                                        onInput$={(e: Event) => {
                                            const target =
                                                e.target as HTMLInputElement;
                                            store.inputValue = target.value;
                                        }}
                                        aria-label="Video URL"
                                    />
                                    <select
                                        class="url-preset-select"
                                        value={store.inputPreset}
                                        onChange$={(e: Event) => {
                                            const target =
                                                e.target as HTMLSelectElement;
                                            store.inputPreset =
                                                target.value as Preset;
                                        }}
                                    >
                                        <option value="best">
                                            Best Quality
                                        </option>
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
                                <p class="modal-error">{store.resolveError}</p>
                            )}

                            {isTorrentTab && store.resolved && (
                                <section class="torrent-files">
                                    <header class="torrent-files-header">
                                        <span>
                                            {store.selected.length} of{" "}
                                            {store.resolved.files.length} files
                                        </span>
                                        <span>
                                            <button
                                                type="button"
                                                class="link-btn"
                                                onClick$={selectAll}
                                            >
                                                All
                                            </button>
                                            <button
                                                type="button"
                                                class="link-btn"
                                                onClick$={selectNone}
                                            >
                                                None
                                            </button>
                                        </span>
                                    </header>
                                    <ul class="torrent-files-list">
                                        {store.resolved.files.map((file) => (
                                            <li
                                                key={file.index}
                                                class="torrent-file"
                                            >
                                                <label>
                                                    <input
                                                        type="checkbox"
                                                        checked={store.selected.includes(
                                                            file.index,
                                                        )}
                                                        onChange$={() =>
                                                            toggleFile(
                                                                file.index,
                                                            )
                                                        }
                                                    />
                                                    <span class="torrent-file-path">
                                                        {file.path}
                                                    </span>
                                                    <span class="torrent-file-size">
                                                        {formatBytes(
                                                            file.sizeBytes,
                                                        )}
                                                    </span>
                                                </label>
                                                {hasMediaExtension(
                                                    file.path,
                                                ) && (
                                                    <button
                                                        type="button"
                                                        class="torrent-file-play"
                                                        aria-label={`Play ${file.path}`}
                                                        onClick$={$(() =>
                                                            onPlay(
                                                                store.inputValue,
                                                                store.inputType ===
                                                                    "file"
                                                                    ? "torrent"
                                                                    : "magnet",
                                                                file.index,
                                                                mediaFiles(
                                                                    store
                                                                        .resolved
                                                                        ?.files ??
                                                                        [],
                                                                ),
                                                            ),
                                                        )}
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

                    <footer class="modal-footer">
                        <button
                            type="button"
                            class="modal-btn modal-btn--secondary"
                            onClick$={onClose}
                            disabled={store.isAdding}
                        >
                            Cancel
                        </button>
                        {isTorrentTab && !store.resolved ? (
                            <button
                                type="button"
                                class="modal-btn modal-btn--primary"
                                onClick$={handleResolve}
                                disabled={
                                    store.isResolving ||
                                    !store.inputValue.trim()
                                }
                            >
                                <span class="button-text">
                                    {store.isResolving
                                        ? "Fetching…"
                                        : "Fetch Files"}
                                </span>
                                {store.isResolving && (
                                    <svg
                                        class="spin"
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
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
                                        class="modal-btn modal-btn--secondary"
                                        onClick$={$(() =>
                                            onPlay(
                                                store.inputValue,
                                                store.inputType === "file"
                                                    ? "torrent"
                                                    : "magnet",
                                                null,
                                                mediaFiles(
                                                    store.resolved?.files ?? [],
                                                ),
                                            ),
                                        )}
                                    >
                                        <LuPlay
                                            width="16"
                                            height="16"
                                            aria-hidden="true"
                                        />
                                        <span class="button-text">Play</span>
                                    </button>
                                )}
                                <button
                                    type="button"
                                    class="modal-btn modal-btn--primary"
                                    onClick$={handleAdd}
                                    disabled={
                                        store.isAdding ||
                                        (isTorrentTab &&
                                            store.selected.length === 0)
                                    }
                                >
                                    <span class="button-text">
                                        {buttonLabel}
                                    </span>
                                    {spinner && (
                                        <svg
                                            class="spin"
                                            width="16"
                                            height="16"
                                            viewBox="0 0 24 24"
                                            fill="none"
                                            stroke="currentColor"
                                            stroke-width="2"
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
    },
);
