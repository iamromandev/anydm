import { component$ } from "@qwik.dev/core";
import type { SidebarCounts, SidebarFilter } from "@/component/layouts/sidebar";
import { speedText, statusParts, titleFor } from "./present";
import "./field.css";

export interface StatusLineProps {
    counts: SidebarCounts;
    filter: SidebarFilter;
    onFilterChange: (filter: SidebarFilter) => void;
    downloadSpeed: number;
    uploadSpeed: number;
}

/**
 * The downloads' status line: how many of each, which are also the filters,
 * and how fast everything is moving. It takes the place of the list's heading.
 */
export const StatusLine = component$<StatusLineProps>(
    ({ counts, filter, onFilterChange, downloadSpeed, uploadSpeed }) => {
        const speed = speedText(downloadSpeed, uploadSpeed);
        return (
            <div class="status-line" role="group" aria-label="Filter downloads">
                <h2 class="status-line-title">{titleFor(filter)}</h2>
                {statusParts(counts, filter).map((part) => (
                    <button
                        key={part.id}
                        type="button"
                        class="status-line-part"
                        aria-pressed={filter === part.id}
                        onClick$={() => onFilterChange(part.id)}
                    >
                        {part.text}
                    </button>
                ))}
                {speed && <span class="status-line-speed">{speed}</span>}
            </div>
        );
    },
);
