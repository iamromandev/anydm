import { component$ } from "@qwik.dev/core";
import { LuAlertCircle } from "@/component/core/icons";
import type { Duplicate } from "@/lib/api";
import "./field.css";

export interface DuplicateNoticeProps {
    duplicate: Duplicate;
    /** Show the download the list already holds. */
    onOpen: () => void;
    /** Add a second copy; left out where the API takes only one. */
    onAddAnyway?: () => void;
    busy?: boolean;
}

/** What an add says when the list already holds it, and the two ways on. */
export const DuplicateNotice = component$<DuplicateNoticeProps>(
    ({ duplicate, onOpen, onAddAnyway, busy = false }) => (
        <div class="duplicate-notice" role="alert">
            <LuAlertCircle width="16" height="16" aria-hidden="true" />
            <span class="duplicate-notice-text">
                Already in your list: {duplicate.title}
                {duplicate.status ? ` (${duplicate.status})` : ""}
            </span>
            <span class="duplicate-notice-actions">
                <button
                    type="button"
                    class="duplicate-notice-btn duplicate-notice-btn--primary"
                    onClick$={onOpen}
                    disabled={busy}
                >
                    Open
                </button>
                {onAddAnyway && (
                    <button
                        type="button"
                        class="duplicate-notice-btn"
                        onClick$={onAddAnyway}
                        disabled={busy}
                    >
                        Add anyway
                    </button>
                )}
            </span>
        </div>
    ),
);
