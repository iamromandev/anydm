import { Hono } from "hono";
import { cors } from "hono/cors";
import { logger } from "hono/logger";

import extractRouter from "./route/extract";
import downloadRouter from "./route/download";

const allowedOrigins = (process.env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);

const isDev = process.env.ENV === "dev";

function resolveOrigin(origin: string): string | null {
    if (allowedOrigins.length > 0 && allowedOrigins.includes(origin)) {
        return origin;
    }

    if (isDev) {
        try {
            const url = new URL(origin);
            const hostname = url.hostname;
            if (hostname === "localhost" || hostname === "127.0.0.1") {
                return origin;
            }
        } catch {
            // invalid origin — reject
        }
    }

    return null;
}

const app = new Hono();
app.use("*", logger());
app.use(
    "*",
    cors({
        origin: resolveOrigin,
        allowMethods: [
            "GET",
            "POST",
            "OPTIONS",
        ],
    }),
);

app.get("/", (c) => {
    return c.text("Hello Bun!");
});
app.route("/extract", extractRouter);
app.route("/download", downloadRouter);

app.onError((error, context) => {
    console.error(`${error}`);
    return context.text("Something went wrong", 500);
});
app.notFound((context) => {
    return context.text("404 - Page Not Found", 404);
});

export default app;
