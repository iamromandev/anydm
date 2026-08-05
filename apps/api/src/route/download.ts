import { Hono } from "hono";

import { getYouTubeDownloadUrl, isYouTubeUrl } from "./extract";
import * as console from "node:console";

// --- Download task store ---

export type DownloadTask = {
    id: string;
    url: string;
    title: string;
    format: string;
    itag: number;
    downloadUrl?: string;
    mimeType?: string;
    status: "pending" | "downloading" | "complete" | "failed";
    progress: number;
    error?: string;
};

const downloadTasks = new Map<string, DownloadTask>();

function createTask(data: Omit<DownloadTask, "id">): DownloadTask {
    const id = crypto.randomUUID();
    const task: DownloadTask = { id, ...data };
    downloadTasks.set(id, task);
    return task;
}

// --- Router ---

const downloadRouter = new Hono();

// POST /download/youtube — initiate YouTube download
downloadRouter.post("/youtube", async (context) => {
    let body: { url?: string; itag?: number };

    try {
        body = await context.req.json();
    } catch {
        return context.json({ success: false, error: "Invalid JSON body" }, 400);
    }

    const { url, itag } = body;

    if (!url || itag == null) {
        return context.json({ success: false, error: "url and itag required" }, 400);
    }

    if (!isYouTubeUrl(url)) {
        return context.json({ success: false, error: "Not a valid YouTube URL" }, 400);
    }

    try {
        const { downloadUrl, filename, mimeType } = await getYouTubeDownloadUrl(url, itag);

        const task = createTask({
            url,
            title: filename,
            format: String(itag),
            itag,
            downloadUrl,
            mimeType,
            status: "pending",
            progress: 0,
        });

        return context.json({
            success: true,
            taskId: task.id,
            filename,
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

    let downloadUrl = task.downloadUrl;
    if (!downloadUrl) {
        try {
            const result = await getYouTubeDownloadUrl(task.url, task.itag);
            downloadUrl = result.downloadUrl;
            task.mimeType = result.mimeType;
            task.downloadUrl = result.downloadUrl;
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Failed to resolve download URL";
            return context.json({ success: false, error: task.error }, 500);
        }
    }

    try {
        task.status = "downloading";

        const response = await fetch(downloadUrl);
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
