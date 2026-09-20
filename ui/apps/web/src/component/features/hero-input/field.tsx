import {
    component$,
    $,
    useStore,
    useSignal,
    useVisibleTask$,
} from "@qwik.dev/core";
import {
    LuLink,
    LuDownload,
    LuMagnet,
    SiYoutube,
    LuGlobe,
    LuFile,
    LuFilm,
    LuPlay,
    LuLoader2,
    LuAlertCircle,
} from "@/component/core/icons";
import { detectKind, isPlayableKind } from "./kind";
import type { InputKind } from "./kind";
import { PRESET_OPTIONS } from "@/lib/prefs";
import "./field.css";

export type { InputKind };
export { detectKind, isPlayableKind };

export interface HeroInputProps {
    /** What a YouTube link starts on, from the person's preferences. */
    defaultPreset: string;
    onSubmit: (input: {
        type: "magnet" | "url" | "file";
        value: string;
        preset?: string;
    }) => void | Promise<void>;
    onPlay?: (value: string, kind: string) => void | Promise<void>;
}

function magnetName(value: string): string {
    const dn = new URLSearchParams(value.split("?")[1] || "").get("dn");
    return dn || value;
}

export const HeroInput = component$<HeroInputProps>(
    ({ defaultPreset, onSubmit, onPlay }) => {
        const inputRef = useSignal<HTMLInputElement>();
        /**
         * The quality picked in this session, or null to follow the
         * preference. A store initialised from the prop captured "best"
         * before preferences had been read out of storage and never caught
         * up; read at render time it simply follows.
         */
        const chosenPreset = useSignal<string | null>(null);
        const store = useStore({
            value: "",
            kind: "auto" as InputKind,
            isDragging: false,
            isLoading: false,
            error: "" as string,
        });

        useVisibleTask$(({ track }) => {
            track(() => inputRef.value);
            if (inputRef.value) {
                inputRef.value.focus();
            }
        });

        const updateValue = $((value: string) => {
            store.value = value;
            if (store.kind === "auto") {
                // nothing
            }
            store.error = "";
        });

        const activeKind =
            store.kind === "auto" ? detectKind(store.value) : store.kind;

        const handleSubmit = $(async () => {
            const value = store.value.trim();
            if (!value) {
                inputRef.value?.focus();
                return;
            }

            // Recomputed here rather than closing over the outer `activeKind`:
            // that value is captured once when this handler's QRL is created —
            // typically on the very first render, while the input is still
            // empty — and never refreshes as the user types. Reading `store.kind`
            // and `store.value` directly keeps this in step with what is on
            // screen right now.
            const kindNow =
                store.kind === "auto" ? detectKind(store.value) : store.kind;

            let type: "magnet" | "url" | "file" = "url";
            if (kindNow === "magnet") type = "magnet";
            if (kindNow === "torrent") type = "url";
            if (kindNow === "youtube") type = "url";

            store.isLoading = true;
            store.error = "";
            try {
                await onSubmit({
                    type,
                    value,
                    preset:
                        kindNow === "youtube"
                            ? (chosenPreset.value ?? defaultPreset)
                            : undefined,
                });
                store.value = "";
            } catch (err) {
                store.error =
                    err instanceof Error
                        ? err.message
                        : "Failed to start download";
            } finally {
                store.isLoading = false;
            }
        });

        const handleFile = $((file: File) => {
            if (!file.name.endsWith(".torrent")) {
                store.error = "Please drop a .torrent file";
                return;
            }

            const reader = new FileReader();
            reader.onload = async () => {
                const result = reader.result as string;
                const base64 = result?.split(",")[1] || "";
                store.isLoading = true;
                store.error = "";
                try {
                    await onSubmit({ type: "file", value: base64 });
                } catch (err) {
                    store.error =
                        err instanceof Error
                            ? err.message
                            : "Failed to start torrent";
                } finally {
                    store.isLoading = false;
                }
            };
            reader.readAsDataURL(file);
        });

        return (
            <section
                class={`hero-input ${store.isDragging ? "hero-input--drag" : ""}`}
                aria-label="Start a download"
            >
                <div class="hero-input-header">
                    <h1 class="hero-input-title">Download anything</h1>
                    <p class="hero-input-subtitle">
                        Paste a link, magnet URI, or drop a .torrent file.
                    </p>
                </div>

                <div class="hero-input-bar">
                    <div class="hero-input-icon">
                        {activeKind === "youtube" ? (
                            <SiYoutube
                                width="20"
                                height="20"
                                aria-hidden="true"
                            />
                        ) : activeKind === "magnet" ? (
                            <LuMagnet
                                width="20"
                                height="20"
                                aria-hidden="true"
                            />
                        ) : activeKind === "torrent" ? (
                            <LuFile width="20" height="20" aria-hidden="true" />
                        ) : activeKind === "media" ? (
                            <LuFilm width="20" height="20" aria-hidden="true" />
                        ) : activeKind === "url" ? (
                            <LuGlobe
                                width="20"
                                height="20"
                                aria-hidden="true"
                            />
                        ) : (
                            <LuLink width="20" height="20" aria-hidden="true" />
                        )}
                    </div>

                    <input
                        ref={inputRef}
                        type="text"
                        class="hero-input-field"
                        placeholder="youtube.com/watch?v=... or magnet:?xt=..."
                        value={store.value}
                        disabled={store.isLoading}
                        onInput$={(e: Event) => {
                            updateValue((e.target as HTMLInputElement).value);
                        }}
                        onKeyDown$={(e: KeyboardEvent) => {
                            if (e.key === "Enter") handleSubmit();
                        }}
                        onDragOver$={(e: DragEvent) => {
                            e.preventDefault();
                            store.isDragging = true;
                        }}
                        onDragLeave$={() => {
                            store.isDragging = false;
                        }}
                        onDrop$={(e: DragEvent) => {
                            e.preventDefault();
                            store.isDragging = false;
                            const file = e.dataTransfer?.files[0];
                            if (file) handleFile(file);
                        }}
                        aria-label="Download link or magnet URI"
                    />

                    {activeKind === "youtube" && (
                        <select
                            class="hero-input-select"
                            onChange$={(e: Event) => {
                                chosenPreset.value = (
                                    e.target as HTMLSelectElement
                                ).value;
                            }}
                            aria-label="Video quality"
                        >
                            {/* `selected` on the option rather than `value`
                                on the select: the select's value is applied
                                before its options exist, so it silently falls
                                back to the first one. */}
                            {PRESET_OPTIONS.map((option) => (
                                <option
                                    key={option.value}
                                    value={option.value}
                                    selected={
                                        option.value ===
                                        (chosenPreset.value ?? defaultPreset)
                                    }
                                >
                                    {option.label}
                                </option>
                            ))}
                        </select>
                    )}

                    <div class="hero-input-divider" aria-hidden="true" />

                    <div class="hero-input-actions">
                        {isPlayableKind(activeKind) && (
                            <button
                                type="button"
                                class="hero-input-play"
                                disabled={
                                    activeKind === "youtube" || store.isLoading
                                }
                                title={
                                    activeKind === "youtube"
                                        ? "Streaming coming soon"
                                        : undefined
                                }
                                aria-disabled={activeKind === "youtube"}
                                onClick$={
                                    activeKind === "youtube"
                                        ? undefined
                                        : $(() =>
                                              onPlay?.(
                                                  store.value.trim(),
                                                  activeKind,
                                              ),
                                          )
                                }
                            >
                                <LuPlay
                                    width="18"
                                    height="18"
                                    aria-hidden="true"
                                />
                                <span class="hero-input-submit-text">Play</span>
                            </button>
                        )}

                        <button
                            type="button"
                            class="hero-input-submit"
                            disabled={!store.value.trim() || store.isLoading}
                            onClick$={handleSubmit}
                        >
                            {store.isLoading ? (
                                <LuLoader2
                                    width="18"
                                    height="18"
                                    class="hero-input-spin"
                                    aria-hidden="true"
                                />
                            ) : (
                                <LuDownload
                                    width="18"
                                    height="18"
                                    aria-hidden="true"
                                />
                            )}
                            <span class="hero-input-submit-text">
                                {store.isLoading ? "Adding…" : "Download"}
                            </span>
                        </button>
                    </div>
                </div>

                <div
                    class="hero-input-chips"
                    role="group"
                    aria-label="Download type"
                >
                    {[
                        { id: "auto" as InputKind, label: "Auto" },
                        { id: "youtube" as InputKind, label: "YouTube" },
                        { id: "magnet" as InputKind, label: "Magnet" },
                        { id: "url" as InputKind, label: "URL" },
                        { id: "torrent" as InputKind, label: ".torrent" },
                        { id: "media" as InputKind, label: "Media" },
                    ].map((chip) => {
                        const resolved =
                            store.kind === "auto"
                                ? chip.id === "auto"
                                : store.kind === chip.id;
                        const detected =
                            store.kind === "auto" &&
                            chip.id !== "auto" &&
                            activeKind === chip.id;
                        return (
                            <button
                                key={chip.id}
                                type="button"
                                class={`hero-input-chip ${resolved ? "hero-input-chip--active" : ""} ${detected ? "hero-input-chip--detected" : ""}`}
                                onClick$={() => {
                                    store.kind = chip.id;
                                }}
                                aria-pressed={resolved}
                            >
                                {chip.label}
                            </button>
                        );
                    })}
                </div>

                {store.error && (
                    <div class="hero-input-error" role="alert">
                        <LuAlertCircle
                            width="14"
                            height="14"
                            aria-hidden="true"
                        />
                        <span>{store.error}</span>
                    </div>
                )}
            </section>
        );
    },
);

export { magnetName };
