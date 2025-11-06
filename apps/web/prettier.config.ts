export default {
    semi: true,
    singleQuote: false,
    tabWidth: 4,
    trailingComma: "all",
    plugins: ["prettier-plugin-tailwindcss"],
    tailwindConfig: "./tailwind.config.ts",
} satisfies import("prettier").Config;
