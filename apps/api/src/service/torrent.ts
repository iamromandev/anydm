import { rm } from "node:fs/promises";
import { join } from "node:path";

import { HttpError } from "./errors";
import { createTask, downloadTasks, type DownloadTask, type ProgressDetails } from "./task";
import { emitTaskUpdate } from "./torrent/events";
import {
    addTorrentBuffer as sessionAddTorrentBuffer,
    addTorrentUrl as sessionAddTorrentUrl,
    deleteTorrent as sessionDeleteTorrent,
    getDownloadsDir,
    getSessionStats,
    getTorrentStats,
    pauseTorrent as sessionPauseTorrent,
    startTorrent as sessionStartTorrent,
    updateLimits as sessionUpdateLimits,
} from "./torrent/session";

const torrentIndexes = new Map<string, number>();
const pollers = new Map<string, ReturnType<typeof setInterval>>();
const taskListeners = new Map<string, (task: DownloadTask) => void>();

function isMagnetUri(input: string): boolean {
    return input.startsWith("magnet:");
}

function isBase64Torrent(input: string): boolean {
    if (input.startsWith("magnet:")) return false;
    try {
        const decoded = Buffer.from(input, "base64");
        if (decoded.length < 8 || decoded[0] !== 0x64) return false;
        const text = decoded.toString("latin1");
        return text.includes("4:info") && text.includes("8:announce");
    } catch {
        return false;
    }
}

function extractInfoHash(magnet: string): string | undefined {
    const match = magnet.match(/[?&]xt=urn:btih:([a-zA-Z0-9]+)/);
    return match?.[1]?.toLowerCase();
}

function stopPolling(taskId: string): void {
    const timer = pollers.get(taskId);
    if (timer) {
        clearInterval(timer);
        pollers.delete(taskId);
    }
}

function startPolling(taskId: string, index: number): void {
    if (pollers.has(taskId)) return;

    const timer = setInterval(async () => {
        const task = downloadTasks.get(taskId);
        if (!task) {
            stopPolling(taskId);
            return;
        }

        if (task.status === "paused") {
            return;
        }

        try {
            const stats = await getTorrentStats(index);
            if (!stats) return;

            task.title = stats.name || task.title;
            task.status = "downloading";

            const downloadSpeed = stats.downloadSpeed ?? 0;
            const uploadSpeed = stats.uploadSpeed ?? 0;
            const remaining = Math.max(0, stats.totalBytes - stats.downloadedBytes);
            const eta = downloadSpeed > 0 ? Math.round(remaining / downloadSpeed) : (task.eta ?? 0);

            if (stats.totalBytes > 0) {
                task.progress = Math.min(100, Math.round((stats.downloadedBytes / stats.totalBytes) * 100));
            }

            task.uploadedBytes = stats.uploadedBytes;
            task.ratio = stats.totalBytes > 0 ? stats.uploadedBytes / stats.totalBytes : 0;
            task.eta = eta;
            task.progressDetails = {
                downloadedBytes: stats.downloadedBytes,
                totalBytes: stats.totalBytes,
                downloadSpeed,
                uploadSpeed,
                eta,
                peersConnected: 0,
            };

            if (stats.finished) {
                task.status = "complete";
                task.progress = 100;
                task.eta = 0;
                task.completedAt = task.completedAt ?? Date.now();
                stopPolling(taskId);
                notifyTaskListeners(taskId, task);
            }

            emitTaskUpdate(task);
        } catch (err) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Torrent download failed";
            task.progressDetails = {
                downloadedBytes: task.progressDetails?.downloadedBytes ?? 0,
                totalBytes: task.progressDetails?.totalBytes ?? 0,
                downloadSpeed: 0,
                uploadSpeed: 0,
                eta: 0,
                peersConnected: 0,
            };
            stopPolling(taskId);
            notifyTaskListeners(taskId, task);
            emitTaskUpdate(task);
        }
    }, 1000);

    pollers.set(taskId, timer);
}

function notifyTaskListeners(taskId: string, task: DownloadTask): void {
    const listener = taskListeners.get(taskId);
    if (listener) {
        try {
            listener(task);
        } catch (err) {
            console.error("Task listener error:", err);
        }
    }
}

export function onTaskUpdate(callback: (task: DownloadTask) => void): string {
    const id = crypto.randomUUID();
    taskListeners.set(id, callback);
    return id;
}

export function offTaskUpdate(listenerId: string): void {
    taskListeners.delete(listenerId);
}

async function findLargestFile(folder: string): Promise<string | null> {
    let largest: { path: string; size: number } | null = null;

    let walker;
    try {
        walker = new Bun.Glob("**/*").scan({ cwd: folder, onlyFiles: true });
    } catch {
        return null;
    }

    for await (const relative of walker) {
        const fullPath = join(folder, relative);
        try {
            const info = await Bun.file(fullPath).stat();
            if (info.isFile() && (!largest || info.size > largest.size)) {
                largest = { path: fullPath, size: info.size };
            }
        } catch {
            // unreadable entry — skip
        }
    }

    return largest?.path ?? null;
}

