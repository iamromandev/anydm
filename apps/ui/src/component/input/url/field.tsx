import { component$, $, useSignal, type PropsOf } from "@qwik.dev/core";
import "./field.css";

type UrlInputProps = PropsOf<"input"> & {
    value?: string;
    onChange$: (value?: string) => void;
    onKeyPress$?: (e: KeyboardEvent) => void;
    error?: string;
    onUrlDrop$?: (url: string) => void;
};

export const UrlInput = component$<UrlInputProps>(
    ({
        id,
        name,
        value,
        onChange$,
        onKeyPress$,
        error,
        onUrlDrop$,
        ...props
    }) => {
        const inputId = id || name;
        const isDragOver = useSignal(false);
        const justPasted = useSignal(false);

        const handlePaste = $(async () => {
            try {
                const text = await navigator.clipboard.readText();
                if (text) {
                    onChange$(text);
                    justPasted.value = true;
                    setTimeout(() => {
                        justPasted.value = false;
                    }, 1500);
                }
            } catch {
                // clipboard read denied — silent fail
            }
        });

        const handleDragOver = $((e: DragEvent) => {
            e.preventDefault();
            e.stopPropagation();
            isDragOver.value = true;
        });

        const handleDragLeave = $((e: DragEvent) => {
            e.preventDefault();
            e.stopPropagation();
            isDragOver.value = false;
        });

        const handleDropEvent = $((e: DragEvent) => {
            e.preventDefault();
            e.stopPropagation();
            isDragOver.value = false;

            const text = e.dataTransfer?.getData("text/plain")?.trim();
            if (text) {
                onChange$(text);
                onUrlDrop$?.(text);
            }
        });

        return (
            <>
                <div
                    class={`input-wrapper ${isDragOver.value ? "input-wrapper--dragover" : ""}`}
                    onDragOver$={handleDragOver}
                    onDragLeave$={handleDragLeave}
                    onDrop$={handleDropEvent}
                >
                    <input
                        id={inputId}
                        type="url"
                        value={value}
                        onInput$={(e) =>
                            onChange$((e.target as HTMLInputElement).value)
                        }
                        onKeyDown$={
                            onKeyPress$
                                ? (e) =>
                                      onKeyPress$(e as unknown as KeyboardEvent)
                                : undefined
                        }
                        class="input"
                        aria-invalid={!!error}
                        {...props}
                    />

                    <button
                        type="button"
                        class={`paste-btn ${justPasted.value ? "paste-btn--done" : ""}`}
                        onClick$={handlePaste}
                        title="Paste from clipboard"
                    >
                        {justPasted.value ? (
                            <svg
                                width="16"
                                height="16"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                            >
                                <polyline points="20 6 9 17 4 12" />
                            </svg>
                        ) : (
                            <svg
                                width="16"
                                height="16"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="2"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                            >
                                <rect
                                    x="9"
                                    y="9"
                                    width="13"
                                    height="13"
                                    rx="2"
                                    ry="2"
                                />
                                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                            </svg>
                        )}
                    </button>
                </div>
                {error && <div class="error">{error}</div>}
            </>
        );
    },
);
