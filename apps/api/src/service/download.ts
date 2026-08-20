import { basename } from "node:path";

import { HttpError } from "./errors";
import { spawnFfmpeg } from "./ffmpeg";
import { downloadTasks, type DownloadTask, type ProgressDetails } from "./task";
import { getTorrentFilePath, verifyTorrent, type TorrentErrorCode } from "./torrent";

function safeFilename(title: string): string {
    return title.replace(/[^\w\s.-]/g, "_");
}

export async function streamTask(id: string): Promise<Response> {
    const task = downloadTasks.get(id);
    if (!task) {
        throw new HttpError(404, "Task not found");
    }

    if (task.status === "failed") {
        throw new HttpError(500, task.error || "Download failed");
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

            return new Response(response.body, {
                headers: {
                    "Content-Disposition": `attachment; filename="${safeFilename(task.title)}"`,
                    "Content-Type": task.mimeType || response.headers.get("Content-Type") || "application/octet-stream",
                    "Content-Length": response.headers.get("Content-Length") || "",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            throw new HttpError(500, task.error);
        }
    }

    // --- Muxed video: merge video-only + audio-only via ffmpeg ---
    if (task.kind === "video" && task.videoUrl && task.audioUrl) {
        try {
            task.status = "downloading";

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
                    "Content-Disposition": `attachment; filename="${safeFilename(task.title)}"`,
                    "Content-Type": task.mimeType || "video/mp4",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            throw new HttpError(500, task.error);
        }
    }

    // --- Audio: transcode to MP3 via ffmpeg ---
    if (task.kind === "audio" && task.audioUrl) {
        try {
            task.status = "downloading";

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
                    "Content-Disposition": `attachment; filename="${safeFilename(task.title)}"`,
                    "Content-Type": task.mimeType || "audio/mpeg",
                    "Cache-Control": "no-cache",
                },
            });
        } catch (err: unknown) {
            task.status = "failed";
            task.error = err instanceof Error ? err.message : "Download failed";
            throw new HttpError(500, task.error);
        }
    }

    // --- Torrent: stream the largest file from the torrent ---
    if (task.kind === "torrent") {
        if (task.status === "complete") {
            try {
                const filePath = await getTorrentFilePath(task.id);
                if (!filePath) {
                    throw new HttpError(500, "Torrent files not available");
                }

                const file = Bun.file(filePath);
                const info = await file.stat();
                const safeFilename = basename(filePath).replace(/[^\w\s.-]/g, "_");

                return new Response(file.stream(), {
                    headers: {
                        "Content-Disposition": `attachment; filename="${safeFilename}"`,
                        "Content-Type": "application/octet-stream",
                        "Content-Length": String(info.size),
                        "Cache-Control": "no-cache",
                    },
                });
            } catch (err: unknown) {
                if (err instanceof HttpError) {
                    throw err;
                }
                task.status = "failed";
                task.error = err instanceof Error ? err.message : "Torrent streaming failed";
                throw new HttpError(500, task.error);
            }
        }

        if (task.status === "verifying" || task.status === "checking") {
            // During verification/checking, wait and then check again
            throw new HttpError(409, "Torrent verifying/checking - please wait");
        }

        if (task.status === "downloading") {
            // Stream partial download - find largest available file
            try {
                const filePath = await getTorrentFilePath(task.id);
                if (!filePath) {
                    throw new HttpError(500, "Torrent files not yet available");
                }

                const file = Bun.file(filePath);
                const info = await file.stat();
                const safeFilename = basename(filePath).replace(/[^\w\s.-]/g, "_");

                return new Response(file.stream(), {
                    headers: {
                        "Content-Disposition": `attachment; filename="${safeFilename}"`,
                        "Content-Type": "application/octet-stream",
                        "Content-Length": String(info.size),
                        "Cache-Control": "no-cache",
                    },
                });
            } catch (err: unknown) {
                if (err instanceof HttpError) {
                    throw err;
                }
                task.status = "failed";
                task.error = err instanceof Error ? err.message : "Torrent streaming failed";
                throw new HttpError(500, task.error);
            }
        }

        throw new HttpError(500, "Torrent not in a streamable state");
    }

    throw new HttpError(500, "No download source available");
}

export function getTask(id: string): DownloadTask | undefined {
    return downloadTasks.get(id);
}

export function listTasks(): DownloadTask[] {
    return Array.from(downloadTasks.values());
}
