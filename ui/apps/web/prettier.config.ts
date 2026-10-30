export default {
    semi: true,
    singleQuote: false,
    tabWidth: 4,
    printWidth: 80,
    trailingComma: "all",
    plugins: [
        "prettier-plugin-tailwindcss",
        "prettier-plugin-multiline-arrays",
    ],
    tailwindConfig: "./tailwind.config.ts",
    multilineArraysWrapThreshold: 1,
    multilineArraysLinePattern: "1",
} satisfies import("prettier").Config;
