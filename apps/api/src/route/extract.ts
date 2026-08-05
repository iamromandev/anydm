import { Hono } from "hono";
import { Innertube, Platform } from "youtubei.js";
import vm from "node:vm";
import * as console from "node:console";

Platform.shim.eval = (data, env) => {
    // The player script ends with a top-level `return ...`, so it must run as
    // a function body. Wrap it in an IIFE and return the evaluated result.
    const context = vm.createContext({ ...env });
    const script = new vm.Script(`(function() {\n${data.output}\n})()`);
    return script.runInContext(context);
};

export type YouTubeFormat = {
    itag: number;
    quality: string;
    container: string;
    hasVideo: boolean;
    hasAudio: boolean;
    contentLength: string | null;
    mimeType: string | null;
};

export type YouTubeExtractResult = {
    platform: "youtube";
    videoId: string;
    title: string;
    author: string;
    channelId: string;
    description: string;
    lengthSeconds: number;
    viewCount: string;
    uploadDate: string;
    category: string;
    isLive: boolean;
    thumbnail: string;
    thumbnails: Array<{ url: string; width: number; height: number }>;
    formats: YouTubeFormat[];
};

let innertubeClient: Innertube | null = null;

async function getInnertubeClient(): Promise<Innertube> {
    if (!innertubeClient) {
        innertubeClient = await Innertube.create();
    }

    return innertubeClient;
}

export function extractYouTubeVideoId(url: string): string | null {
    try {
        const parsed = new URL(url);
        const host = parsed.hostname.replace(/^www\./, "").replace(/^m\./, "");

        if (host === "youtu.be") {
            return parsed.pathname.slice(1).split("/")[0] || null;
        }

        if (host === "youtube.com" || host === "music.youtube.com") {
            if (parsed.pathname === "/watch") {
                return parsed.searchParams.get("v");
            }

            if (parsed.pathname.startsWith("/embed/")) {
                return parsed.pathname.split("/")[2] || null;
            }

            if (parsed.pathname.startsWith("/shorts/")) {
                return parsed.pathname.split("/")[2] || null;
            }
        }

        return null;
    } catch {
        return null;
    }
}

export function isYouTubeUrl(url: string): boolean {
    return extractYouTubeVideoId(url) !== null;
}

function getContainer(mimeType: string | undefined): string {
    if (!mimeType) {
        return "unknown";
    }

    const [
        type,
    ] = mimeType.split(";");
    const [
        ,
        subtype,
    ] = type?.split("/") ?? [];

    return subtype || "unknown";
}

export async function extractYouTubeInfo(url: string): Promise<YouTubeExtractResult> {
    const videoId = extractYouTubeVideoId(url);
    if (!videoId) {
        throw new Error("Not a valid YouTube URL");
    }

    const innertube = await getInnertubeClient();
    const info = await innertube.getInfo(videoId);
    const details = info.basic_info;
    const thumbnails =
        details.thumbnail?.map((item) => ({
            url: item.url,
            width: item.width,
            height: item.height,
        })) ?? [];
    const thumbnail = thumbnails.at(-1)?.url ?? "";

    const formats =
        info.streaming_data?.formats?.map((format) => ({
            itag: format.itag ?? 0,
            quality: format.quality_label || format.quality || "unknown",
            container: getContainer(format.mime_type),
            hasVideo: format.has_video ?? false,
            hasAudio: format.has_audio ?? false,
            contentLength: format.content_length != null ? String(format.content_length) : null,
            mimeType: format.mime_type ?? null,
        })) ?? [];

    const description = info.secondary_info?.description?.toString() ?? details.short_description ?? "";

    const uploadDate =
        details.start_timestamp != null
            ? new Date(Number(details.start_timestamp) * 1000).toISOString().slice(0, 10)
            : "";

    return {
        platform: "youtube",
        videoId: details.id ?? videoId,
        title: details.title ?? "",
        author: details.channel?.name ?? details.author ?? "",
        channelId: details.channel?.id ?? details.channel_id ?? "",
        description,
        lengthSeconds: details.duration ?? 0,
        viewCount: details.view_count != null ? String(details.view_count) : "0",
        uploadDate,
        category: details.category ?? "",
        isLive: details.is_live ?? details.is_live_content ?? false,
        thumbnail,
        thumbnails,
        formats,
    };
}

export async function getYouTubeDownloadUrl(
    url: string,
    itag: number,
): Promise<{ downloadUrl: string; filename: string; mimeType: string }> {
    const videoId = extractYouTubeVideoId(url);
    if (!videoId) {
        throw new Error("Not a valid YouTube URL");
    }

    const innertube = await getInnertubeClient();
    // The default WEB client now requires a PoToken to fetch streams, so its
    // deciphered URLs return HTTP 403. The ANDROID client returns playable URLs
    // without a PoToken, so resolve the download URL through it.
    const info = await innertube.getBasicInfo(videoId, { client: "ANDROID" });
    const details = info.basic_info;

    const allFormats = [
        ...(info.streaming_data?.formats ?? []),
        ...(info.streaming_data?.adaptive_formats ?? []),
    ];

    const format = allFormats.find((f) => f.itag === itag);
    if (!format) {
        throw new Error(`Format with itag ${itag} not found`);
    }

    let downloadUrl = format.url;
    if (!downloadUrl && innertube.session.player) {
        downloadUrl = await format.decipher(innertube.session.player);
    }
    if (!downloadUrl) {
        const cipher = format.signature_cipher || format.cipher;
        if (cipher) {
            const parsed = new URLSearchParams(cipher);
            downloadUrl = parsed.get("url") || undefined;
        }
    }

    if (!downloadUrl) {
        throw new Error(`Download URL not available for format itag ${itag}`);
    }

    const ext = format.mime_type ? (format.mime_type.split(";")[0]?.split("/")[1] ?? "unknown") : "unknown";
    const quality = format.quality_label || format.quality || "unknown";
    const title = details.title ?? "video";
    const safeTitle = title.replace(/[^\w\s-]/g, "").replace(/\s+/g, "_");
    const filename = `${safeTitle}_${quality}.${ext}`;
    const mimeType = format.mime_type
        ? (format.mime_type.split(";")[0] ?? "application/octet-stream")
        : "application/octet-stream";

    return { downloadUrl: downloadUrl as string, filename, mimeType };
}

const extractRouter = new Hono();

extractRouter.post("", async (context) => {
    let body: { url?: string };

    try {
        body = await context.req.json();
    } catch {
        return context.json({ success: false, error: "Invalid JSON body" }, 400);
    }

    const url = body.url?.trim();
    if (!url) {
        return context.json({ success: false, error: "URL required" }, 400);
    }

    try {
        new URL(url);
    } catch {
        return context.json({ success: false, error: "Invalid URL" }, 400);
    }

    if (!isYouTubeUrl(url)) {
        return context.json({ success: false, error: "Only YouTube URLs are supported for now" }, 400);
    }

    try {
        const data = await extractYouTubeInfo(url);
        return context.json({ success: true, data });
    } catch (err: unknown) {
        console.error("YouTube extraction failed:", err);

        const message = err instanceof Error ? err.message : "Failed to extract video info";

        return context.json({ success: false, error: message }, 500);
    }
});

export default extractRouter;
