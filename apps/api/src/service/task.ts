import type { DownloadPreset } from "./youtube";

export type ProgressDetails = {
    downloadedBytes: number;
    totalBytes: number;
    downloadSpeed: number;
    uploadSpeed: number;
    eta: number;
    peersConnected: number;
};

export type TaskLimits = {
    maxDownloadRate?: number;
    maxUploadRate?: number;
};

export type DownloadTask = {
    id: string;
    url: string;
    title: string;
    preset: DownloadPreset;
    kind: "video" | "audio" | "torrent";
    status: "pending" | "downloading" | "verifying" | "checking" | "paused" | "seeding" | "complete" | "failed";
    progress: number;
    error?: string;
    downloadUrl?: string;
    videoUrl?: string;
    audioUrl?: string;
    mimeType?: string;
    infoHash?: string;
    addedAt?: number;
    completedAt?: number;
    uploadedBytes?: number;
    seeders?: number;
    leechers?: number;
    ratio?: number;
    eta?: number;
    downloadPath?: string;
    sourceInput?: string;
    limits?: TaskLimits;
    progressDetails?: ProgressDetails;
};

export const downloadTasks = new Map<string, DownloadTask>();

import { writeFile, mkdir } from "node:fs/promises";
import { join } from "node:path";

const STORAGE_PATH = join(import.meta.dir, "..", "..", "data", "tasks.json");

async function persistTasks(): Promise<void> {
    const tasks = Array.from(downloadTasks.values()).map(({ progressDetails, ...rest }) => rest);
    await mkdir(join(import.meta.dir, "..", "..", "data"), { recursive: true });
    await writeFile(STORAGE_PATH, JSON.stringify(tasks, null, 2));
}

export function createTaskId(): string {
    return crypto.randomUUID();
}

export function createTask(data: Omit<DownloadTask, "id"> & { progressDetails?: ProgressDetails }): DownloadTask {
    const id = crypto.randomUUID();
    const task: DownloadTask = { id, ...data };
    downloadTasks.set(id, task);
    persistTasks().catch((err) => console.error("Failed to persist task:", err));
    return task;
}

export function getTask(id: string): DownloadTask | undefined {
    return downloadTasks.get(id);
}

export function listTasks(): DownloadTask[] {
    return Array.from(downloadTasks.values());
}

export function removeTask(id: string): void {
    downloadTasks.delete(id);
    persistTasks().catch((err) => console.error("Failed to persist tasks:", err));
}

export function updateTask(updated: Partial<DownloadTask>): void {
    if (!updated.id) return;
    const existing = downloadTasks.get(updated.id);
    if (!existing) return;
    const task = { ...existing, ...updated };
    downloadTasks.set(updated.id, task);
    persistTasks().catch((err) => console.error("Failed to persist tasks:", err));
}

export function setTask(id: string, data: Partial<DownloadTask>): void {
    const task = { ...downloadTasks.get(id)!, data };
    downloadTasks.set(id, task);
    persistTasks().catch((err) => console.error("Failed to persist tasks:", err));
}
