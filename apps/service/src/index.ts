import { Hono } from "hono";
import { cors } from "hono/cors";
import { logger } from "hono/logger";

import downloadRouter from "./route/download";

const app = new Hono();
app.use("*", logger());

app.get("/", (c) => {
    return c.text("Hello Bun!");
});
app.route("/download", downloadRouter);

app.onError((error, context) => {
    console.error(`${error}`);
    return context.text("Something went wrong", 500);
});
app.notFound((context) => {
    return context.text("404 - Page Not Found", 404);
});

export default app;
