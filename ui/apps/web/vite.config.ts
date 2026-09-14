import { defineConfig } from "vite";
import { qwikVite } from "@qwik.dev/core/optimizer";
import { qwikRouter } from "@qwik.dev/router/vite";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    resolve: { tsconfigPaths: true },
    // Pinned so the root makefile's ui-down/restart (UI_PORT) always find it;
    // strictPort makes a busy port an error instead of a silent move to 3031.
    server: { port: 3030, strictPort: true },
    plugins: [
        tailwindcss(),
        qwikRouter({
            routesDir: "src/route",
        }),
        qwikVite(),
    ],
    build: {
        rollupOptions: {
            output: {
                chunkFileNames: (chunk) => {
                    const name = (chunk.name ?? "chunk").replace(
                        /[\\/]+/g,
                        "_",
                    );
                    return `assets/${name}.js`;
                },
            },
        },
    },
});
