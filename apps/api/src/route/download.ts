import { Hono } from "hono";

import { isYouTubeUrl, resolveYouTubeDownload, type DownloadPreset } from "./extract";
import * as console from "node:console";

// --- Download task store ---

export type DownloadTask = {
    id: string;
    url: string;
    title: string;
    preset: DownloadPreset;
    kind: "video" | "audio";
    status: "pending" | "downloading" | "complete" | "failed";
    progress: number;
    error?: string;
    downloadUrl?: string;
    videoUrl?: string;
    audioUrl?: string;
    mimeType?: string;
};

const downloadTasks = new Map<string, DownloadTask>();

function createTask(data: Omit<DownloadTask, "id">): DownloadTask {
    const id = crypto.randomUUID();
    const task: DownloadTask = { id, ...data };
    downloadTasks.set(id, task);
    return task;
}

function readFfmpegPath(): string {
    // ffmpeg-static default export is the path to the bundled binary.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const path = require("ffmpeg-static") as string;
    if (!path) {
        throw new Error("ffmpeg-static did not resolve a binary path");
    }
    return path;
}

function spawnFfmpeg(args: string[]): Bun.PipedSubprocess {
    return Bun.spawn({
        cmd: [
            readFfmpegPath(),
            ...args,
        ],
        stdout: "pipe",
        stderr: "pipe",
    });
}

// --- Router ---

const downloadRouter = new Hono();

// POST /download/youtube — initiate YouTube download by preset
downloadRouter.post("/youtube", async (context) => {
    let body: { url?: string; preset?: DownloadPreset };

    try {
        body = await context.req.json();
    } catch {
        return context.json({ success: false, error: "Invalid JSON body" }, 400);
    }

    const url = body.url?.trim();
    const preset = body.preset ?? "best";

    const VALID_PRESETS: DownloadPreset[] = [
        "best",
        "2160",
        "1440",
        "1080",
        "720",
        "480",
        "mp3",
    ];

    if (!url) {
        return context.json({ success: false, error: "url required" }, 400);
    }

    if (!VALID_PRESETS.includes(preset)) {
        return context.json({ success: false, error: `Invalid preset "${preset}"` }, 400);
    }

    if (!isYouTubeUrl(url)) {
        return context.json({ success: false, error: "Not a valid YouTube URL" }, 400);
    }

    try {
        const resolution = await resolveYouTubeDownload(url, preset);

        const task = createTask({
            url,
            title: resolution.filename,
            preset,
            kind: resolution.kind,
            status: "pending",
            progress: 0,
            downloadUrl: resolution.downloadUrl,
            videoUrl: resolution.videoUrl,
            audioUrl: resolution.audioUrl,
            mimeType: resolution.mimeType,
        });

        return context.json({
            success: true,
            taskId: task.id,
            filename: resolution.filename,
            kind: resolution.kind,
        });
    } catch (err: unknown) {
        console.error("YouTube download failed:", err);

        const message =
            err instanceof Error ? err.message : typeof err === "string" ? err : "Failed to get YouTube download URL";

        return context.json({ success: false, error: message }, 500);
    }
});

// GET /download/:id/file — proxy the actual file stream
downloadRouter.get("/:id/file", async (context) => {
    const id = context.req.param("id");
    const task = downloadTasks.get(id);

    if (!task) {
        return context.json({ success: false, error: "Task not found" }, 404);
    }

    if (task.status === "failed") {
        return context.json({ success: false, error: task.error || "Download failed" }, 500);
    }

    // --- Combined video: proxy the resolved stream directly ---
    if (task.kind === "video" && task.downloadUrl) {
        try {
            task.status = "downloading";

            const response = await fetch(task.downloadUrl);
            if (!response.ok) {
                throw new Error(`YouTube returned status ${response.status}`);
            }

            task.status = "complete";
            task.progress = 100;

            const safeFilename = task.title.replace(/[^\w\s.-]/g, "_");
            return new Response(response.body, {
                headers: {
                    "Content-Disposition": `attachment; filename="${safeFilename}"`,
                    "Content-Type": task.mimeType || response.headers.get("Content-Type") || "application/octet-stream",
                    "Content-Length": response.headers.get("Content-Length") || "",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            return context.json({ success: false, error: task.error }, 500);
        }
    }

    // --- Muxed video: merge video-only + audio-only via ffmpeg ---
    if (task.kind === "video" && task.videoUrl && task.audioUrl) {
        try {
            task.status = "downloading";

            const safeFilename = task.title.replace(/[^\w\s.-]/g, "_");
            const ffmpeg = spawnFfmpeg([
                "-y",
                "-i",
                task.videoUrl,
                "-i",
                task.audioUrl,
                "-c",
                "copy",
                "-movflags",
                "frag_keyframe+empty_moov",
                "-f",
                "mp4",
                "pipe:1",
            ]);

            ffmpeg.stderr
                .getReader()
                .read()
                .catch(() => {});

            ffmpeg.exited
                .then((code) => {
                    if (code === 0) {
                        task.status = "complete";
                        task.progress = 100;
                    } else {
                        task.status = "failed";
                        task.error = `ffmpeg exited with code ${code}`;
                    }
                })
                .catch((err: Error) => {
                    task.status = "failed";
                    task.error = err.message;
                });

            return new Response(ffmpeg.stdout, {
                headers: {
                    "Content-Disposition": `attachment; filename="${safeFilename}"`,
                    "Content-Type": task.mimeType || "video/mp4",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            return context.json({ success: false, error: task.error }, 500);
        }
    }

    // --- Audio: transcode to MP3 via ffmpeg ---
    if (task.kind === "audio" && task.audioUrl) {
        try {
            task.status = "downloading";

            const safeFilename = task.title.replace(/[^\w\s.-]/g, "_");
            const ffmpeg = spawnFfmpeg([
                "-y",
                "-i",
                task.audioUrl,
                "-vn",
                "-c:a",
                "libmp3lame",
                "-q:a",
                "2",
                "-f",
                "mp3",
                "pipe:1",
            ]);

            ffmpeg.stderr
                .getReader()
                .read()
                .catch(() => {});

            ffmpeg.exited
                .then((code) => {
                    if (code === 0) {
                        task.status = "complete";
                        task.progress = 100;
                    } else {
                        task.status = "failed";
                        task.error = `ffmpeg exited with code ${code}`;
                    }
                })
                .catch((err: Error) => {
                    task.status = "failed";
                    task.error = err.message;
                });

            return new Response(ffmpeg.stdout, {
                headers: {
                    "Content-Disposition": `attachment; filename="${safeFilename}"`,
                    "Content-Type": task.mimeType || "audio/mpeg",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            return context.json({ success: false, error: task.error }, 500);
        }
    }

    return context.json({ success: false, error: "No download source available" }, 500);
});

// GET /download — list all download tasks
downloadRouter.get("/", (context) => {
    const tasks = Array.from(downloadTasks.values());
    return context.json({ success: true, data: tasks });
});

// GET /download/:id — single task status
downloadRouter.get("/:id", (context) => {
    const id = context.req.param("id");
    const task = downloadTasks.get(id);

    if (!task) {
        return context.json({ success: false, error: "Task not found" }, 404);
    }

    return context.json({ success: true, data: task });
});

// POST /download — torrent upload (currently unavailable)
downloadRouter.post("", (context) => {
    return context.text("Torrent download not yet implemented", 501);
});

export default downloadRouter;
