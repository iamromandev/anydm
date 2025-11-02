import { serve } from "bun";
import { readFileSync } from "fs";
import { join } from "path";

const indexHTML = readFileSync(join(import.meta.dir, "index.html"), "utf-8");

serve({
  port: 3000,
  fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/") {
      return new Response(indexHTML, { headers: { "Content-Type": "text/html" } });
    }
    if (url.pathname === "/main.js") {
      const js = readFileSync(join(import.meta.dir, "dist/main.js"), "utf-8");
      return new Response(js, { headers: { "Content-Type": "application/javascript" } });
    }
    return new Response("Not found", { status: 404 });
  },
});