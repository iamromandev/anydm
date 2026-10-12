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

export function SourceView({
    state,
    onToggle,
    onTest,
    onEdit,
    onDelete,
    onAdd,
    onReset,
}: SourceViewProps) {
    return (
        <section className="source-view" aria-label="Sources">
            <div className="source-head">
                <h2 className="source-title">Sources</h2>
                <button type="button" className="source-add" onClick={onAdd}>
                    + Add
                </button>
            </div>

            {state.failure && (
                <p className="source-failure" role="alert">
                    {state.failure}
                </p>
            )}

            {state.loading ? (
                <p className="source-empty">Loading sources…</p>
            ) : state.items.length === 0 ? (
                <p className="source-empty">No sources.</p>
            ) : (
                <ul className="source-list">
                    {state.items.map((item) => {
                        const busy = state.busy[item.id];
                        return (
                            <li key={item.id} className="source-row">
                                <label className="source-switch">
                                    <input
                                        type="checkbox"
                                        className="source-switch-input"
                                        checked={item.enabled}
                                        disabled={busy !== undefined}
                                        onChange={({ currentTarget: el }) =>
                                            onToggle(item.id, el.checked)
                                        }
                                        aria-label={`${item.name} ${item.enabled ? "on" : "off"}`}
                                    />
                                    <span
                                        className="source-switch-track"
                                        aria-hidden="true"
                                    />
                                </label>
                                <div className="source-main">
                                    <div className="source-name">
                                        {item.name}{" "}
                                        <span className="source-badge">
                                            {item.kind}
                                        </span>
                                    </div>
                                    <div className="source-address">
                                        {item.baseUrl}
                                        {item.apiKeyMasked
                                            ? ` · key ${item.apiKeyMasked}`
                                            : ""}
                                    </div>
                                    <div className="source-test">
                                        {busy ??
                                            testLine(
                                                state.lastTest[item.id] ?? null,
                                            )}
                                    </div>
                                </div>
                                <div className="source-actions">
                                    <button
                                        type="button"
                                        className="source-btn"
                                        disabled={busy !== undefined}
                                        onClick={() => onTest(item.id)}
                                    >
                                        Test
                                    </button>
                                    <button
                                        type="button"
                                        className="source-btn"
                                        disabled={busy !== undefined}
                                        onClick={() => onEdit(item)}
                                    >
                                        Edit
                                    </button>
                                    {item.defaultUrl && (
                                        <button
                                            type="button"
                                            className="source-btn"
                                            disabled={busy !== undefined}
                                            onClick={() => onReset(item.id)}
                                        >
                                            Reset
                                        </button>
                                    )}
                                    {item.deletable && (
                                        <button
                                            type="button"
                                            className="source-btn source-btn--danger"
                                            disabled={busy !== undefined}
                                            onClick={() => onDelete(item)}
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
}
