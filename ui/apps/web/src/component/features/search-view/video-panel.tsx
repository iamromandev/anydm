import type { CSSProperties } from "react";
import {
    LuAlertTriangle,
    LuLoader2,
    LuPlus,
    LuSearch,
} from "@/component/core/icons";
import { searchVideos } from "@/lib/api/search";
import {
    canSearchVideos,
    formatDuration,
    videoEmptyText,
    videoFacts,
    VIDEO_EMPTY_IDLE,
} from "./present";
import type { Patch } from "@/hook/use-patch";
import { videoFailureOf, type SearchState } from "./state";
import "./video-panel.css";

export interface VideoPanelProps {
    state: SearchState;
    patch: (changes: Patch<SearchState>) => void;
    onAddVideo: (url: string) => Promise<void> | void;
}

export function VideoPanel({ state, patch, onAddVideo }: VideoPanelProps) {
    /** Merge into the video half of the state, as it is when this lands. */
    const patchVideo = (changes: Partial<SearchState["video"]>) =>
        patch((held) => ({ video: { ...held.video, ...changes } }));

    const run = async (text: string) => {
        if (!canSearchVideos(text, state.video.busy)) return;
        const q = text.trim();
        patchVideo({ busy: true, failure: null });
        try {
            const answer = await searchVideos(q);
            patchVideo({ answer, searched: q });
        } catch (error) {
            patchVideo({
                answer: null,
                searched: q,
                failure: videoFailureOf(error),
            });
        } finally {
            patchVideo({ busy: false });
        }
    };

    const add = async (url: string) => {
        if (state.video.adding) return;
        patchVideo({ adding: url });
        try {
            await onAddVideo(url);
        } catch {
            // The add flow has already told the person why.
        } finally {
            patchVideo({ adding: "" });
        }
    };

    const video = state.video;
    const now = Date.now();

    return (
        <div className="video-panel">
            <form
                className="search-view-bar"
                onSubmit={(event) => {
                    event.preventDefault();
                    void run(state.video.q);
                }}
            >
                <input
                    className="search-view-query"
                    type="search"
                    placeholder="Search YouTube"
                    aria-label="Search YouTube"
                    value={video.q}
                    onChange={({ currentTarget: el }) =>
                        patchVideo({ q: el.value })
                    }
                />
                <button
                    className="search-view-go"
                    type="submit"
                    // Disabled only while a request runs; run() ignores a too-short query.
                    disabled={video.busy}
                >
                    {video.busy ? (
                        <span className="search-view-spin">
                            <LuLoader2
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        </span>
                    ) : (
                        <LuSearch width="16" height="16" aria-hidden="true" />
                    )}
                    <span>Search</span>
                </button>
            </form>

            {video.failure && (
                <div className="search-view-failure" role="alert">
                    <p>
                        <LuAlertTriangle
                            width="14"
                            height="14"
                            aria-hidden="true"
                        />{" "}
                        {video.failure}
                    </p>
                    <button
                        type="button"
                        className="search-view-retry"
                        onClick={() => run(state.video.searched)}
                    >
                        Try again
                    </button>
                </div>
            )}

            {!video.answer && !video.failure && (
                <p className="search-view-empty">
                    {video.busy ? (
                        <span className="search-view-spin">
                            <LuLoader2
                                width="20"
                                height="20"
                                aria-hidden="true"
                            />
                        </span>
                    ) : (
                        VIDEO_EMPTY_IDLE
                    )}
                </p>
            )}

            {video.answer && video.answer.results.length === 0 && (
                <p className="search-view-empty">
                    {videoEmptyText(video.searched)}
                </p>
            )}

            {video.answer && video.answer.results.length > 0 && (
                <>
                    <div className="search-view-status" role="status">
                        <span>
                            {video.answer.results.length} videos ·{" "}
                            {(video.answer.tookMs / 1000).toFixed(1)} s
                        </span>
                    </div>
                    <ul className="search-view-list">
                        {video.answer.results.map((r, i) => (
                            <li
                                key={r.url}
                                className="video-row"
                                style={
                                    {
                                        "--i": String(Math.min(i, 12)),
                                    } as CSSProperties
                                }
                            >
                                <div className="video-thumb">
                                    {r.thumbnail && (
                                        <img
                                            src={r.thumbnail}
                                            alt=""
                                            loading="lazy"
                                            width="148"
                                            height="83"
                                        />
                                    )}
                                    {r.durationS !== null && (
                                        <span className="video-duration">
                                            {formatDuration(r.durationS)}
                                        </span>
                                    )}
                                </div>
                                <div className="video-body">
                                    <p className="video-title">{r.title}</p>
                                    <p className="video-facts">
                                        {r.channel && (
                                            <span className="video-channel">
                                                {r.channel}
                                            </span>
                                        )}
                                        {videoFacts(r, now).map((f) => (
                                            <span key={f}>{f}</span>
                                        ))}
                                    </p>
                                </div>
                                <div className="video-actions">
                                    <button
                                        type="button"
                                        className="search-view-add"
                                        aria-label={`Add ${r.title}`}
                                        disabled={video.adding !== ""}
                                        onClick={() => add(r.url)}
                                    >
                                        {video.adding === r.url ? (
                                            <>
                                                <span className="search-view-spin">
                                                    <LuLoader2
                                                        width="14"
                                                        height="14"
                                                        aria-hidden="true"
                                                    />
                                                </span>
                                                Adding…
                                            </>
                                        ) : (
                                            <>
                                                <LuPlus
                                                    width="14"
                                                    height="14"
                                                    aria-hidden="true"
                                                />
                                                Add
                                            </>
                                        )}
                                    </button>
                                </div>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </div>
    );
}
