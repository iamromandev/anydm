export default {
    semi: true,
    singleQuote: false,
    tabWidth: 4,
    printWidth: 120,
    trailingComma: "all",
    plugins: [
        "prettier-plugin-multiline-arrays",
    ],
    multilineArraysWrapThreshold: 1,
    multilineArraysLinePattern: "1",
} satisfies import("prettier").Config;
