function readFfmpegPath(): string {
    // ffmpeg-static default export is the path to the bundled binary.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const path = require("ffmpeg-static") as string;
    if (!path) {
        throw new Error("ffmpeg-static did not resolve a binary path");
    }
    return path;
}

export function spawnFfmpeg(args: string[]): Bun.PipedSubprocess {
    return Bun.spawn({
        cmd: [
            readFfmpegPath(),
            ...args,
        ],
        stdout: "pipe",
        stderr: "pipe",
    });
}
