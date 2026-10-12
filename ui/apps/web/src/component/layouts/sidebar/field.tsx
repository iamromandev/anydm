import {
    LuDownload,
    LuCheckCircle,
    LuLoader2,
    LuChevronDown,
    LuChevronRight,
    LuSearch,
    LuGlobe,
    LuBarChart3,
} from "@/component/core/icons";
import type { CategoryItem } from "@/lib/api/category";
import "./field.css";

export interface SidebarProps {
    filter: SidebarFilter;
    onFilterChange: (filter: SidebarFilter) => void;
    /** Every category, listed under the status filters. */
    categories: CategoryItem[];
    /** The category the list is narrowed to, or null for every category. */
    category: string | null;
    onCategoryChange: (id: string | null) => void;
    counts: SidebarCounts;
    collapsed?: boolean;
    open?: boolean;
    onToggleCollapse?: () => void;
    /** What a sweep would find, so a button that can do nothing stays away. */
    bulk: BulkAvailability;
    onPauseAll: () => void;
    onResumeAll: () => void;
    onClearFinished: () => void;
    /** The Search item: shown when the API has indexers. */
    search?: { enabled: boolean; active: boolean; onOpen: () => void };
    /** The Sources item: always shown, so a switched-off last source can be switched back on. */
    sources: { active: boolean; onOpen: () => void };
    /** The Stats item: what was downloaded and uploaded over a range. */
    stats: { active: boolean; onOpen: () => void };
}

export interface BulkAvailability {
    pausable: number;
    resumable: number;
    finished: number;
}

export type SidebarFilter = "all" | "downloading" | "seeding" | "completed";

export interface SidebarCounts {
    all: number;
    downloading: number;
    seeding: number;
    completed: number;
}

const FILTERS: Array<{ id: SidebarFilter; label: string }> = [
    { id: "all", label: "All" },
    { id: "downloading", label: "Active" },
    { id: "seeding", label: "Seeding" },
    { id: "completed", label: "Completed" },
];

function FilterIcon(props: { id: SidebarFilter }) {
    if (props.id === "all") {
        return <LuDownload width="16" height="16" aria-hidden="true" />;
    }
    if (props.id === "completed") {
        return <LuCheckCircle width="16" height="16" aria-hidden="true" />;
    }
    return <LuLoader2 width="16" height="16" aria-hidden="true" />;
}

export function Sidebar({
    filter,
    onFilterChange,
    categories,
    category,
    onCategoryChange,
    counts,
    collapsed = false,
    open = true,
    onToggleCollapse,
    bulk,
    onPauseAll,
    onResumeAll,
    onClearFinished,
    search,
    sources,
    stats,
}: SidebarProps) {
    return (
        <aside
            className={`sidebar ${collapsed ? "sidebar--collapsed" : ""} ${open ? "sidebar--open" : ""}`}
            role="navigation"
            aria-label="Download filters"
        >
            {search?.enabled && (
                <nav
                    className="sidebar-nav sidebar-nav--search"
                    aria-label="Search"
                >
                    <button
                        type="button"
                        className={`sidebar-filter ${search.active ? "sidebar-filter--active" : ""}`}
                        onClick={search.onOpen}
                    >
                        <LuSearch width="16" height="16" aria-hidden="true" />
                        <span className="sidebar-filter-label">Search</span>
                    </button>
                </nav>
            )}
            <nav
                className="sidebar-nav sidebar-nav--sources"
                aria-label="Sources"
            >
                <button
                    type="button"
                    className={`sidebar-filter ${sources.active ? "sidebar-filter--active" : ""}`}
                    onClick={sources.onOpen}
                >
                    <LuGlobe width="16" height="16" aria-hidden="true" />
                    <span className="sidebar-filter-label">Sources</span>
                </button>
            </nav>
            <nav className="sidebar-nav sidebar-nav--stats" aria-label="Stats">
                <button
                    type="button"
                    className={`sidebar-filter ${stats.active ? "sidebar-filter--active" : ""}`}
                    onClick={stats.onOpen}
                >
                    <LuBarChart3 width="16" height="16" aria-hidden="true" />
                    <span className="sidebar-filter-label">Stats</span>
                </button>
            </nav>

            <div className="sidebar-header">
                <span className="sidebar-title">Downloads</span>
                {onToggleCollapse && (
                    <button
                        type="button"
                        className="sidebar-collapse-btn"
                        onClick={onToggleCollapse}
                        aria-label={
                            collapsed ? "Expand sidebar" : "Collapse sidebar"
                        }
                    >
                        {collapsed ? (
                            <LuChevronRight
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        ) : (
                            <LuChevronDown
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        )}
                    </button>
                )}
            </div>

            <nav className="sidebar-nav">
                {FILTERS.map((f) => (
                    <button
                        key={f.id}
                        type="button"
                        className={`sidebar-filter ${!search?.active && filter === f.id ? "sidebar-filter--active" : ""}`}
                        onClick={() => onFilterChange(f.id)}
                    >
                        <FilterIcon id={f.id} />
                        <span className="sidebar-filter-label">{f.label}</span>
                        <span className="sidebar-filter-count">
                            {counts[f.id]}
                        </span>
                    </button>
                ))}
            </nav>

            <h3 className="sidebar-heading">Categories</h3>
            <nav className="sidebar-nav" aria-label="Categories">
                <button
                    type="button"
                    className={`sidebar-filter ${!search?.active && category === null ? "sidebar-filter--active" : ""}`}
                    onClick={() => onCategoryChange(null)}
                >
                    <span className="sidebar-filter-label">All categories</span>
                </button>
                {categories.map((c) => (
                    <button
                        key={c.id}
                        type="button"
                        className={`sidebar-filter ${!search?.active && category === c.id ? "sidebar-filter--active" : ""}`}
                        onClick={() => onCategoryChange(c.id)}
                    >
                        <span className="sidebar-filter-label">{c.name}</span>
                        <span className="sidebar-filter-count">{c.count}</span>
                    </button>
                ))}
            </nav>

            {(bulk.pausable > 0 || bulk.resumable > 0 || bulk.finished > 0) && (
                <div className="sidebar-bulk">
                    <span className="sidebar-bulk-label">Everything</span>
                    {bulk.pausable > 0 && (
                        <button
                            type="button"
                            className="sidebar-bulk-btn"
                            onClick={onPauseAll}
                        >
                            Pause all ({bulk.pausable})
                        </button>
                    )}
                    {bulk.resumable > 0 && (
                        <button
                            type="button"
                            className="sidebar-bulk-btn"
                            onClick={onResumeAll}
                        >
                            Resume all ({bulk.resumable})
                        </button>
                    )}
                    {bulk.finished > 0 && (
                        <button
                            type="button"
                            className="sidebar-bulk-btn"
                            onClick={onClearFinished}
                        >
                            Clear finished ({bulk.finished})
                        </button>
                    )}
                </div>
            )}
        </aside>
    );
}
