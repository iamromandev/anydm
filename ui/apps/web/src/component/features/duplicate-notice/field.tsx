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
export function DuplicateNotice({
    duplicate,
    onOpen,
    onAddAnyway,
    busy = false,
}: DuplicateNoticeProps) {
    return (
        <div className="duplicate-notice" role="alert">
            <LuAlertCircle width="16" height="16" aria-hidden="true" />
            <span className="duplicate-notice-text">
                Already in your list: {duplicate.title}
                {duplicate.status ? ` (${duplicate.status})` : ""}
                {duplicate.collectionId
                    ? `, in ${duplicate.collectionTitle || "a playlist"}`
                    : ""}
            </span>
            <span className="duplicate-notice-actions">
                <button
                    type="button"
                    className="duplicate-notice-btn duplicate-notice-btn--primary"
                    onClick={onOpen}
                    disabled={busy}
                >
                    Open
                </button>
                {onAddAnyway && (
                    <button
                        type="button"
                        className="duplicate-notice-btn"
                        onClick={onAddAnyway}
                        disabled={busy}
                    >
                        Add anyway
                    </button>
                )}
            </span>
        </div>
    );
}
