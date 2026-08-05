import { component$, $, useStore, useVisibleTask$ } from "@qwik.dev/core";
import { UrlInput } from "@/component/input/url";
import {
    LuCheckCircle,
    LuXCircle,
    LuAlertTriangle,
    LuLoader2,
    LuRefreshCw,
    LuMonitorPlay,
    LuFilm,
    LuMusic2,
    LuGlobe,
    LuTrash,
    LuArrowDownToLine,
    LuX,
} from "@/component/icons";
import {
    SiYoutube,
    SiTiktok,
    SiInstagram,
    SiX,
    SiVimeo,
    SiTwitch,
} from "@/component/icons";
import { LuLink } from "@/component/icons";
import "../style/global.css";
import "../style/home.css";

type DownloadPreset = "best" | "2160" | "1440" | "1080" | "720" | "480" | "mp3";

type TaskStatus = "pending" | "downloading" | "complete" | "failed";

type DownloadTask = {
    id: string;
    url: string;
    title: string;
    preset: DownloadPreset;
    kind: "video" | "audio";
    status: TaskStatus;
    progress: number;
    error?: string;
    addedAt: number;
};

const STORAGE_KEY = "anydm:tasks";
const MAX_TASKS = 20;

const PRESETS: Array<{ id: DownloadPreset; label: string }> = [
    { id: "best", label: "Best Quality" },
    { id: "2160", label: "4K (2160p)" },
    { id: "1440", label: "1440p" },
    { id: "1080", label: "1080p" },
    { id: "720", label: "720p" },
    { id: "480", label: "480p" },
    { id: "mp3", label: "Audio (MP3)" },
];

function getBaseUrl(): string {
    return (
        import.meta.env.PUBLIC_BASE_URL ||
        (import.meta.env.DEV ? "http://localhost:3000" : "")
    );
}

function getPlatformFromUrl(url: string): string {
    const lower = url.toLowerCase();
    if (lower.includes("youtube.com") || lower.includes("youtu.be"))
        return "YouTube";
    if (lower.includes("tiktok.com")) return "TikTok";
    if (lower.includes("instagram.com")) return "Instagram";
    if (lower.includes("twitter.com") || lower.includes("x.com")) return "X";
    if (lower.includes("vimeo.com")) return "Vimeo";
    if (lower.includes("twitch.tv")) return "Twitch";
    return "Other";
}

function getPlatformIcon(platform: string) {
    switch (platform) {
        case "YouTube":
            return SiYoutube;
        case "TikTok":
            return SiTiktok;
        case "Instagram":
            return SiInstagram;
        case "X":
            return SiX;
        case "Vimeo":
            return SiVimeo;
        case "Twitch":
            return SiTwitch;
        default:
            return LuGlobe;
    }
}

function getPresetIcon(preset: DownloadPreset) {
    if (preset === "mp3") return LuMusic2;
    if (preset === "best") return LuMonitorPlay;
    return LuFilm;
}

function getPresetLabel(preset: DownloadPreset): string {
    return PRESETS.find((p) => p.id === preset)?.label ?? preset;
}

function getStatusBadgeClass(status: TaskStatus): string {
    switch (status) {
        case "pending":
            return "task-badge--pending";
        case "downloading":
            return "task-badge--downloading";
        case "complete":
            return "task-badge--complete";
        case "failed":
            return "task-badge--failed";
    }
}

function getStatusIcon(status: TaskStatus) {
    switch (status) {
        case "pending":
            return LuLoader2;
        case "downloading":
            return LuArrowDownToLine;
        case "complete":
            return LuCheckCircle;
        case "failed":
            return LuXCircle;
    }
}

function getStatusLabel(status: TaskStatus): string {
    switch (status) {
        case "pending":
            return "Queued";
        case "downloading":
            return "Downloading";
        case "complete":
            return "Complete";
        case "failed":
            return "Failed";
    }
}

