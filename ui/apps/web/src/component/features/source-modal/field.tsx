import { useState } from "react";
import { LuX } from "@/component/core/icons";
import { usePatch } from "@/hook/use-patch";
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

export function SourceModal({
    mode,
    onClose,
    onSaved,
    onNotify,
}: SourceModalProps) {
    const editing = mode.type === "edit" ? mode.source : null;
    const [
        form,
        patchForm,
    ] = usePatch({
        name: editing?.name ?? "",
        kind: editing?.kind ?? "torznab",
        address: editing?.baseUrl ?? "",
        apiKey: "",
    });
    // Blank means "keep the stored key" until the field is touched; a
    // touched-but-blank field clears it. The real key is never shown.
    const [
        keyTouched,
        setKeyTouched,
    ] = useState(false);
    const [
        test,
        setTest,
    ] = useState<DialogTestState>(emptyDialogTest);
    const [
        saving,
        setSaving,
    ] = useState(false);

    const title = mode.type === "add" ? "Add source" : "Edit source";
    const ready =
        form.address.trim() !== "" &&
        (mode.type === "edit" || form.name.trim() !== "") &&
        !saving &&
        !test.testing;

    const runTest = async () => {
        setTest((held) => ({ ...held, testing: true, failure: null }));
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
            setTest(dialogTestResult(result));
        } catch (err) {
            setTest(dialogTestFailed(errorMessage(err)));
            onNotify("error", errorMessage(err));
        }
    };

    const runSave = async () => {
        setSaving(true);
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
                          ...(editing?.kind === "torznab" && keyTouched
                              ? { apiKey: form.apiKey }
                              : {}),
                      });
            await onSaved(saved);
        } catch (err) {
            onNotify("error", errorMessage(err));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div
            className="source-modal-overlay"
            role="dialog"
            aria-modal="true"
            aria-label={title}
        >
            <div className="source-modal-panel">
                <div className="source-modal-header">
                    <h2 className="source-modal-title">{title}</h2>
                    <button
                        type="button"
                        className="source-modal-close"
                        aria-label={`Close ${title.toLowerCase()}`}
                        onClick={onClose}
                    >
                        <LuX width="18" height="18" aria-hidden="true" />
                    </button>
                </div>

                <div className="source-modal-body">
                    {mode.type === "add" ? (
                        <>
                            <label className="source-modal-field">
                                <span className="source-modal-label">Name</span>
                                <input
                                    className="source-modal-input"
                                    value={form.name}
                                    onChange={({ currentTarget: el }) =>
                                        patchForm({ name: el.value })
                                    }
                                    placeholder="prowlarr"
                                    autoComplete="off"
                                    spellCheck={false}
                                />
                            </label>
                            <label className="source-modal-field">
                                <span className="source-modal-label">Kind</span>
                                <select
                                    className="source-modal-input"
                                    value={form.kind}
                                    onChange={({ currentTarget: el }) =>
                                        patchForm({ kind: el.value })
                                    }
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
                        <div className="source-modal-readonly">
                            <div className="source-modal-name">
                                {editing?.name}{" "}
                                <span className="source-modal-badge">
                                    {editing?.kind}
                                </span>
                            </div>
                            <p className="source-modal-note">
                                A source's name and kind can't change — delete
                                it and add another to rename.
                            </p>
                        </div>
                    )}

                    <label className="source-modal-field">
                        <span className="source-modal-label">Address</span>
                        <input
                            className="source-modal-input"
                            value={form.address}
                            onChange={({ currentTarget: el }) =>
                                patchForm({ address: el.value })
                            }
                            placeholder="http://prowlarr:9696/1/api"
                            autoComplete="off"
                            spellCheck={false}
                        />
                    </label>

                    {(mode.type === "add"
                        ? form.kind === "torznab"
                        : editing?.kind === "torznab") && (
                        <label className="source-modal-field">
                            <span className="source-modal-label">
                                API key
                                {mode.type === "edit" &&
                                    (editing?.apiKeyMasked
                                        ? ` (currently ${editing.apiKeyMasked} — leave blank to keep)`
                                        : " (optional)")}
                            </span>
                            <input
                                type="password"
                                className="source-modal-input"
                                value={form.apiKey}
                                onChange={({ currentTarget: el }) => {
                                    patchForm({ apiKey: el.value });
                                    setKeyTouched(true);
                                }}
                                autoComplete="off"
                                spellCheck={false}
                            />
                        </label>
                    )}

                    {(test.result || test.failure) && (
                        <p className="source-modal-test" role="status">
                            {test.failure ?? testLine(test.result)}
                        </p>
                    )}
                </div>

                <div className="source-modal-actions">
                    <button
                        type="button"
                        className="source-modal-btn"
                        disabled={!ready}
                        onClick={runSave}
                    >
                        {saving
                            ? "Saving…"
                            : mode.type === "add"
                              ? "Add source"
                              : "Save"}
                    </button>
                    <button
                        type="button"
                        className="source-modal-btn"
                        onClick={onClose}
                    >
                        Cancel
                    </button>
                    <button
                        type="button"
                        className="source-modal-btn source-modal-test-btn"
                        disabled={!ready}
                        onClick={runTest}
                    >
                        {test.testing ? "Testing…" : "Test"}
                    </button>
                </div>
            </div>
        </div>
    );
}
