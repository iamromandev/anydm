package dev.anydm.desktop

internal val IsMac: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