export default component$(() => {
    const store = useStore({
        url: "",
        error: "",
        preset: "best" as DownloadPreset,
        tasks: [] as DownloadTask[],
        activeTab: "active" as "active" | "completed",
        adding: false,
    });

    const loadTasks = $(() => {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (raw) {
                store.tasks = JSON.parse(raw);
            }
        } catch {
            // corrupt data — ignore
        }
    });

    const persistTasks = $(() => {
        try {
            localStorage.setItem(
                STORAGE_KEY,
                JSON.stringify(store.tasks.slice(0, MAX_TASKS)),
            );
        } catch {
            // storage full — ignore
        }
    });

    const addTask = $((task: DownloadTask) => {
        store.tasks = [
            task,
            ...store.tasks.filter((t) => t.id !== task.id),
        ].slice(0, MAX_TASKS);
        persistTasks();
    });

    const removeTask = $((id: string) => {
        store.tasks = store.tasks.filter((t) => t.id !== id);
        persistTasks();
    });

    const clearCompleted = $(() => {
        store.tasks = store.tasks.filter((t) => t.status !== "complete");
        persistTasks();
    });

    const triggerFile = $((taskId: string) => {
        const BASE_URL = getBaseUrl();
        if (!BASE_URL) return;

        const a = document.createElement("a");
        a.href = `${BASE_URL}/download/${taskId}/file`;
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    });

    const handleDownload = $(async (e: Event) => {
        e.preventDefault();

        const url = store.url.trim();
        if (!url) {
            store.error = "Paste a URL first";
            return;
        }

        store.error = "";
        store.adding = true;

        try {
            new URL(url);
        } catch {
            store.error = "Invalid URL";
            store.adding = false;
            return;
        }

        try {
            const BASE_URL = getBaseUrl();
            if (!BASE_URL) {
                throw new Error(
                    "PUBLIC_BASE_URL is missing. Copy apps/ui/.env.example to apps/ui/.env.local and restart the dev server.",
                );
            }

            const response = await fetch(`${BASE_URL}/download/youtube`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ url, preset: store.preset }),
            });

            const payload = await response.json();

            if (!response.ok || !payload.success) {
                throw new Error(
                    payload.success === false
                        ? payload.error
                        : "Failed to start download",
                );
            }

            triggerFile(payload.taskId);

            addTask({
                id: payload.taskId,
                url,
                title: payload.filename,
                preset: store.preset,
                kind: payload.kind,
                status: "downloading",
                progress: 0,
                addedAt: Date.now(),
            });

            store.url = "";
            store.activeTab = "active";
        } catch (err: unknown) {
            console.error(err);
            store.error =
                err instanceof Error ? err.message : "Download failed";
        } finally {
            store.adding = false;
        }
    });

    useVisibleTask$(({ cleanup }) => {
        loadTasks();

        const syncTasks = async () => {
            try {
                const BASE_URL = getBaseUrl();
                if (!BASE_URL) return;

                const response = await fetch(`${BASE_URL}/download`);
                const payload = await response.json();
                if (!payload.success) return;

                const serverMap = new Map<string, DownloadTask>(
                    payload.data.map((t: DownloadTask) => [
                        t.id,
                        t,
                    ]),
                );

                let changed = false;
                store.tasks = store.tasks.map((t) => {
                    const server = serverMap.get(t.id);
                    if (
                        server &&
                        (server.status !== t.status ||
                            server.progress !== t.progress ||
                            server.error !== t.error)
                    ) {
                        changed = true;
                        return {
                            ...t,
                            status: server.status,
                            progress: server.progress,
                            error: server.error,
                        };
                    }
                    return t;
                });

                if (changed) {
                    persistTasks();
                }
            } catch {
                // poll failures are silent
            }
        };

        syncTasks();
        const interval = setInterval(syncTasks, 2500);
        cleanup(() => clearInterval(interval));
    });

    const activeTasks = store.tasks.filter((t) => t.status !== "complete");
    const completedTasks = store.tasks.filter((t) => t.status === "complete");
    const visibleTasks =
        store.activeTab === "active" ? activeTasks : completedTasks;

    return (
        <div class="home-container">
            <div class="mesh-bg">
                <div class="mesh-blob" />
                <div class="mesh-blob" />
                <div class="mesh-blob" />
                <div class="mesh-blob" />
            </div>

            <div class="dm-dashboard">
                {/* Download bar */}
                <div class="dm-bar">
                    <div class="dm-bar-header">
                        <h1>Any Download Manager</h1>
                        <p>
                            Paste a YouTube link, pick a format, and download.
                        </p>
                    </div>

                    <form
                        preventdefault:submit
                        onSubmit$={handleDownload}
                        class="dm-form"
                    >
                        <div class="dm-form-row">
                            <div class="dm-url-field">
                                <UrlInput
                                    value={store.url}
                                    onChange$={(v: any) => {
                                        store.url = v ?? "";
                                        store.error = "";
                                    }}
                                    placeholder="https://www.youtube.com/watch?v=..."
                                    error={store.error}
                                    required
                                />
                            </div>

                            <select
                                class="dm-preset"
                                aria-label="Format preset"
                                value={store.preset}
                                onChange$={(e) => {
                                    store.preset = (
                                        e.target as HTMLSelectElement
                                    ).value as DownloadPreset;
                                    store.error = "";
                                }}
                            >
                                {PRESETS.map((p) => (
                                    <option key={p.id} value={p.id}>
                                        {p.label}
                                    </option>
                                ))}
                            </select>

                            <button
                                type="submit"
                                class="dm-download-btn"
                                disabled={store.adding}
                            >
                                {store.adding ? (
                                    <svg
                                        class="spin"
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
                                        stroke-linecap="round"
                                        stroke-linejoin="round"
                                    >
                                        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                                    </svg>
                                ) : (
                                    <svg
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        stroke-width="2"
                                        stroke-linecap="round"
                                        stroke-linejoin="round"
                                    >
                                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                        <polyline points="7 10 12 15 17 10" />
                                        <line x1="12" y1="15" x2="12" y2="3" />
                                    </svg>
                                )}
                                <span>
                                    {store.adding ? "Adding..." : "Download"}
                                </span>
                            </button>
                        </div>
                    </form>
                </div>

                {/* Task manager */}
                <div class="dm-manager">
                    <div class="dm-tabs">
                        <button
                            type="button"
                            class={`dm-tab ${store.activeTab === "active" ? "dm-tab--active" : ""}`}
                            onClick$={() => (store.activeTab = "active")}
                        >
                            Active
                            <span class="dm-tab-count">
                                {activeTasks.length}
                            </span>
                        </button>
                        <button
                            type="button"
                            class={`dm-tab ${store.activeTab === "completed" ? "dm-tab--active" : ""}`}
                            onClick$={() => (store.activeTab = "completed")}
                        >
                            Completed
                            <span class="dm-tab-count">
                                {completedTasks.length}
                            </span>
                        </button>

                        {store.activeTab === "completed" &&
                            completedTasks.length > 0 && (
                                <button
                                    type="button"
                                    class="dm-clear-btn"
                                    onClick$={clearCompleted}
                                >
                                    <LuTrash width="12" height="12" />
                                    Clear all
                                </button>
                            )}
                    </div>

                    {visibleTasks.length === 0 ? (
                        <div class="dm-empty">
                            {store.activeTab === "active" ? (
                                <>
                                    <LuArrowDownToLine width="28" height="28" />
                                    <p>No active downloads</p>
                                    <p class="dm-empty-sub">
                                        Paste a link above to start downloading.
                                    </p>
                                </>
                            ) : (
                                <>
                                    <LuCheckCircle width="28" height="28" />
                                    <p>No completed downloads</p>
                                    <p class="dm-empty-sub">
                                        Finished downloads will show up here.
                                    </p>
                                </>
                            )}
                        </div>
                    ) : (
                        <ul class="dm-task-list">
                            {visibleTasks.map((task) => {
                                const PlatformIcon = getPlatformIcon(
                                    getPlatformFromUrl(task.url),
                                );
                                const PresetIcon = getPresetIcon(task.preset);
                                const StatusIcon = getStatusIcon(task.status);
                                const platform = getPlatformFromUrl(task.url);

                                return (
                                    <li key={task.id} class="dm-task">
                                        <div class="dm-task-platform">
                                            <PlatformIcon
                                                width="18"
                                                height="18"
                                            />
                                        </div>

                                        <div class="dm-task-main">
                                            <div class="dm-task-title">
                                                {task.title}
                                            </div>
                                            <div class="dm-task-meta">
                                                <span class="dm-task-platform-name">
                                                    {platform}
                                                </span>
                                                <span class="dm-task-preset">
                                                    <PresetIcon
                                                        width="12"
                                                        height="12"
                                                    />
                                                    {getPresetLabel(
                                                        task.preset,
                                                    )}
                                                </span>
                                            </div>

                                            {task.status === "downloading" ? (
                                                <div class="dm-progress">
                                                    <div class="dm-progress-track">
                                                        <div class="dm-progress-shimmer" />
                                                    </div>
                                                    <span class="dm-progress-label">
                                                        Downloading…
                                                    </span>
                                                </div>
                                            ) : task.status === "pending" ? (
                                                <div class="dm-progress">
                                                    <div class="dm-progress-track dm-progress-track--idle" />
                                                    <span class="dm-progress-label">
                                                        Queued
                                                    </span>
                                                </div>
                                            ) : null}

                                            {task.error && (
                                                <div class="dm-task-error">
                                                    <LuAlertTriangle
                                                        width="12"
                                                        height="12"
                                                    />
                                                    {task.error}
                                                </div>
                                            )}
                                        </div>

                                        <div class="dm-task-side">
                                            <span
                                                class={`task-badge ${getStatusBadgeClass(task.status)}`}
                                            >
                                                <StatusIcon
                                                    width="12"
                                                    height="12"
                                                />
                                                {getStatusLabel(task.status)}
                                            </span>

                                            {task.status === "complete" && (
                                                <button
                                                    type="button"
                                                    class="dm-task-action"
                                                    title="Download again"
                                                    onClick$={() =>
                                                        triggerFile(task.id)
                                                    }
                                                >
                                                    <LuRefreshCw
                                                        width="14"
                                                        height="14"
                                                    />
                                                </button>
                                            )}

                                            <button
                                                type="button"
                                                class="dm-task-action dm-task-action--danger"
                                                title="Remove"
                                                onClick$={() =>
                                                    removeTask(task.id)
                                                }
                                            >
                                                <LuX width="14" height="14" />
                                            </button>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    )}
                </div>

                {/* Supported platforms hint */}
                <div class="dm-platforms">
                    <LuGlobe width="14" height="14" />
                    Supports YouTube, TikTok, Instagram, X, Vimeo, Twitch and
                    more
                </div>
            </div>
        </div>
    );
});
