import { component$, $, useStore } from "@qwik.dev/core";
import { LuX } from "@/component/core/icons";
import {
    createSource,
    probeSource,
    testSource,
    updateSource,
    type SourceItem,
} from "@/lib/api/source";
import { errorMessage } from "@/lib/toast";
import {
    dialogTestFailed,
    dialogTestResult,
    emptyDialogTest,
    testLine,
    type DialogTestState,
} from "@/component/features/source-view";
import "./field.css";

export type SourceModalMode =
    { type: "add" } | { type: "edit"; source: SourceItem };

export interface SourceModalProps {
    mode: SourceModalMode;
    onClose: () => void;
    onSaved: (source: SourceItem) => void;
    onNotify: (tone: "error" | "info", message: string) => void;
}

const KIND_OPTIONS: Array<{ id: string; label: string }> = [
    { id: "torznab", label: "Torznab indexer" },
    { id: "apibay", label: "apibay" },
    { id: "nyaa", label: "Nyaa" },
    { id: "eztv", label: "EZTV" },
];

export const SourceModal = component$<SourceModalProps>(
    ({ mode, onClose, onSaved, onNotify }) => {
        const editing = mode.type === "edit" ? mode.source : null;
        const form = useStore({
            name: editing?.name ?? "",
            kind: editing?.kind ?? "torznab",
            address: editing?.baseUrl ?? "",
            apiKey: "",
        });
        // Blank means "keep the stored key" until the field is touched; a
        // touched-but-blank field clears it. The real key is never shown.
        const keyEdited = useStore({ touched: false });
        const test = useStore<DialogTestState>(emptyDialogTest());
        const saving = useStore({ busy: false });

        const title = mode.type === "add" ? "Add source" : "Edit source";
        const ready =
            form.address.trim() !== "" &&
            (mode.type === "edit" || form.name.trim() !== "") &&
            !saving.busy &&
            !test.testing;

        const runTest = $(async () => {
            test.testing = true;
            test.failure = null;
            try {
                const key =
                    form.kind === "torznab" && form.apiKey
                        ? form.apiKey
                        : undefined;
                const result =
                    mode.type === "add"
                        ? await probeSource({
                              kind: form.kind,
                              baseUrl: form.address.trim(),
                              ...(key !== undefined ? { apiKey: key } : {}),
                          })
                        : await testSource(editing?.id ?? "", {
                              baseUrl: form.address.trim(),
                              ...(key !== undefined ? { apiKey: key } : {}),
                          });
                Object.assign(test, dialogTestResult(result));
            } catch (err) {
                Object.assign(test, dialogTestFailed(errorMessage(err)));
                onNotify("error", errorMessage(err));
            }
        });

        const runSave = $(async () => {
            saving.busy = true;
            try {
                const saved =
                    mode.type === "add"
                        ? await createSource({
                              name: form.name.trim(),
                              kind: form.kind,
                              baseUrl: form.address.trim(),
                              ...(form.kind === "torznab" && form.apiKey
                                  ? { apiKey: form.apiKey }
                                  : {}),
                          })
                        : await updateSource(editing?.id ?? "", {
                              baseUrl: form.address.trim(),
                              ...(editing?.kind === "torznab" &&
                              keyEdited.touched
                                  ? { apiKey: form.apiKey }
                                  : {}),
                          });
                await onSaved(saved);
            } catch (err) {
                onNotify("error", errorMessage(err));
            } finally {
                saving.busy = false;
            }
        });

        return (
            <div
                class="source-modal-overlay"
                role="dialog"
                aria-modal="true"
                aria-label={title}
            >
                <div class="source-modal-panel">
                    <div class="source-modal-header">
                        <h2 class="source-modal-title">{title}</h2>
                        <button
                            type="button"
                            class="source-modal-close"
                            aria-label={`Close ${title.toLowerCase()}`}
                            onClick$={onClose}
                        >
                            <LuX width="18" height="18" aria-hidden="true" />
                        </button>
                    </div>

                    <div class="source-modal-body">
                        {mode.type === "add" ? (
                            <>
                                <label class="source-modal-field">
                                    <span class="source-modal-label">Name</span>
                                    <input
                                        class="source-modal-input"
                                        value={form.name}
                                        onInput$={(_, el) => {
                                            form.name = el.value;
                                        }}
                                        placeholder="prowlarr"
                                        autocomplete="off"
                                        spellcheck={false}
                                    />
                                </label>
                                <label class="source-modal-field">
                                    <span class="source-modal-label">Kind</span>
                                    <select
                                        class="source-modal-input"
                                        value={form.kind}
                                        onChange$={(_, el) => {
                                            form.kind = el.value;
                                        }}
                                    >
                                        {KIND_OPTIONS.map((option) => (
                                            <option
                                                key={option.id}
                                                value={option.id}
                                            >
                                                {option.label}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            </>
                        ) : (
                            <div class="source-modal-readonly">
                                <div class="source-modal-name">
                                    {editing?.name}{" "}
                                    <span class="source-modal-badge">
                                        {editing?.kind}
                                    </span>
                                </div>
                                <p class="source-modal-note">
                                    A source's name and kind can't change —
                                    delete it and add another to rename.
                                </p>
                            </div>
                        )}

                        <label class="source-modal-field">
                            <span class="source-modal-label">Address</span>
                            <input
                                class="source-modal-input"
                                value={form.address}
                                onInput$={(_, el) => {
                                    form.address = el.value;
                                }}
                                placeholder="http://prowlarr:9696/1/api"
                                autocomplete="off"
                                spellcheck={false}
                            />
                        </label>

                        {(mode.type === "add"
                            ? form.kind === "torznab"
                            : editing?.kind === "torznab") && (
                            <label class="source-modal-field">
                                <span class="source-modal-label">
                                    API key
                                    {mode.type === "edit" &&
                                        (editing?.apiKeyMasked
                                            ? ` (currently ${editing.apiKeyMasked} — leave blank to keep)`
                                            : " (optional)")}
                                </span>
                                <input
                                    type="password"
                                    class="source-modal-input"
                                    value={form.apiKey}
                                    onInput$={(_, el) => {
                                        form.apiKey = el.value;
                                        keyEdited.touched = true;
                                    }}
                                    autocomplete="off"
                                    spellcheck={false}
                                />
                            </label>
                        )}

                        {(test.result || test.failure) && (
                            <p class="source-modal-test" role="status">
                                {test.failure ?? testLine(test.result)}
                            </p>
                        )}
                    </div>

                    <div class="source-modal-actions">
                        <button
                            type="button"
                            class="source-modal-btn"
                            disabled={!ready}
                            onClick$={runSave}
                        >
                            {saving.busy
                                ? "Saving…"
                                : mode.type === "add"
                                  ? "Add source"
                                  : "Save"}
                        </button>
                        <button
                            type="button"
                            class="source-modal-btn"
                            onClick$={onClose}
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            class="source-modal-btn source-modal-test-btn"
                            disabled={!ready}
                            onClick$={runTest}
                        >
                            {test.testing ? "Testing…" : "Test"}
                        </button>
                    </div>
                </div>
            </div>
        );
    },
);
