import { component$ } from "@qwik.dev/core";
import type { SourceItem } from "@/lib/api/source";
import { testLine, type SourceViewState } from "./state";
import "./field.css";

export interface SourceViewProps {
    state: SourceViewState;
    /** A switch flipped: saved at once. */
    onToggle: (id: string, enabled: boolean) => void;
    onTest: (id: string) => void;
    onEdit: (source: SourceItem) => void;
    onDelete: (source: SourceItem) => void;
    onAdd: () => void;
    /** Back to the registry address and state; only offered when there is a default. */
    onReset: (id: string) => void;
}

export const SourceView = component$<SourceViewProps>(
    ({ state, onToggle, onTest, onEdit, onDelete, onAdd, onReset }) => {
        return (
            <section class="source-view" aria-label="Sources">
                <div class="source-head">
                    <h2 class="source-title">Sources</h2>
                    <button type="button" class="source-add" onClick$={onAdd}>
                        + Add
                    </button>
                </div>

                {state.failure && (
                    <p class="source-failure" role="alert">
                        {state.failure}
                    </p>
                )}

                {state.loading ? (
                    <p class="source-empty">Loading sources…</p>
                ) : state.items.length === 0 ? (
                    <p class="source-empty">No sources.</p>
                ) : (
                    <ul class="source-list">
                        {state.items.map((item) => {
                            const busy = state.busy[item.id];
                            return (
                                <li key={item.id} class="source-row">
                                    <label class="source-switch">
                                        <input
                                            type="checkbox"
                                            class="source-switch-input"
                                            checked={item.enabled}
                                            disabled={busy !== undefined}
                                            onChange$={(_, el) =>
                                                onToggle(item.id, el.checked)
                                            }
                                            aria-label={`${item.name} ${item.enabled ? "on" : "off"}`}
                                        />
                                        <span
                                            class="source-switch-track"
                                            aria-hidden="true"
                                        />
                                    </label>
                                    <div class="source-main">
                                        <div class="source-name">
                                            {item.name}{" "}
                                            <span class="source-badge">
                                                {item.kind}
                                            </span>
                                        </div>
                                        <div class="source-address">
                                            {item.baseUrl}
                                            {item.apiKeyMasked
                                                ? ` · key ${item.apiKeyMasked}`
                                                : ""}
                                        </div>
                                        <div class="source-test">
                                            {busy ??
                                                testLine(
                                                    state.lastTest[item.id] ??
                                                        null,
                                                )}
                                        </div>
                                    </div>
                                    <div class="source-actions">
                                        <button
                                            type="button"
                                            class="source-btn"
                                            disabled={busy !== undefined}
                                            onClick$={() => onTest(item.id)}
                                        >
                                            Test
                                        </button>
                                        <button
                                            type="button"
                                            class="source-btn"
                                            disabled={busy !== undefined}
                                            onClick$={() => onEdit(item)}
                                        >
                                            Edit
                                        </button>
                                        {item.defaultUrl && (
                                            <button
                                                type="button"
                                                class="source-btn"
                                                disabled={busy !== undefined}
                                                onClick$={() =>
                                                    onReset(item.id)
                                                }
                                            >
                                                Reset
                                            </button>
                                        )}
                                        {item.deletable && (
                                            <button
                                                type="button"
                                                class="source-btn source-btn--danger"
                                                disabled={busy !== undefined}
                                                onClick$={() => onDelete(item)}
                                            >
                                                Delete
                                            </button>
                                        )}
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
