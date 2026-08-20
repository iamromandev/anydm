import { Hono } from "hono";

import { HttpError } from "../service/errors";
import { downloadTasks } from "../service/task";
import { broadcast, clientCount, subscribe } from "../service/torrent/events";
import {
    addTorrent,
    getGlobalStats,
    listTorrents,
    pauseTorrent,
    removeTorrent,
    resumeTorrent,
    setGlobalLimits,
    verifyTorrent,
    type DownloadTask,
} from "../service/torrent";
import type { ProgressDetails } from "../service/task";

const torrentRouter = new Hono();

// Lazy periodic global-stats broadcast for connected SSE clients.
let statsTimer: ReturnType<typeof setInterval> | null = null;
function ensureStatsBroadcast(): void {
    if (statsTimer) return;
    statsTimer = setInterval(() => {
        if (clientCount() === 0) {
            clearInterval(statsTimer!);
            statsTimer = null;
            return;
        }
        broadcast("stats", getGlobalStats());
    }, 2000);
}

torrentRouter.post("", async (context) => {
    let body: { torrent?: string };

    try {
        body = await context.req.json();
    } catch {
        return context.json({ success: false, error: "Invalid JSON body" }, 400);
    }

    const input = body.torrent?.trim();
    if (!input) {
        return context.json({ success: false, error: "torrent required (magnet URI or base64 .torrent file)" }, 400);
    }

    try {
        const task = await addTorrent(input);

        return context.json({
            success: true,
            taskId: task.id,
            filename: task.title,
            kind: "torrent",
        });
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Failed to start torrent download";
        return context.json({ success: false, error: message }, status as never);
    }
});

torrentRouter.get("/", (context) => {
    return context.json({ success: true, data: listTorrents() });
});

// GET /download/torrent/events — server-sent events for live updates
torrentRouter.get("/events", (context) => {
    const stream = subscribe();
    ensureStatsBroadcast();

    broadcast("tasks", listTorrents());
    broadcast("stats", getGlobalStats());

    return new Response(stream, {
        headers: {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache, no-transform",
            Connection: "keep-alive",
        },
    });
});

// GET /download/torrent/global/stats — aggregate session + torrent stats
torrentRouter.get("/global/stats", (context) => {
    return context.json({ success: true, data: getGlobalStats() });
});

// PATCH /download/torrent/global/limits — set global download/upload speed limits (bytes/sec)
torrentRouter.patch("/global/limits", async (context) => {
    let parsed: { downloadBps?: number | null; uploadBps?: number | null };
    try {
        parsed = await context.req.json();
    } catch {
        return context.json({ success: false, error: "Invalid JSON body" }, 400);
    }

    const downloadBps = typeof parsed.downloadBps === "number" ? parsed.downloadBps : null;
    const uploadBps = typeof parsed.uploadBps === "number" ? parsed.uploadBps : null;

    setGlobalLimits(downloadBps, uploadBps);
    broadcast("stats", getGlobalStats());

    return context.json({ success: true, data: getGlobalStats() });
});

torrentRouter.get("/:id", (context) => {
    const task = downloadTasks.get(context.req.param("id"));

    if (!task) {
        return context.json({ success: false, error: "Task not found" }, 404);
    }

    return context.json({ success: true, data: task });
});

torrentRouter.delete("/:id", async (context) => {
    try {
        await removeTorrent(context.req.param("id"));
        return context.json({ success: true });
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Failed to delete torrent";
        return context.json({ success: false, error: message }, status as never);
    }
});

torrentRouter.post("/:id/pause", async (context) => {
    try {
        const task = await pauseTorrent(context.req.param("id"));
        return context.json({ success: true, data: task });
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Failed to pause torrent";
        return context.json({ success: false, error: message }, status as never);
    }
});

torrentRouter.post("/:id/resume", async (context) => {
    try {
        const task = await resumeTorrent(context.req.param("id"));
        return context.json({ success: true, data: task });
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Failed to resume torrent";
        return context.json({ success: false, error: message }, status as never);
    }
});

torrentRouter.post("/:id/verify", async (context) => {
    try {
        const task = await verifyTorrent(context.req.param("id"));
        return context.json({ success: true, data: task });
    } catch (err: unknown) {
        const status = err instanceof HttpError ? err.status : 500;
        const message = err instanceof Error ? err.message : "Verification failed";
        return context.json({ success: false, error: message }, status as never);
    }
});

torrentRouter.get("/:id/progress", (context) => {
    const task = downloadTasks.get(context.req.param("id"));

    if (!task) {
        return context.json({ success: false, error: "Task not found" }, 404);
    }

    const progress: Partial<ProgressDetails> = {
        downloadedBytes: task.progressDetails?.downloadedBytes ?? 0,
        totalBytes: task.progressDetails?.totalBytes ?? 0,
        downloadSpeed: task.progressDetails?.downloadSpeed ?? 0,
        uploadSpeed: task.progressDetails?.uploadSpeed ?? 0,
        eta: task.progressDetails?.eta ?? 0,
        peersConnected: task.progressDetails?.peersConnected ?? 0,
    };

    return context.json({
        success: true,
        data: {
            id: task.id,
            progress: task.progress,
            status: task.status,
            ...progress,
        },
    });
});

export default torrentRouter;
