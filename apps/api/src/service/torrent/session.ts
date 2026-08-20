import { mkdir } from "node:fs/promises";
import { join } from "node:path";

import {
    RqbitSession,
    type RqbitAddTorrentOptions,
    type RqbitSessionOptions,
    type RqbitSessionStats,
    type TorrentStats,
} from "rqbit-napi";

let session: RqbitSession | null = null;
let downloadsDir = "";

export function resolveDownloadsDir(): string {
    const dir = process.env.DOWNLOADS_DIR?.trim();
    if (dir) {
        return dir;
    }
    return join(import.meta.dir, "..", "..", "..", "downloads");
}

export function getDownloadsDir(): string {
    if (!downloadsDir) {
        downloadsDir = resolveDownloadsDir();
    }
    return downloadsDir;
}

export async function getSession(): Promise<RqbitSession> {
    if (!session) {
        downloadsDir = resolveDownloadsDir();
        await mkdir(downloadsDir, { recursive: true });

        const options: RqbitSessionOptions = {
            fastresume: true,
            disableDht: false,
            disableDhtPersistence: false,
            enableUpnp: false,
        };

        session = await RqbitSession.create(downloadsDir, options);
    }
    return session;
}

export async function addTorrentUrl(url: string, options: RqbitAddTorrentOptions): Promise<number> {
    const client = await getSession();
    return client.addTorrent(url, options);
}

export async function addTorrentBuffer(buffer: Buffer, options: RqbitAddTorrentOptions): Promise<number> {
    const client = await getSession();
    return client.addTorrentBuffer(buffer, options);
}

export async function pauseTorrent(index: number): Promise<boolean> {
    const client = await getSession();
    return client.pauseTorrent(index);
}

export async function startTorrent(index: number): Promise<boolean> {
    const client = await getSession();
    return client.startTorrent(index);
}

export async function deleteTorrent(index: number, deleteFiles: boolean): Promise<boolean> {
    const client = await getSession();
    return client.deleteTorrent(index, deleteFiles);
}

export async function getTorrentStats(index: number): Promise<TorrentStats | null> {
    const client = await getSession();
    return client.getTorrentStats(index);
}

export function getSessionStats(): RqbitSessionStats {
    if (!session) {
        return { fetchedBytes: 0, uploadedBytes: 0, downloadSpeed: 0, uploadSpeed: 0, uptimeSeconds: 0 };
    }
    return session.getSessionStats();
}

export function updateLimits(downloadBps?: number | null, uploadBps?: number | null): void {
    if (!session) return;
    session.updateLimits(downloadBps ?? undefined, uploadBps ?? undefined);
}

export async function closeSession(): Promise<void> {
    if (session) {
        await session.stop().catch(() => {});
        session = null;
    }
}

process.on("exit", () => {
    if (session) {
        session.stop().catch(() => {});
    }
});
