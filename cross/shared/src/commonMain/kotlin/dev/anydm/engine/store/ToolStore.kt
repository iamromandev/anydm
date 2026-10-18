package dev.anydm.engine.store

import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolRecord

/** The runtime tools the engine has installed (BIN-01). */
interface ToolStore {
    suspend fun get(tool: Tool): ToolRecord?

    /** Replaces the record of the same tool. */
    suspend fun put(record: ToolRecord)

    /** Every record whose tool this version knows. */
    suspend fun all(): List<ToolRecord>
}
