export default {
    semi: true,
    singleQuote: false,
    tabWidth: 4,
    printWidth: 80,
    trailingComma: "all",
    plugins: [
        "prettier-plugin-multiline-arrays",
    ],
    multilineArraysWrapThreshold: 1,
    multilineArraysLinePattern: "1",
} satisfies import("prettier").Config;
