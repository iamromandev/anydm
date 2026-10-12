import { useState } from "react";
import { ThemeToggle } from "@/component/shared/theme-toggle";
import { SORT_OPTIONS, type SortValue } from "@/lib/sort";
import {
    LuPlus,
    LuSettings,
    LuMenu,
    LuSearch,
    LuX,
} from "@/component/core/icons";
import "./field.css";

export interface TopToolbarProps {
    searchQuery: string;
    onSearchChange: (query: string) => void;
    sort: SortValue;
    onSortChange: (sort: SortValue) => void;
    onAddClick: () => void;
    /** Open the dialog for adding many links at once. */
    onAddManyClick: () => void;
    onSettingsClick: () => void;
    sidebarOpen: boolean;
    onSidebarToggle: () => void;
}

export function TopToolbar({
    searchQuery,
    onSearchChange,
    sort,
    onSortChange,
    onAddClick,
    onAddManyClick,
    onSettingsClick,
    sidebarOpen,
    onSidebarToggle,
}: TopToolbarProps) {
    const [
        addMenuOpen,
        setAddMenuOpen,
    ] = useState(false);
    // Only meaningful on a narrow screen, where the field is folded behind
    // its own button; above that it is always shown.
    const [
        searchOpen,
        setSearchOpen,
    ] = useState(false);

    return (
        <header className="top-toolbar" role="banner">
            <div className="toolbar-left">
                <button
                    type="button"
                    className="toolbar-btn toolbar-btn--icon"
                    onClick={onSidebarToggle}
                    aria-label={sidebarOpen ? "Close sidebar" : "Open sidebar"}
                    aria-expanded={sidebarOpen}
                >
                    <LuMenu width="18" height="18" aria-hidden="true" />
                </button>

                <a href="/" className="toolbar-logo">
                    <span className="toolbar-logo-mark">A</span>
                    <span className="toolbar-logo-text">AnyDM</span>
                </a>
            </div>

            <div className="toolbar-right">
                <div
                    className={`toolbar-search ${searchOpen ? "toolbar-search--open" : ""}`}
                >
                    <button
                        type="button"
                        className="toolbar-btn toolbar-btn--icon toolbar-search-toggle"
                        aria-label="Search downloads"
                        aria-expanded={searchOpen}
                        onClick={() => setSearchOpen((open) => !open)}
                    >
                        <LuSearch width="18" height="18" aria-hidden="true" />
                    </button>

                    <input
                        type="search"
                        className="toolbar-search-field"
                        placeholder="Search downloads"
                        aria-label="Search downloads"
                        aria-controls="download-list"
                        value={searchQuery}
                        onChange={({ currentTarget: el }) =>
                            onSearchChange(el.value)
                        }
                        onKeyDown={(event) => {
                            if (event.key !== "Escape") return;
                            const el = event.currentTarget;
                            // Escape clears rather than closing, because a
                            // filtered list with no visible term is a
                            // puzzle nobody asked for.
                            el.value = "";
                            onSearchChange("");
                            el.blur();
                        }}
                    />

                    {searchQuery && (
                        <button
                            type="button"
                            className="toolbar-search-clear"
                            aria-label="Clear search"
                            onClick={() => onSearchChange("")}
                        >
                            <LuX width="14" height="14" aria-hidden="true" />
                        </button>
                    )}
                </div>

                <label className="toolbar-sort">
                    <span className="toolbar-sort-label">Sort</span>
                    <select
                        className="toolbar-sort-select"
                        value={sort}
                        onChange={({ currentTarget: el }) =>
                            onSortChange(el.value as SortValue)
                        }
                    >
                        {SORT_OPTIONS.map((option) => (
                            <option key={option.value} value={option.value}>
                                {option.label}
                            </option>
                        ))}
                    </select>
                </label>

                <div className="toolbar-dropdown">
                    <button
                        type="button"
                        className="toolbar-btn toolbar-btn--primary"
                        onClick={() => setAddMenuOpen((open) => !open)}
                        aria-expanded={addMenuOpen}
                        aria-haspopup="true"
                        aria-label="Add download"
                    >
                        <LuPlus width="16" height="16" aria-hidden="true" />
                        <span className="toolbar-btn-text">New</span>
                    </button>
                    {addMenuOpen && (
                        <div
                            className="toolbar-dropdown-menu"
                            role="menu"
                            onClick={(event) => {
                                setAddMenuOpen(false);
                                // Every item but Add many opens the add dialog.
                                const many = (event.target as Element).closest(
                                    "[data-add-many]",
                                );
                                if (many) onAddManyClick();
                                else onAddClick();
                            }}
                        >
                            <button
                                type="button"
                                className="dropdown-item"
                                role="menuitem"
                            >
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    aria-hidden="true"
                                >
                                    <path d="M6 9l6 6 6-6" />
                                </svg>
                                <span>Magnet Link</span>
                            </button>
                            <button
                                type="button"
                                className="dropdown-item"
                                role="menuitem"
                            >
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    aria-hidden="true"
                                >
                                    <rect
                                        x="3"
                                        y="3"
                                        width="18"
                                        height="18"
                                        rx="2"
                                        ry="2"
                                    />
                                    <polyline points="17 8 21 12 17 16" />
                                </svg>
                                <span>.torrent File</span>
                            </button>
                            <button
                                type="button"
                                className="dropdown-item"
                                role="menuitem"
                            >
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    aria-hidden="true"
                                >
                                    <circle cx="12" cy="12" r="10" />
                                    <line x1="2" y1="12" x2="22" y2="12" />
                                    <path d="M12 2a20 20 0 0 1 4 10 20 20 0 0 1-4 10 20 20 0 0 1-4-10 20 20 0 0 1 4-10z" />
                                </svg>
                                <span>Link (any site or file)</span>
                            </button>
                            <button
                                type="button"
                                className="dropdown-item"
                                role="menuitem"
                                data-add-many
                            >
                                <svg
                                    width="16"
                                    height="16"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    aria-hidden="true"
                                >
                                    <line x1="8" y1="6" x2="21" y2="6" />
                                    <line x1="8" y1="12" x2="21" y2="12" />
                                    <line x1="8" y1="18" x2="21" y2="18" />
                                    <line x1="3" y1="6" x2="3.01" y2="6" />
                                    <line x1="3" y1="12" x2="3.01" y2="12" />
                                    <line x1="3" y1="18" x2="3.01" y2="18" />
                                </svg>
                                <span>Add many…</span>
                            </button>
                        </div>
                    )}
                </div>

                <button
                    type="button"
                    className="toolbar-btn toolbar-btn--icon"
                    onClick={onSettingsClick}
                    aria-label="Settings"
                >
                    <LuSettings width="18" height="18" aria-hidden="true" />
                </button>

                <ThemeToggle />
            </div>
        </header>
    );
}