const ERROR_CATEGORIES = {
    MAGNET_INVALID: "magnet_invalid",
    ADD_TORRENT_FAILED: "add_torrent_failed",
    DOWNLOAD_FAILED: "download_failed",
    VERIFICATION_FAILED: "verification_failed",
    SESSION_FAILED: "session_failed",
};

let retryCounts = new Map<string, number>();

export enum TorrentErrorCode {
    MagnetInvalid = "magnet_invalid",
    AddTorrentFailed = "add_torrent_failed",
    DownloadFailed = "download_failed",
    VerificationFailed = "verification_failed",
    SessionFailed = "session_failed",
    TaskNotFound = "task_not_found",
}

export class TorrentError extends Error {
    constructor(
        public code: TorrentErrorCode,
        message: string,
    ) {
        super(message);
        this.name = "TorrentError";
    }
}

function categorizeError(err: unknown): { code: TorrentErrorCode; message: string } {
    if (err instanceof HttpError) {
        if (err.status === 400) {
            return { code: TorrentErrorCode.MagnetInvalid, message: err.message };
        }
    }

    const msg = err instanceof Error ? err.message : String(err);

    if (msg.toLowerCase().includes("magnet")) {
        return { code: TorrentErrorCode.MagnetInvalid, message: msg };
    }

    if (msg.toLowerCase().includes("add torrent") || msg.toLowerCase().includes("add_torrent")) {
        return { code: TorrentErrorCode.AddTorrentFailed, message: msg };
    }

    if (msg.toLowerCase().includes("download") || msg.toLowerCase().includes("downloading")) {
        return { code: TorrentErrorCode.DownloadFailed, message: msg };
    }

    if (msg.toLowerCase().includes("verify") || msg.toLowerCase().includes("verification")) {
        return { code: TorrentErrorCode.VerificationFailed, message: msg };
    }

    if (msg.toLowerCase().includes("session") || msg.toLowerCase().includes("rqbit")) {
        return { code: TorrentErrorCode.SessionFailed, message: msg };
    }

    return { code: TorrentErrorCode.DownloadFailed, message: msg };
}

let retryMap = new Map<string, { attempts: number; lastAttempt: number }>();

function shouldRetry(taskId: string): boolean {
    const record = retryMap.get(taskId);
    if (!record) return true;
    if (record.attempts >= 3) return false;
    const now = Date.now();
    if (now - record.lastAttempt < 1000 * 60 * 5) return false;
    return true;
}

function incrementRetry(taskId: string): void {
    const record = retryMap.get(taskId);
    if (record) {
        record.attempts += 1;
        record.lastAttempt = Date.now();
    } else {
        retryMap.set(taskId, { attempts: 1, lastAttempt: Date.now() });
    }
}

export async function addTorrent(input: string): Promise<DownloadTask> {
    if (!isMagnetUri(input) && !isBase64Torrent(input)) {
        throw new HttpError(400, "Invalid torrent input — expected magnet URI or base64 .torrent data");
    }

    let retryCount = 0;
    const maxRetries = 3;
    let task: DownloadTask | undefined;

    while (retryCount < maxRetries) {
        try {
            const isMagnet = isMagnetUri(input);

            task = createTask({
                url: isMagnet ? input : ".torrent file",
                title: "Loading torrent...",
                preset: "best",
                kind: "torrent",
                status: "pending",
                progress: 0,
                error: undefined,
                downloadUrl: undefined,
                videoUrl: undefined,
                audioUrl: undefined,
                mimeType: undefined,
                infoHash: isMagnet ? extractInfoHash(input) : undefined,
                addedAt: Date.now(),
                sourceInput: input,
            });

            const outputFolder = join(getDownloadsDir(), task!.id);
            const options = { outputFolder, overwrite: true };
            const index = isMagnet
                ? await sessionAddTorrentUrl(input, options)
                : await sessionAddTorrentBuffer(Buffer.from(input, "base64"), options);

            task.downloadPath = outputFolder;
            torrentIndexes.set(task!.id, index);
            startPolling(task!.id, index);

            return task!;
        } catch (err: unknown) {
            console.error("Torrent download attempt failed:", err);
            retryCount++;
            incrementRetry(task?.id ?? "");

            const { code, message } = categorizeError(err);

            if (code === TorrentErrorCode.MagnetInvalid) {
                throw new HttpError(400, message);
            }

            if (retryCount < maxRetries && shouldRetry(task?.id ?? "")) {
                const backoffMs = 1000 * 2 ** retryCount;
                console.log(`Retrying torrent add in ${backoffMs / 1000}s (attempt ${retryCount + 1}/${maxRetries})`);
                await new Promise((resolve) => setTimeout(resolve, backoffMs));
                continue;
            }

            const errCode = code !== TorrentErrorCode.DownloadFailed ? code : TorrentErrorCode.DownloadFailed;
            throw new HttpError(500, `${errCode}: ${message}`);
        }
    }

    throw new HttpError(500, "Max retries exceeded for torrent download");
}

