import { component$ } from "@qwik.dev/core";
import { LuX } from "@/component/core/icons";
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
}

export const SettingsModal = component$<SettingsModalProps>(
    ({ open, prefs, sort, server, onClose, onPrefsChange, onSortChange }) => {
        if (!open) return null;

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
