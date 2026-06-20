import { component$, $, useSignal } from "@qwik.dev/core";
import "../style/info.css";

export type VideoInfo = {
    platform: string;
    videoId: string;
    title: string;
    author: string;
    channelId: string;
    description: string;
    lengthSeconds: number;
    viewCount: string;
    uploadDate: string;
    category: string;
    isLive: boolean;
    thumbnail: string;
    formats: Array<{
        itag: number;
        quality: string;
        container: string;
        hasVideo: boolean;
        hasAudio: boolean;
        contentLength: string | null;
    }>;
};

type InfoProps = {
    data: VideoInfo;
    videoUrl?: string;
};

function formatDuration(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const remainingSeconds = seconds % 60;

    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, "0")}:${String(remainingSeconds).padStart(2, "0")}`;
    }

    return `${minutes}:${String(remainingSeconds).padStart(2, "0")}`;
}

function formatViewCount(count: string): string {
    const value = Number(count);
    if (Number.isNaN(value)) {
        return count;
    }

    return value.toLocaleString();
}

function formatFileSize(bytes: string | null): string {
    if (!bytes) {
        return "unknown";
    }

    const size = Number(bytes);
    if (Number.isNaN(size)) {
        return "unknown";
    }

    if (size >= 1_000_000_000) {
        return `${(size / 1_000_000_000).toFixed(2)} GB`;
    }

    if (size >= 1_000_000) {
        return `${(size / 1_000_000).toFixed(2)} MB`;
    }

    return `${(size / 1_000).toFixed(2)} KB`;
}

export const Info = component$<InfoProps>(({ data, videoUrl }) => {
    const downloadingItag = useSignal<number | null>(null);
    const downloadError = useSignal("");

    const uniqueFormats = data.formats.filter(
        (format, index, formats) =>
            formats.findIndex(
                (item) =>
                    item.quality === format.quality &&
                    item.container === format.container &&
                    item.hasVideo === format.hasVideo &&
                    item.hasAudio === format.hasAudio,
            ) === index,
    );

    const handleDownload = $(async (itag: number) => {
        if (!videoUrl) return;

        downloadingItag.value = itag;
        downloadError.value = "";

        try {
            const BASE_URL =
                import.meta.env.PUBLIC_BASE_URL ||
                (import.meta.env.DEV ? "http://localhost:3000" : "");

            const response = await fetch(`${BASE_URL}/download/youtube`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({ url: videoUrl, itag }),
            });

            const payload = await response.json();

            if (!response.ok || !payload.success) {
                downloadError.value =
                    payload.success === false
                        ? payload.error
                        : "Download failed";
                return;
            }

            const a = document.createElement("a");
            a.href = `${BASE_URL}/download/${payload.taskId}/file`;
            a.style.display = "none";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        } catch (err: unknown) {
            console.error(err);
            downloadError.value =
                err instanceof Error ? err.message : "Download failed";
        } finally {
            downloadingItag.value = null;
        }
    });

    return (
        <section class="info-panel">
            <div class="info-header">
                <img
                    src={data.thumbnail}
                    alt={data.title}
                    class="info-thumbnail"
                    width={320}
                    height={180}
                />
                <div class="info-meta">
                    <h2 class="info-title">{data.title}</h2>
                    <p class="info-author">{data.author}</p>
                    <div class="info-stats">
                        <span>{formatDuration(data.lengthSeconds)}</span>
                        <span>{formatViewCount(data.viewCount)} views</span>
                        {data.isLive && <span class="info-live">Live</span>}
                    </div>
                    {data.category && (
                        <p class="info-category">{data.category}</p>
                    )}
                    {data.uploadDate && (
                        <p class="info-upload">Uploaded {data.uploadDate}</p>
                    )}
                </div>
            </div>

            {data.description && (
                <details class="info-description">
                    <summary>Description</summary>
                    <p>{data.description}</p>
                </details>
            )}

            {uniqueFormats.length > 0 && (
                <div class="info-formats">
                    <h3>Available formats</h3>
                    <ul>
                        {uniqueFormats.map((format) => (
                            <li key={format.itag}>
                                <span class="info-format-quality">
                                    {format.quality}
                                </span>
                                <span>{format.container.toUpperCase()}</span>
                                <span>
                                    {format.hasVideo && format.hasAudio
                                        ? "video + audio"
                                        : format.hasVideo
                                          ? "video only"
                                          : "audio only"}
                                </span>
                                <span>
                                    {formatFileSize(format.contentLength)}
                                </span>
                                {videoUrl && (
                                    <button
                                        type="button"
                                        class="info-download-btn"
                                        disabled={
                                            downloadingItag.value ===
                                            format.itag
                                        }
                                        onClick$={() =>
                                            handleDownload(format.itag)
                                        }
                                    >
                                        {downloadingItag.value === format.itag
                                            ? "..."
                                            : "Download"}
                                    </button>
                                )}
                            </li>
                        ))}
                    </ul>
                    {downloadError.value && (
                        <p class="info-download-error">{downloadError.value}</p>
                    )}
                </div>
            )}
        </section>
    );
});
