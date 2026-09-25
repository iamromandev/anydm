import { component$ } from "@qwik.dev/core";
import { LuX } from "@/component/core/icons";
import { AUDIO_LANGUAGES } from "@/lib/audio";
import { PRESET_OPTIONS, type Prefs, type Preset } from "@/lib/prefs";
import { SORT_OPTIONS, type SortValue } from "@/lib/sort";
import { serverLabel, serverValue } from "./server-value";
import "./field.css";

/** The server's own configuration, as the API reports it. */
export type ServerSettings = Record<string, string | number | boolean>;

export interface SettingsModalProps {
    open: boolean;
    prefs: Prefs;
    sort: SortValue;
    /** Null until the API answers, and after it fails. */
    server: ServerSettings | null;
    onClose: () => void;
    onPrefsChange: (prefs: Prefs) => void;
    onSortChange: (sort: SortValue) => void;
    /** What this browser sends as `X-API-Key`; empty when nothing is stored. */
    apiKey: string;
    /** Why the key is being asked for. Set when the API has answered 401. */
    apiKeyMessage: string | null;
    onApiKeySave: (key: string) => void;
}

export const SettingsModal = component$<SettingsModalProps>(
    ({
        open,
        prefs,
        sort,
        server,
        onClose,
        onPrefsChange,
        onSortChange,
        apiKey,
        apiKeyMessage,
        onApiKeySave,
    }) => {
        if (!open) return null;

        // First when the API has just refused a request, since that is the
        // one thing to do; otherwise after the preferences it rarely matters
        // next to.
        const access = (
            <section class="settings-section">
                <h3 class="settings-section-title">API key</h3>
                <p
                    class={[
                        "settings-section-note",
                        apiKeyMessage && "settings-section-note--alert",
                    ]}
                    role={apiKeyMessage ? "alert" : undefined}
                >
                    {apiKeyMessage ??
                        "Only needed when the API sets API_KEY. Kept in this browser; save it blank to remove it."}
                </p>
                <form
                    class="settings-key"
                    preventdefault:submit
                    onSubmit$={(_, form) =>
                        onApiKeySave(
                            String(new FormData(form).get("apiKey") ?? ""),
                        )
                    }
                >
                    <input
                        type="password"
                        name="apiKey"
                        class="settings-input"
                        aria-label="API key"
                        autocomplete="off"
                        spellcheck={false}
                        value={apiKey}
                    />
                    <button type="submit" class="settings-key-save">
                        Save
                    </button>
                </form>
            </section>
        );

        return (
            <div
                class="settings-overlay"
                role="dialog"
                aria-modal="true"
                aria-label="Settings"
            >
                <div class="settings-panel">
                    <div class="settings-header">
                        <h2 class="settings-title">Settings</h2>
                        <button
                            type="button"
                            class="settings-close"
                            aria-label="Close settings"
                            onClick$={onClose}
                        >
                            <LuX width="18" height="18" aria-hidden="true" />
                        </button>
                    </div>

                    <div class="settings-body">
                        {apiKeyMessage && access}
                        <section class="settings-section">
                            <h3 class="settings-section-title">Preferences</h3>
                            <p class="settings-section-note">
                                Kept in this browser.
                            </p>

                            <label class="settings-row">
                                <span class="settings-label">
                                    Default quality
                                </span>
                                <select
                                    class="settings-select"
                                    onChange$={(_, el) =>
                                        onPrefsChange({
                                            ...prefs,
                                            defaultPreset: el.value as Preset,
                                        })
                                    }
                                >
                                    {PRESET_OPTIONS.map((option) => (
                                        <option
                                            key={option.value}
                                            value={option.value}
                                            selected={
                                                option.value ===
                                                prefs.defaultPreset
                                            }
                                        >
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <label class="settings-row">
                                <span class="settings-label">
                                    Audio language
                                </span>
                                <select
                                    class="settings-select"
                                    onChange$={(_, el) =>
                                        onPrefsChange({
                                            ...prefs,
                                            audioLanguage: el.value,
                                        })
                                    }
                                >
                                    <option
                                        value=""
                                        selected={prefs.audioLanguage === ""}
                                    >
                                        As the file marks it
                                    </option>
                                    {AUDIO_LANGUAGES.map((option) => (
                                        <option
                                            key={option.value}
                                            value={option.value}
                                            selected={
                                                option.value ===
                                                prefs.audioLanguage
                                            }
                                        >
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <label class="settings-row">
                                <span class="settings-label">
                                    Sort the list by
                                </span>
                                <select
                                    class="settings-select"
                                    onChange$={(_, el) =>
                                        onSortChange(el.value as SortValue)
                                    }
                                >
                                    {SORT_OPTIONS.map((option) => (
                                        <option
                                            key={option.value}
                                            value={option.value}
                                            selected={option.value === sort}
                                        >
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <label class="settings-row">
                                <span class="settings-label">
                                    Ask before removing a download
                                </span>
                                <input
                                    type="checkbox"
                                    class="settings-check"
                                    checked={prefs.confirmBeforeRemove}
                                    onChange$={(_, el) =>
                                        onPrefsChange({
                                            ...prefs,
                                            confirmBeforeRemove: el.checked,
                                        })
                                    }
                                />
                            </label>
                        </section>

                        {!apiKeyMessage && access}

                        <section class="settings-section">
                            <h3 class="settings-section-title">Server</h3>
                            <p class="settings-section-note">
                                Read-only. These come from <code>api/.env</code>{" "}
                                and take effect when the API restarts.
                            </p>

                            {server === null ? (
                                <p class="settings-server-empty">
                                    Could not read the server's settings.
                                </p>
                            ) : (
                                <dl class="settings-server">
                                    {Object.entries(server).map(
                                        ([
                                            key,
                                            value,
                                        ]) => (
                                            <div
                                                key={key}
                                                class="settings-server-row"
                                            >
                                                <dt class="settings-server-key">
                                                    {serverLabel(key)}
                                                </dt>
                                                <dd class="settings-server-value">
                                                    {serverValue(key, value)}
                                                </dd>
                                            </div>
                                        ),
                                    )}
                                </dl>
                            )}
                        </section>
                    </div>
                </div>
            </div>
        );
    },
);
