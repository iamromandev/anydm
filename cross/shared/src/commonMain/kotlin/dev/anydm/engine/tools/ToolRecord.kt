package dev.anydm.engine.tools

import kotlin.time.Instant

/** The installed copy of a [tool]: its [version], the folder it lives in, and when a newer one was last looked for. */
data class ToolRecord(
    val tool: Tool,
    val version: String,
    val path: String,
    val checkedAt: Instant?,
)
