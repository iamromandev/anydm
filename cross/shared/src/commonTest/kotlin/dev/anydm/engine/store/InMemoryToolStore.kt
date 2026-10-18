package dev.anydm.engine.store

import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolRecord

internal class InMemoryToolStore : ToolStore {
    private val records = mutableMapOf<Tool, ToolRecord>()

    override suspend fun get(tool: Tool): ToolRecord? = records[tool]

    override suspend fun put(record: ToolRecord) {
        records[record.tool] = record
    }

    override suspend fun all(): List<ToolRecord> = records.values.toList()
}
