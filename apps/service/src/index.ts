import { Hono } from "hono";
import { cors } from "hono/cors";
import { logger } from "hono/logger";

import extractRouter from "./route/extract";
import downloadRouter from "./route/download";

const app = new Hono();
app.use("*", logger());
app.use(
    "*",
    cors({
        origin: [
            "http://localhost:5173",
            "http://127.0.0.1:5173",
        ],
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
