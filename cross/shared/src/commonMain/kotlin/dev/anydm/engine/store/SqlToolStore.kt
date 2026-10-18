package dev.anydm.engine.store

import dev.anydm.engine.store.db.AnydmDb
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.anydm.engine.store.db.Tool as ToolRow

internal class SqlToolStore(
    database: AnydmDb,
    private val io: CoroutineDispatcher,
) : ToolStore {
    private val queries = database.toolQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun get(tool: Tool): ToolRecord? = withContext(io) { queries.selectByName(tool.id).executeAsOneOrNull()?.toRecord() }

    override suspend fun put(record: ToolRecord) {
        withContext(writeContext) { queries.put(record.tool.id, record.version, record.path, record.checkedAt) }
    }

    override suspend fun all(): List<ToolRecord> = withContext(io) { queries.selectAll().executeAsList().mapNotNull { it.toRecord() } }

    private fun ToolRow.toRecord(): ToolRecord? = Tool.byId(name)?.let { ToolRecord(it, version, path, checked_at) }
}
