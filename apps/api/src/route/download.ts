import * as console from "node:console";

import { Hono } from "hono";

import { getTask, listTasks, streamTask } from "../service/download";
import { HttpError } from "../service/errors";
import { createTask } from "../service/task";
import { getGlobalStats as getTorrentGlobalStats } from "../service/torrent";
import { isYouTubeUrl, resolveYouTubeDownload, type DownloadPreset } from "../service/youtube";

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

    try {
        return await streamTask(id);
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Download failed";
        return context.json({ success: false, error: message }, status as never);
    }
});

// GET /download — list all download tasks
downloadRouter.get("/", (context) => {
    return context.json({
        success: true,
        data: listTasks(),
        globalStats: getTorrentGlobalStats(),
    });
});

// GET /download/:id — single task status
downloadRouter.get("/:id", (context) => {
    const id = context.req.param("id");
    const task = getTask(id);

    if (!task) {
        return context.json({ success: false, error: "Task not found" }, 404);
    }

    return context.json({ success: true, data: task });
});

export default downloadRouter;
