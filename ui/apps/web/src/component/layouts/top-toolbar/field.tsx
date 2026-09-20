import { component$, $, useStore } from "@qwik.dev/core";
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
    onSettingsClick: () => void;
    sidebarOpen: boolean;
    onSidebarToggle: () => void;
}

export const TopToolbar = component$<TopToolbarProps>(
    ({
        searchQuery,
        onSearchChange,
        sort,
        onSortChange,
        onAddClick,
        onSettingsClick,
        sidebarOpen,
        onSidebarToggle,
    }) => {
        const store = useStore({
            addMenuOpen: false,
            // Only meaningful on a narrow screen, where the field is folded
            // behind its own button; above that it is always shown.
            searchOpen: false,
        });

        return (
            <header class="top-toolbar" role="banner">
                <div class="toolbar-left">
                    <button
                        type="button"
                        class="toolbar-btn toolbar-btn--icon"
                        onClick$={onSidebarToggle}
                        aria-label={
                            sidebarOpen ? "Close sidebar" : "Open sidebar"
                        }
                        aria-expanded={sidebarOpen}
                    >
                        <LuMenu width="18" height="18" aria-hidden="true" />
                    </button>

                    <a href="/" class="toolbar-logo">
                        <span class="toolbar-logo-mark">A</span>
                        <span class="toolbar-logo-text">AnyDM</span>
                    </a>
                </div>

                <div class="toolbar-right">
                    <div
                        class={`toolbar-search ${store.searchOpen ? "toolbar-search--open" : ""}`}
                    >
                        <button
                            type="button"
                            class="toolbar-btn toolbar-btn--icon toolbar-search-toggle"
                            aria-label="Search downloads"
                            aria-expanded={store.searchOpen}
                            onClick$={() => {
                                store.searchOpen = !store.searchOpen;
                            }}
                        >
                            <LuSearch
                                width="18"
                                height="18"
                                aria-hidden="true"
                            />
                        </button>

                        <input
                            type="search"
                            class="toolbar-search-field"
                            placeholder="Search downloads"
                            aria-label="Search downloads"
                            aria-controls="download-list"
                            value={searchQuery}
                            onInput$={(_, el) => onSearchChange(el.value)}
                            onKeyDown$={(event, el) => {
                                if (event.key !== "Escape") return;
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
                                class="toolbar-search-clear"
                                aria-label="Clear search"
                                onClick$={() => onSearchChange("")}
                            >
                                <LuX
                                    width="14"
                                    height="14"
                                    aria-hidden="true"
                                />
                            </button>
                        )}
                    </div>

                    <label class="toolbar-sort">
                        <span class="toolbar-sort-label">Sort</span>
                        <select
                            class="toolbar-sort-select"
                            value={sort}
                            onChange$={(_, el) =>
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

                    <div class="toolbar-dropdown">
                        <button
                            type="button"
                            class="toolbar-btn toolbar-btn--primary"
                            onClick$={() => {
                                store.addMenuOpen = !store.addMenuOpen;
                            }}
                            aria-expanded={store.addMenuOpen}
                            aria-haspopup="true"
                            aria-label="Add download"
                        >
                            <LuPlus width="16" height="16" aria-hidden="true" />
                            <span class="toolbar-btn-text">New</span>
                        </button>
                        {store.addMenuOpen && (
                            <div
                                class="toolbar-dropdown-menu"
                                role="menu"
                                onClick$={() => {
                                    store.addMenuOpen = false;
                                    onAddClick();
                                }}
                            >
                                <button
                                    type="button"
                                    class="dropdown-item"
                                    role="menuitem"
                                >
                                    <svg
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
                                        aria-hidden="true"
                                    >
                                        <path d="M6 9l6 6 6-6" />
                                    </svg>
                                    <span>Magnet Link</span>
                                </button>
                                <button
                                    type="button"
                                    class="dropdown-item"
                                    role="menuitem"
                                >
                                    <svg
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
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
                                    class="dropdown-item"
                                    role="menuitem"
                                >
                                    <svg
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
                                        aria-hidden="true"
                                    >
                                        <circle cx="12" cy="12" r="10" />
                                        <line x1="2" y1="12" x2="22" y2="12" />
                                        <path d="M12 2a20 20 0 0 1 4 10 20 20 0 0 1-4 10 20 20 0 0 1-4-10 20 20 0 0 1 4-10z" />
                                    </svg>
                                    <span>URL (YouTube, etc.)</span>
                                </button>
                            </div>
                        )}
                    </div>

                    <button
                        type="button"
                        class="toolbar-btn toolbar-btn--icon"
                        onClick$={onSettingsClick}
                        aria-label="Settings"
                    >
                        <LuSettings width="18" height="18" aria-hidden="true" />
                    </button>

                    <ThemeToggle />
                </div>
            </header>
        );
    },
);
