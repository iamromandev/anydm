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
    LuLoader2,
    LuAlertCircle,
} from "@/component/core/icons";
import "./field.css";

export type InputKind = "auto" | "magnet" | "youtube" | "url" | "torrent";

export interface HeroInputProps {
    onSubmit: (input: {
        type: "magnet" | "url" | "file";
        value: string;
        preset?: string;
    }) => void | Promise<void>;
}

const YOUTUBE_HOSTS = new Set([
    "youtube.com",
    "www.youtube.com",
    "youtu.be",
    "m.youtube.com",
    "music.youtube.com",
]);

function detectKind(value: string): Exclude<InputKind, "auto"> {
    const trimmed = value.trim();
    if (trimmed.startsWith("magnet:?")) return "magnet";

    try {
        const url = new URL(trimmed);
        if (YOUTUBE_HOSTS.has(url.hostname)) return "youtube";
        if (url.pathname.endsWith(".torrent")) return "torrent";
        return "url";
    } catch {
        return "url";
    }
}

function magnetName(value: string): string {
    const dn = new URLSearchParams(value.split("?")[1] || "").get("dn");
    return dn || value;
}

export const HeroInput = component$<HeroInputProps>(({ onSubmit }) => {
    const inputRef = useSignal<HTMLInputElement>();
    const store = useStore({
        value: "",
        kind: "auto" as InputKind,
        preset: "best" as string,
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

        let type: "magnet" | "url" | "file" = "url";
        if (activeKind === "magnet") type = "magnet";
        if (activeKind === "torrent") type = "url";
        if (activeKind === "youtube") type = "url";

        store.isLoading = true;
        store.error = "";
        try {
            await onSubmit({
                type,
                value,
                preset: activeKind === "youtube" ? store.preset : undefined,
            });
            store.value = "";
        } catch (err) {
            store.error =
                err instanceof Error ? err.message : "Failed to start download";
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
                        <SiYoutube width="20" height="20" aria-hidden="true" />
                    ) : activeKind === "magnet" ? (
                        <LuMagnet width="20" height="20" aria-hidden="true" />
                    ) : activeKind === "torrent" ? (
                        <LuFile width="20" height="20" aria-hidden="true" />
                    ) : activeKind === "url" ? (
                        <LuGlobe width="20" height="20" aria-hidden="true" />
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
                        value={store.preset}
                        onChange$={(e: Event) => {
                            store.preset = (
                                e.target as HTMLSelectElement
                            ).value;
                        }}
                        aria-label="Video quality"
                    >
                        <option value="best">Best</option>
                        <option value="2160">4K</option>
                        <option value="1440">1440p</option>
                        <option value="1080">1080p</option>
                        <option value="720">720p</option>
                        <option value="480">480p</option>
                        <option value="mp3">Audio</option>
                    </select>
                )}

                <div class="hero-input-divider" aria-hidden="true" />

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
                        <LuDownload width="18" height="18" aria-hidden="true" />
                    )}
                    <span class="hero-input-submit-text">
                        {store.isLoading ? "Adding…" : "Download"}
                    </span>
                </button>
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
                    <LuAlertCircle width="14" height="14" aria-hidden="true" />
                    <span>{store.error}</span>
                </div>
            )}
        </section>
    );
});

export { magnetName };
