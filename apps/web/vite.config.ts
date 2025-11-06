import { defineConfig } from "vite";
import { qwikVite } from "@qwik.dev/core/optimizer";
import tsconfigPaths from "vite-tsconfig-paths";
import { qwikRouter } from "@qwik.dev/router/vite";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    plugins: [
        tailwindcss(),
        qwikRouter({
            routesDir: "src/route",
        }),
        qwikVite(),
        tsconfigPaths(),
    ],
});
