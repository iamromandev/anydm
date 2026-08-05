import { defineConfig } from "vite";
import { qwikVite } from "@qwik.dev/core/optimizer";
import { qwikRouter } from "@qwik.dev/router/vite";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    resolve: { tsconfigPaths: true },
    plugins: [
        tailwindcss(),
        qwikRouter({
            routesDir: "src/route",
        }),
        qwikVite(),
    ],
});
