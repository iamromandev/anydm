import { component$, type PropsOf } from "@qwik.dev/core";
import "./field.css";

type UrlInputProps = PropsOf<"input"> & {
    value?: string;
    onChange$: (value?: string) => void;
    onKeyPress$?: (e: KeyboardEvent) => void;
    error?: string;
};

export const UrlInput = component$<UrlInputProps>(
    ({ id, name, value, onChange$, onKeyPress$, error, ...props }) => {
        const inputId = id || name;

        return (
            <>
                <div class="container">
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
                </div>
                {error && <div class="error">{error}</div>}
            </>
        );
    },
);
