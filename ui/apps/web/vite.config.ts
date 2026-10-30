import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    resolve: { tsconfigPaths: true },
    // `PUBLIC_API_URL` is the name this app has always read its API base from.
    envPrefix: [
        "VITE_",
        "PUBLIC_",
    ],
    // Pinned so the root makefile's ui-down/restart (UI_PORT) always find it;
    // strictPort makes a busy port an error instead of a silent move to 3031.
    server: { port: 3030, strictPort: true },
    plugins: [
        react(),
        tailwindcss(),
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
