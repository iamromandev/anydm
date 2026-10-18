package dev.anydm.engine.core

/** Why an [Attempt] failed. Each entry says what went wrong, not what happens next. */
enum class ErrorClass {
    /** A passing network or server problem: timeout, reset connection, 5xx. */
    TRANSIENT,

    /** The server asked the client to slow down (429, or a quota). */
    RATE_LIMITED,

    /** The server refused the credentials or the session (401, 403). */
    AUTH,

    /** The source no longer exists at this location (404, 410). */
    GONE,

    /** There is no room left to write on the target disk. */
    DISK_FULL,

    /** The site withholds this format from this client (for example, needs a login or a premium plan). */
    FORMAT_WITHHELD,

    /** The bytes received don't match the expected size or checksum. */
    INTEGRITY,

    /** The job needs a runtime tool that isn't installed yet; the failure's message is the tool's id (BIN-01). */
    TOOL_MISSING,
}
