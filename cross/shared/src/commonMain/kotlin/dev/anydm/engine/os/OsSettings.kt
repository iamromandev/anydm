package dev.anydm.engine.os

/** The two settings the OS-wide helpers follow (OSI-05, OSI-06), as the services read them: a flow of the current values. */
data class OsSettings(
    val clipboardWatch: Boolean = false,
    val globalHotkey: Boolean = false,
)
