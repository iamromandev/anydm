import { LuX } from "@/component/core/icons";
import { cx } from "@/lib/cx";
import type { CategoryItem } from "@/lib/api/category";
import { CategoryManager } from "@/component/features/category-manager";
import { AUDIO_LANGUAGES } from "@/lib/audio";
import { PRESET_OPTIONS, type Prefs, type Preset } from "@/lib/prefs";
import { SORT_OPTIONS, type SortValue } from "@/lib/sort";
import { serverLabel, serverValue } from "./server-value";
import "./field.css";

import type { ServerSettings } from "@/lib/api/server-settings";
export type { ServerSettings };

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
    /** Every category, for the Categories section. */
    categories: CategoryItem[];
    onCategoryCreate: (name: string, folder: string) => Promise<boolean>;
    onCategoryUpdate: (
        id: string,
        patch: { name?: string; folder?: string },
    ) => Promise<boolean>;
    onCategoryOrder: (ids: string[]) => Promise<void>;
    /** Asks first; the shell owns the confirmation. */
    onCategoryDelete: (category: CategoryItem) => void;
}

export function SettingsModal({
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
    categories,
    onCategoryCreate,
    onCategoryUpdate,
    onCategoryOrder,
    onCategoryDelete,
}: SettingsModalProps) {
    if (!open) return null;

    // First when the API has just refused a request, since that is the
    // one thing to do; otherwise after the preferences it rarely matters
    // next to.
    const access = (
        <section className="settings-section">
            <h3 className="settings-section-title">API key</h3>
            <p
                className={cx(
                    "settings-section-note",
                    apiKeyMessage && "settings-section-note--alert",
                )}
                role={apiKeyMessage ? "alert" : undefined}
            >
                {apiKeyMessage ??
                    "Only needed when the API sets API_KEY. Kept in this browser; save it blank to remove it."}
            </p>
            <form
                className="settings-key"
                onSubmit={(event) => {
                    event.preventDefault();
                    onApiKeySave(
                        String(
                            new FormData(event.currentTarget).get("apiKey") ??
                                "",
                        ),
                    );
                }}
            >
                <input
                    type="password"
                    name="apiKey"
                    className="settings-input"
                    aria-label="API key"
                    autoComplete="off"
                    spellCheck={false}
                    defaultValue={apiKey}
                />
                <button type="submit" className="settings-key-save">
                    Save
                </button>
            </form>
        </section>
    );

    return (
        <div
            className="settings-overlay"
            role="dialog"
            aria-modal="true"
            aria-label="Settings"
        >
            <div className="settings-panel">
                <div className="settings-header">
                    <h2 className="settings-title">Settings</h2>
                    <button
                        type="button"
                        className="settings-close"
                        aria-label="Close settings"
                        onClick={onClose}
                    >
                        <LuX width="18" height="18" aria-hidden="true" />
                    </button>
                </div>

                <div className="settings-body">
                    {apiKeyMessage && access}
                    <section className="settings-section">
                        <h3 className="settings-section-title">Preferences</h3>
                        <p className="settings-section-note">
                            Kept in this browser.
                        </p>

                        <label className="settings-row">
                            <span className="settings-label">
                                Default quality
                            </span>
                            <select
                                className="settings-select"
                                value={prefs.defaultPreset}
                                onChange={({ currentTarget: el }) =>
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
                                    >
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="settings-row">
                            <span className="settings-label">
                                Audio language
                            </span>
                            <select
                                className="settings-select"
                                value={prefs.audioLanguage}
                                onChange={({ currentTarget: el }) =>
                                    onPrefsChange({
                                        ...prefs,
                                        audioLanguage: el.value,
                                    })
                                }
                            >
                                <option value="">As the file marks it</option>
                                {AUDIO_LANGUAGES.map((option) => (
                                    <option
                                        key={option.value}
                                        value={option.value}
                                    >
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="settings-row">
                            <span className="settings-label">Subtitles</span>
                            <select
                                className="settings-select"
                                value={prefs.subtitleLanguage}
                                onChange={({ currentTarget: el }) =>
                                    onPrefsChange({
                                        ...prefs,
                                        subtitleLanguage: el.value,
                                    })
                                }
                            >
                                <option value="">Off</option>
                                {AUDIO_LANGUAGES.map((option) => (
                                    <option
                                        key={option.value}
                                        value={option.value}
                                    >
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="settings-row">
                            <span className="settings-label">
                                Sort the list by
                            </span>
                            <select
                                className="settings-select"
                                value={sort}
                                onChange={({ currentTarget: el }) =>
                                    onSortChange(el.value as SortValue)
                                }
                            >
                                {SORT_OPTIONS.map((option) => (
                                    <option
                                        key={option.value}
                                        value={option.value}
                                    >
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="settings-row">
                            <span className="settings-label">
                                Ask before removing a download
                            </span>
                            <input
                                type="checkbox"
                                className="settings-check"
                                checked={prefs.confirmBeforeRemove}
                                onChange={({ currentTarget: el }) =>
                                    onPrefsChange({
                                        ...prefs,
                                        confirmBeforeRemove: el.checked,
                                    })
                                }
                            />
                        </label>
                    </section>

                    {!apiKeyMessage && access}

                    <CategoryManager
                        categories={categories}
                        onCreate={onCategoryCreate}
                        onUpdate={onCategoryUpdate}
                        onOrder={onCategoryOrder}
                        onDelete={onCategoryDelete}
                    />

                    <section className="settings-section">
                        <h3 className="settings-section-title">Server</h3>
                        <p className="settings-section-note">
                            Read-only. These come from <code>api/.env</code> and
                            take effect when the API restarts.
                        </p>

                        {server === null ? (
                            <p className="settings-server-empty">
                                Could not read the server's settings.
                            </p>
                        ) : (
                            <dl className="settings-server">
                                {Object.entries(server).map(
                                    ([
                                        key,
                                        value,
                                    ]) => (
                                        <div
                                            key={key}
                                            className="settings-server-row"
                                        >
                                            <dt className="settings-server-key">
                                                {serverLabel(key)}
                                            </dt>
                                            <dd className="settings-server-value">
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
}
