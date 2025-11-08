import { Hono } from "hono";

import * as console from "node:console";
import WebTorrent, { type Torrent } from "webtorrent";
import { tmpdir } from "os";
import { join } from "path";

export async function downloadTorrent(torrentFile: File) {
    const client = new WebTorrent();

    try {
        const buffer = Buffer.from(await torrentFile.arrayBuffer());

        return await new Promise<{
            message: string;
            name: string;
            path: string;
        }>((resolve, reject) => {
            client.add(buffer, { path: tmpdir() }, (torrent: Torrent) => {
                console.log(`Started downloading torrent: ${torrent.name} (Info Hash: ${torrent.infoHash})`);
                console.log(`Total size: ${torrent.length} bytes`);

                torrent.on("download", () => {
                    const progress = (torrent.downloaded / torrent.length) * 100;
                    console.log(`Download progress for ${torrent.name}: ${progress.toFixed(2)}%`);
                });

                torrent.on("done", () => {
                    console.log(`Torrent download finished: ${torrent.name}`);

                    if (!torrent.files || torrent.files.length === 0) {
                        reject(new Error(`No files found in torrent: ${torrent.name}`));
                        return;
                    }

                    const firstFilePath = torrent.files[0]?.path;
                    if (!firstFilePath) {
                        reject(new Error(`Invalid file path in torrent: ${torrent.name}`));
                        return;
                    }

                    const finalFilePath = join(torrent.path, firstFilePath);
                    console.log(`Downloaded content saved to: ${finalFilePath}`);

                    resolve({
                        message: `Successfully downloaded torrent: ${torrent.name}`,
                        name: torrent.name,
                        path: finalFilePath,
                    });
                });

                torrent.on("error", (err) => {
                    console.error("Torrent error:", err);
                    reject(err);
                });
            });

            client.on("error", (err) => {
                console.error("WebTorrent client setup error:", err);
                reject(err);
            });
        }).finally(() => {
            client.destroy();
            console.log("WebTorrent client destroyed.");
        });
    } catch (err: unknown) {
        client.destroy();
        console.log("WebTorrent client destroyed due to error.");
        throw err;
    }
}

const downloadRouter = new Hono();

downloadRouter.post("", async (context) => {
    const formData: FormData = await context.req.formData();
    const torrentFile = formData.get("torrent");

    if (!torrentFile || typeof torrentFile === "string") {
        return context.text("No torrent found.", 400);
    }

    try {
        /* const result = await downloadTorrent(torrentFile as File);
        return context.json({
            success: true,
            message: result.message,
            fileName: result.name,
            savedPath: result.path, // optional
        });*/
    } catch (err: unknown) {
        console.error("Torrent download failed:", err);

        const message =
            err instanceof Error
                ? err.message
                : typeof err === "string"
                  ? err
                  : "Unknown error during torrent processing";

        return context.text(`Server error during torrent processing: ${message}`, 500);
    }
});

export default downloadRouter;
