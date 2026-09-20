import { component$, $, useStore } from "@qwik.dev/core";
import {
    LuDownload,
    LuCheckCircle,
    LuLoader2,
    LuChevronDown,
    LuChevronRight,
    LuX,
} from "@/component/core/icons";
import "./field.css";

export interface SidebarProps {
    filter: SidebarFilter;
    onFilterChange: (filter: SidebarFilter) => void;
    counts: SidebarCounts;
    collapsed?: boolean;
    open?: boolean;
    onToggleCollapse?: () => void;
    /** What a sweep would find, so a button that can do nothing stays away. */
    bulk: BulkAvailability;
    onPauseAll: () => void;
    onResumeAll: () => void;
    onClearFinished: () => void;
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

const FilterIcon = component$((props: { id: SidebarFilter }) => {
    if (props.id === "all") {
        return <LuDownload width="16" height="16" aria-hidden="true" />;
    }
    if (props.id === "completed") {
        return <LuCheckCircle width="16" height="16" aria-hidden="true" />;
    }
    return <LuLoader2 width="16" height="16" aria-hidden="true" />;
});

export const Sidebar = component$<SidebarProps>(
    ({
        filter,
        onFilterChange,
        counts,
        collapsed = false,
        open = true,
        onToggleCollapse,
        bulk,
        onPauseAll,
        onResumeAll,
        onClearFinished,
    }) => {
        const store = useStore({
            downloadsExpanded: true,
        });

        return (
            <aside
                class={`sidebar ${collapsed ? "sidebar--collapsed" : ""} ${open ? "sidebar--open" : ""}`}
                role="navigation"
                aria-label="Download filters"
            >
                <div class="sidebar-header">
                    <span class="sidebar-title">Downloads</span>
                    {onToggleCollapse && (
                        <button
                            type="button"
                            class="sidebar-collapse-btn"
                            onClick$={onToggleCollapse}
                            aria-label={
                                collapsed
                                    ? "Expand sidebar"
                                    : "Collapse sidebar"
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

                <nav class="sidebar-nav">
                    {FILTERS.map((f) => (
                        <button
                            key={f.id}
                            type="button"
                            class={`sidebar-filter ${filter === f.id ? "sidebar-filter--active" : ""}`}
                            onClick$={() => onFilterChange(f.id)}
                        >
                            <FilterIcon id={f.id} />
                            <span class="sidebar-filter-label">{f.label}</span>
                            <span class="sidebar-filter-count">
                                {counts[f.id]}
                            </span>
                        </button>
                    ))}
                </nav>

                {(bulk.pausable > 0 ||
                    bulk.resumable > 0 ||
                    bulk.finished > 0) && (
                    <div class="sidebar-bulk">
                        <span class="sidebar-bulk-label">Everything</span>
                        {bulk.pausable > 0 && (
                            <button
                                type="button"
                                class="sidebar-bulk-btn"
                                onClick$={onPauseAll}
                            >
                                Pause all ({bulk.pausable})
                            </button>
                        )}
                        {bulk.resumable > 0 && (
                            <button
                                type="button"
                                class="sidebar-bulk-btn"
                                onClick$={onResumeAll}
                            >
                                Resume all ({bulk.resumable})
                            </button>
                        )}
                        {bulk.finished > 0 && (
                            <button
                                type="button"
                                class="sidebar-bulk-btn"
                                onClick$={onClearFinished}
                            >
                                Clear finished ({bulk.finished})
                            </button>
                        )}
                    </div>
                )}
            </aside>
        );
    },
);
