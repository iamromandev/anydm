import { component$, $ } from "@qwik.dev/core";
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
import { videoFailureOf, type SearchState } from "./state";
import "./video-panel.css";

export interface VideoPanelProps {
    state: SearchState;
    onAddVideo: (url: string) => Promise<void> | void;
}

export const VideoPanel = component$<VideoPanelProps>(
    ({ state, onAddVideo }) => {
        // Declared before its callers: a $() captures only what is above it.
        const run = $(async (text: string) => {
            const video = state.video;
            if (!canSearchVideos(text, video.busy)) return;
            const q = text.trim();
            video.busy = true;
            video.failure = null;
            try {
                video.answer = await searchVideos(q);
                video.searched = q;
            } catch (error) {
                video.answer = null;
                video.searched = q;
                video.failure = videoFailureOf(error);
            } finally {
                video.busy = false;
            }
        });

        const add = $(async (url: string) => {
            if (state.video.adding) return;
            state.video.adding = url;
            try {
                await onAddVideo(url);
            } catch {
                // The add flow has already told the person why.
            } finally {
                state.video.adding = "";
            }
        });

        const video = state.video;
        const now = Date.now();

        return (
            <div class="video-panel">
                <form
                    class="search-view-bar"
                    preventdefault:submit
                    onSubmit$={() => run(state.video.q)}
                >
                    <input
                        class="search-view-query"
                        type="search"
                        placeholder="Search YouTube"
                        aria-label="Search YouTube"
                        value={video.q}
                        onInput$={(_, el) => {
                            state.video.q = el.value;
                        }}
                    />
                    <button
                        class="search-view-go"
                        type="submit"
                        // Disabled only while a request runs; run() ignores a too-short query.
                        disabled={video.busy}
                    >
                        {video.busy ? (
                            <span class="search-view-spin">
                                <LuLoader2
                                    width="16"
                                    height="16"
                                    aria-hidden="true"
                                />
                            </span>
                        ) : (
                            <LuSearch
                                width="16"
                                height="16"
                                aria-hidden="true"
                            />
                        )}
                        <span>Search</span>
                    </button>
                </form>

                {video.failure && (
                    <div class="search-view-failure" role="alert">
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
                            class="search-view-retry"
                            onClick$={() => run(state.video.searched)}
                        >
                            Try again
                        </button>
                    </div>
                )}

                {!video.answer && !video.failure && (
                    <p class="search-view-empty">
                        {video.busy ? (
                            <span class="search-view-spin">
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
                    <p class="search-view-empty">
                        {videoEmptyText(video.searched)}
                    </p>
                )}

                {video.answer && video.answer.results.length > 0 && (
                    <>
                        <div class="search-view-status" role="status">
                            <span>
                                {video.answer.results.length} videos ·{" "}
                                {(video.answer.tookMs / 1000).toFixed(1)} s
                            </span>
                        </div>
                        <ul class="search-view-list">
                            {video.answer.results.map((r, i) => (
                                <li
                                    key={r.url}
                                    class="video-row"
                                    style={{ "--i": String(Math.min(i, 12)) }}
                                >
                                    <div class="video-thumb">
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
                                            <span class="video-duration">
                                                {formatDuration(r.durationS)}
                                            </span>
                                        )}
                                    </div>
                                    <div class="video-body">
                                        <p class="video-title">{r.title}</p>
                                        <p class="video-facts">
                                            {r.channel && (
                                                <span class="video-channel">
                                                    {r.channel}
                                                </span>
                                            )}
                                            {videoFacts(r, now).map((f) => (
                                                <span key={f}>{f}</span>
                                            ))}
                                        </p>
                                    </div>
                                    <div class="video-actions">
                                        <button
                                            type="button"
                                            class="search-view-add"
                                            aria-label={`Add ${r.title}`}
                                            disabled={video.adding !== ""}
                                            onClick$={() => add(r.url)}
                                        >
                                            {video.adding === r.url ? (
                                                <>
                                                    <span class="search-view-spin">
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
    },
);
