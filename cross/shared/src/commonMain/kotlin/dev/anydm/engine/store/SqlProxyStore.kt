package dev.anydm.engine.store

import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.anydm.engine.store.db.Proxy as ProxyRow

internal class SqlProxyStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : ProxyStore {
    private val queries = database.proxyQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun list(): List<ProxyEntry> = withContext(io) { queries.selectAll().executeAsList().mapNotNull { it.toEntry() } }

    override suspend fun get(id: ProxyId): ProxyEntry? = withContext(io) { queries.selectById(id).executeAsOneOrNull()?.toEntry() }

    override suspend fun add(new: NewProxy): ProxyResult =
        withContext(writeContext) {
            database.transactionWithResult {
                if (queries.selectByName(new.name).executeAsOneOrNull() != null) {
                    ProxyResult.NameTaken
                } else {
                    queries.insert(new.name, new.type, new.host, new.port, new.username, new.bypass.joinToString("\n"))
                    ProxyResult.Saved(checkNotNull(queries.selectLastInserted().executeAsOne().toEntry()))
                }
            }
        }

    override suspend fun update(proxy: ProxyEntry): ProxyResult =
        withContext(writeContext) {
            database.transactionWithResult {
                when {
                    queries.selectById(proxy.id).executeAsOneOrNull() == null -> {
                        ProxyResult.NotFound
                    }

                    queries.selectByName(proxy.name).executeAsOneOrNull()?.let { it.id != proxy.id } == true -> {
                        ProxyResult.NameTaken
                    }

                    else -> {
                        queries.update(
                            proxy.name,
                            proxy.type,
                            proxy.host,
                            proxy.port,
                            proxy.username,
                            proxy.bypass.joinToString("\n"),
                            proxy.id,
                        )
                        ProxyResult.Saved(proxy)
                    }
                }
            }
        }

    override suspend fun remove(id: ProxyId): ProxyRemoval =
        withContext(writeContext) {
            database.transactionWithResult {
                if (queries.selectById(id).executeAsOneOrNull() == null) {
                    ProxyRemoval.NotFound
                } else {
                    val choice = ProxyChoice.Named(id)
                    val downloads =
                        database.downloadQueries
                            .countProxy(choice)
                            .executeAsOne()
                            .toInt()
                    val profiles =
                        database.profileQueries
                            .countProxy(choice)
                            .executeAsOne()
                            .toInt()
                    val global =
                        database.settingQueries.selectAll().executeAsList().any {
                            it.name == GLOBAL_PROXY &&
                                it.data_ == choice.encode()
                        }
                    if (downloads + profiles > 0 || global) {
                        ProxyRemoval.InUse(downloads, profiles, global)
                    } else {
                        queries.delete(id)
                        ProxyRemoval.Removed
                    }
                }
            }
        }

    // A row that breaks today's rules (a host this version rejects) is no proxy: it must not stop a job, or the engine.
    private fun ProxyRow.toEntry(): ProxyEntry? =
        runCatching { ProxyEntry(id, name, type, host, port, username, bypass.lines().filter { it.isNotBlank() }) }.getOrNull()
}
