import { component$, useSignal, useVisibleTask$ } from "@qwik.dev/core";
import "../../style/download.css";

type DownloadTask = {
    id: string;
    url: string;
    title: string;
    format: string;
    itag: number;
    status: "pending" | "downloading" | "complete" | "failed";
    progress: number;
    error?: string;
};

function getBaseUrl(): string {
    return (
        import.meta.env.PUBLIC_BASE_URL ||
        (import.meta.env.DEV ? "http://localhost:3000" : "")
    );
}

export default component$(() => {
    const tasks = useSignal<DownloadTask[]>([]);
    const isLoading = useSignal(true);
    const loadError = useSignal("");

    useVisibleTask$(({ cleanup }) => {
        const fetchTasks = async () => {
            try {
                const BASE_URL = getBaseUrl();
                if (!BASE_URL) {
                    loadError.value =
                        "PUBLIC_BASE_URL is missing. Copy apps/web/.env.example to apps/web/.env.local and restart the dev server.";
                    return;
                }

                const response = await fetch(`${BASE_URL}/download`);
                const payload = await response.json();

                if (payload.success) {
                    tasks.value = payload.data;
                    loadError.value = "";
                } else {
                    loadError.value =
                        payload.error || "Failed to load downloads";
                }
            } catch (err: unknown) {
                loadError.value =
                    err instanceof Error
                        ? err.message
                        : "Failed to load downloads";
            } finally {
                isLoading.value = false;
            }
        };

        fetchTasks();
        const interval = setInterval(fetchTasks, 3000);
        cleanup(() => clearInterval(interval));
    });

    return (
        <div class="download-page">
            <div class="download-container">
                <h1 class="download-title">Downloads</h1>

                {loadError.value && (
                    <div class="download-error">{loadError.value}</div>
                )}

                {isLoading.value && (
                    <p class="download-loading">Loading downloads...</p>
                )}

                {!isLoading.value &&
                    tasks.value.length === 0 &&
                    !loadError.value && (
                        <div class="download-empty">
                            <p>No downloads yet.</p>
                            <p>
                                Extract a video and download a format to see it
                                here.
                            </p>
                            <a href="/" class="download-empty-link">
                                Go to extract
                            </a>
                        </div>
                    )}

                {tasks.value.length > 0 && (
                    <div class="download-list">
                        {tasks.value.map((task) => (
                            <div key={task.id} class="download-card">
                                <div class="download-card-info">
                                    <span class="download-card-title">
                                        {task.title}
                                    </span>
                                    <span class="download-card-meta">
                                        Format: {task.format}
                                    </span>
                                </div>
                                <div class="download-card-status">
                                    <span
                                        class={`download-badge download-badge--${task.status}`}
                                    >
                                        {task.status}
                                    </span>
                                </div>
                                {task.status === "downloading" && (
                                    <div class="download-progress-bar">
                                        <div
                                            class="download-progress-fill"
                                            style={{
                                                width: `${task.progress}%`,
                                            }}
                                        />
                                    </div>
                                )}
                                {task.error && (
                                    <p class="download-card-error">
                                        {task.error}
                                    </p>
                                )}
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
});
