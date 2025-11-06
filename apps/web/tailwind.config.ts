import type { Config } from "tailwindcss";

export default {
    content: [
        "./src/**/*.{html,ts,tsx,js,jsx}",
    ],
    theme: {
        extend: {
            colors: {
                primary: {
                    DEFAULT: "#3b82f6", // Tailwind blue-500
                    dark: "#2563eb", // Tailwind blue-600
                },
            },
            fontFamily: {
                sans: [
                    "Inter",
                    "ui-sans-serif",
                    "system-ui",
                ],
            },
        },
    },
    darkMode: "class", // or 'media'
    plugins: [],
} satisfies Config;
