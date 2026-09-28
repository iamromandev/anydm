package dev.anydm.desktop.theme

fun isMac(os: String = System.getProperty("os.name")): Boolean = os.lowercase().startsWith("mac")

/** A shortcut as the OS writes it: "⌘S" on macOS, "Ctrl+S" elsewhere. */
fun shortcutLabel(
    key: String,
    os: String = System.getProperty("os.name"),
): String = if (isMac(os)) "⌘$key" else "Ctrl+$key"
