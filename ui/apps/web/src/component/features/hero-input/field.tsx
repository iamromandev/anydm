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
    LuMonitorPlay,
    LuPlay,
    LuLoader2,
    LuAlertCircle,
} from "@/component/core/icons";
import { formatTime } from "@/component/core/utils";
import {
    choosePreset,
    lookupLink,
    type AddType,
    type SitePreview,
} from "@/lib/api/site";
import { detectKind, isPlayableKind } from "./kind";
import type { InputKind } from "./kind";
import { PRESET_OPTIONS } from "@/lib/prefs";
import "./field.css";

export type { InputKind };
export { detectKind, isPlayableKind };

/** How long typing has to pause before a page link is looked up. */
const LOOKUP_DELAY_MS = 600;

export interface HeroInputProps {
    /** What a site link starts on, from the person's preferences. */
    defaultPreset: string;
    onSubmit: (input: {
        type: AddType;
        value: string;
        preset?: string;
    }) => void | Promise<void>;
    onPlay?: (value: string, kind: string) => void | Promise<void>;
}

type LookupStatus = "idle" | "looking" | "site" | "file" | "error";

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
            /** What the API said about the site link in the box, if anything. */
            lookup: "idle" as LookupStatus,
            preview: null as SitePreview | null,
        });

        useVisibleTask$(({ track }) => {
            track(() => inputRef.value);
            if (inputRef.value) {
                inputRef.value.focus();
            }
        });

        /**
         * Ask the API about a site link once typing pauses.
         *
         * An answer for a value that has since changed is dropped, so a slow
         * lookup can never paint the preview of a link no longer in the box.
         * ``document-ready`` because the add box can sit outside the viewport,
         * where the default strategy would never run this.
         */
        useVisibleTask$(
            ({ track, cleanup }) => {
                const value = track(() => store.value).trim();
                const kind = track(() => store.kind);
                const effective = kind === "auto" ? detectKind(value) : kind;

                chosenPreset.value = null;
                if (!value || effective !== "site") {
                    store.lookup = "idle";
                    store.preview = null;
                    return;
                }

                store.lookup = "looking";
                store.preview = null;
                const timer = setTimeout(async () => {
                    const current = () => store.value.trim() === value;
                    try {
                        const found = await lookupLink(value);
                        if (!current()) return;
                        store.preview =
                            found.kind === "site" ? found.preview : null;
                        store.lookup = found.kind;
                    } catch (err) {
                        if (!current()) return;
                        store.lookup = "error";
                        store.error =
                            err instanceof Error
                                ? err.message
                                : "Could not look this link up";
                    }
                }, LOOKUP_DELAY_MS);
                cleanup(() => clearTimeout(timer));
            },
            { strategy: "document-ready" },
        );

        const updateValue = $((value: string) => {
            store.value = value;
            store.error = "";
        });

        const activeKind =
            store.kind === "auto" ? detectKind(store.value) : store.kind;
        const offered = store.preview?.presets ?? [];
        const preset =
            chosenPreset.value ?? choosePreset(offered, defaultPreset) ?? "";
        const siteBlocked =
            activeKind === "site" &&
            (store.lookup === "looking" || store.lookup === "error");

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

            let input: { type: AddType; value: string; preset?: string };
            if (kindNow === "magnet") {
                input = { type: "magnet", value };
            } else if (kindNow === "site") {
                // Download stays disabled until the lookup has answered, so
                // this is either a previewed page or a file no site claims.
                if (store.lookup === "site" && store.preview) {
                    const picked =
                        chosenPreset.value ??
                        choosePreset(store.preview.presets, defaultPreset);
                    if (!picked) {
                        store.error =
                            "Nothing on this page can be downloaded yet";
                        return;
                    }
                    input = { type: "site", value, preset: picked };
                } else if (store.lookup === "file") {
                    input = { type: "url", value };
                } else {
                    return;
                }
            } else {
                input = { type: "url", value };
            }

            store.isLoading = true;
            store.error = "";
            try {
                await onSubmit(input);
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

        const isYoutube = store.preview?.extractor === "Youtube";

        return (
            <section
                class={`hero-input ${store.isDragging ? "hero-input--drag" : ""}`}
                aria-label="Start a download"
            >
                <div class="hero-input-header">
                    <h1 class="hero-input-title">Download anything</h1>
                    <p class="hero-input-subtitle">
                        Paste a video page, a file link, a magnet URI, or drop a
                        .torrent file.
                    </p>
                </div>

                <div class="hero-input-bar">
                    <div class="hero-input-icon">
                        {activeKind === "site" && isYoutube ? (
                            <SiYoutube
                                width="20"
                                height="20"
                                aria-hidden="true"
                            />
                        ) : activeKind === "site" ? (
                            <LuMonitorPlay
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
                        placeholder="youtube.com/watch?v=..., vimeo.com/..., or magnet:?xt=..."
                        value={store.value}
                        disabled={store.isLoading}
                        onInput$={(e: Event) => {
                            updateValue((e.target as HTMLInputElement).value);
                        }}
                        onKeyDown$={(e: KeyboardEvent) => {
                            if (e.key === "Enter" && !siteBlocked)
                                handleSubmit();
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

                    {activeKind === "site" && store.lookup === "site" && (
                        <select
                            class="hero-input-select"
                            onChange$={(e: Event) => {
                                chosenPreset.value = (
                                    e.target as HTMLSelectElement
                                ).value;
                            }}
                            aria-label="Quality"
                        >
                            {/* `selected` on the option rather than `value`
                                on the select: the select's value is applied
                                before its options exist, so it silently falls
                                back to the first one. Only what this page
                                offers is listed. */}
                            {PRESET_OPTIONS.filter((option) =>
                                offered.includes(option.value),
                            ).map((option) => (
                                <option
                                    key={option.value}
                                    value={option.value}
                                    selected={option.value === preset}
                                >
                                    {option.label}
                                </option>
                            ))}
                        </select>
                    )}

                    <div class="hero-input-divider" aria-hidden="true" />

                    <div class="hero-input-actions">
                        {(isPlayableKind(activeKind) ||
                            activeKind === "site") && (
                            <button
                                type="button"
                                class="hero-input-play"
                                disabled={
                                    activeKind === "site" || store.isLoading
                                }
                                title={
                                    activeKind === "site"
                                        ? "Playing from sites isn't supported yet"
                                        : undefined
                                }
                                aria-disabled={activeKind === "site"}
                                onClick$={
                                    activeKind === "site"
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
                            disabled={
                                !store.value.trim() ||
                                store.isLoading ||
                                siteBlocked
                            }
                            onClick$={handleSubmit}
                        >
                            {store.isLoading ||
                            (activeKind === "site" &&
                                store.lookup === "looking") ? (
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

                {activeKind === "site" && store.lookup === "looking" && (
                    <div class="hero-input-preview" aria-live="polite">
                        <span class="hero-input-preview-note">Looking up…</span>
                    </div>
                )}

                {activeKind === "site" &&
                    store.lookup === "site" &&
                    store.preview && (
                        <div class="hero-input-preview" aria-live="polite">
                            {store.preview.thumbnail && (
                                <img
                                    class="hero-input-preview-thumb"
                                    src={store.preview.thumbnail}
                                    alt=""
                                    width={96}
                                    height={54}
                                    loading="lazy"
                                    referrerPolicy="no-referrer"
                                />
                            )}
                            <div class="hero-input-preview-text">
                                <span class="hero-input-preview-title">
                                    {store.preview.title}
                                </span>
                                <span class="hero-input-preview-meta">
                                    {[
                                        store.preview.site,
                                        store.preview.uploader,
                                        store.preview.duration > 0
                                            ? formatTime(store.preview.duration)
                                            : "",
                                    ]
                                        .filter(Boolean)
                                        .join(" · ")}
                                </span>
                            </div>
                        </div>
                    )}

                {activeKind === "site" && store.lookup === "file" && (
                    <div class="hero-input-preview" aria-live="polite">
                        <span class="hero-input-preview-note">
                            Not a page on a known site: it will download as a
                            file.
                        </span>
                    </div>
                )}

                <div
                    class="hero-input-chips"
                    role="group"
                    aria-label="Download type"
                >
                    {[
                        { id: "auto" as InputKind, label: "Auto" },
                        { id: "site" as InputKind, label: "Site" },
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
