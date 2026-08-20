import * as console from "node:console";

import { Hono } from "hono";

import { extractYouTubeInfo, isYouTubeUrl } from "../service/youtube";

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