export async function removeTorrent(id: string): Promise<void> {
    const task = downloadTasks.get(id);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    stopPolling(id);

    const index = torrentIndexes.get(id);
    if (index !== undefined) {
        try {
            await sessionDeleteTorrent(index, true);
        } catch (err) {
            console.error("Failed to delete torrent:", err);
        }
    }

    await rm(join(getDownloadsDir(), id), { recursive: true, force: true }).catch(() => {});

    torrentIndexes.delete(id);
    downloadTasks.delete(id);
    retryMap.delete(id);
    taskListeners.delete(id);
}

export function listTorrents(): DownloadTask[] {
    return Array.from(downloadTasks.values()).filter((t) => t.kind === "torrent");
}

export function getTorrentFilePath(taskId: string): Promise<string | null> {
    return findLargestFile(join(getDownloadsDir(), taskId));
}

export async function verifyTorrent(taskId: string): Promise<DownloadTask> {
    const task = downloadTasks.get(taskId);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    task.status = "verifying";

    try {
        const index = torrentIndexes.get(taskId);
        if (index === undefined) {
            throw new HttpError(500, "Torrent index not found");
        }

        const stats = await getTorrentStats(index);
        if (!stats) {
            throw new HttpError(500, "Could not get torrent stats");
        }

        task.progress = Math.min(100, Math.round((stats.downloadedBytes / (stats.totalBytes || 1)) * 100));

        if (stats.finished) {
            task.status = "complete";
            task.progress = 100;
        } else {
            task.status = "downloading";
        }

        emitTaskUpdate(task);
        return task;
    } catch (err: unknown) {
        task.status = "failed";
        task.error = err instanceof Error ? err.message : "Verification failed";
        emitTaskUpdate(task);
        throw new HttpError(500, task.error);
    }
}

export async function checkTorrentHealth(taskId: string): Promise<{
    healthy: boolean;
    progress: number;
    downloadSpeed: number;
    uploadSpeed: number;
    eta: number;
    peersConnected: number;
}> {
    const task = downloadTasks.get(taskId);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    try {
        const index = torrentIndexes.get(taskId);
        if (index === undefined) {
            return {
                healthy: false,
                progress: task.progress,
                downloadSpeed: 0,
                uploadSpeed: 0,
                eta: 0,
                peersConnected: 0,
            };
        }

        const stats = await getTorrentStats(index);
        if (!stats) {
            return {
                healthy: false,
                progress: task.progress,
                downloadSpeed: 0,
                uploadSpeed: 0,
                eta: 0,
                peersConnected: 0,
            };
        }

        const progress =
            stats.totalBytes > 0
                ? Math.min(100, Math.round((stats.downloadedBytes / stats.totalBytes) * 100))
                : task.progress;

        return {
            healthy: true,
            progress,
            downloadSpeed: stats.downloadSpeed ?? 0,
            uploadSpeed: stats.uploadSpeed ?? 0,
            eta: 0,
            peersConnected: 0,
        };
    } catch (err) {
        return { healthy: false, progress: task.progress, downloadSpeed: 0, uploadSpeed: 0, eta: 0, peersConnected: 0 };
    }
}

export async function pauseTorrent(taskId: string): Promise<DownloadTask> {
    const task = downloadTasks.get(taskId);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    const index = torrentIndexes.get(taskId);
    if (index !== undefined) {
        await sessionPauseTorrent(index);
    }

    task.status = "paused";
    if (task.progressDetails) {
        task.progressDetails.downloadSpeed = 0;
        task.progressDetails.uploadSpeed = 0;
    }
    emitTaskUpdate(task);
    return task;
}

export async function resumeTorrent(taskId: string): Promise<DownloadTask> {
    const task = downloadTasks.get(taskId);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    const index = torrentIndexes.get(taskId);
    if (index !== undefined) {
        await sessionStartTorrent(index);
    }

    if (task.status === "paused" || task.status === "failed") {
        task.status = "pending";
        task.error = undefined;
        if (index !== undefined && !pollers.has(taskId)) {
            startPolling(taskId, index);
        }
    }

    emitTaskUpdate(task);
    return task;
}

export function getGlobalStats() {
    const stats = getSessionStats();
    const torrents = listTorrents();

    const totalDownloaded = torrents.reduce((sum, t) => sum + (t.progressDetails?.downloadedBytes ?? 0), 0);
    const totalPeers = torrents.reduce((sum, t) => sum + (t.progressDetails?.peersConnected ?? 0), 0);
    const active = torrents.filter(
        (t) => t.status === "downloading" || t.status === "pending" || t.status === "verifying",
    );

    return {
        fetchedBytes: stats.fetchedBytes,
        uploadedBytes: stats.uploadedBytes,
        downloadSpeed: stats.downloadSpeed,
        uploadSpeed: stats.uploadSpeed,
        uptimeSeconds: stats.uptimeSeconds,
        totalDownloaded,
        totalPeers,
        activeCount: active.length,
        torrentCount: torrents.length,
    };
}

export function setGlobalLimits(downloadBps?: number | null, uploadBps?: number | null): void {
    sessionUpdateLimits(downloadBps, uploadBps);
}
export type { DownloadTask } from "./task";
