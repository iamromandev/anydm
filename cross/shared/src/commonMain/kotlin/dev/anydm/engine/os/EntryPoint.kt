package dev.anydm.engine.os

/** Where a link came from (OSI-07). */
enum class EntryPoint {
    /** The browser extension, through the bridge (OSI-01). */
    BRIDGE,

    /** The OS opened the app with it: a `magnet:` link, a `.torrent` file or an `anydm://` link. */
    OS_LINK,

    /** A second launch handed it to the running app (OSI-08). */
    SECOND_LAUNCH,

    /** The user pressed the global hotkey with the link on the clipboard (OSI-06). */
    HOTKEY,
}
