package dev.anydm.engine.store

import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SqlHostKeyStore(
    database: AnydmDb,
    private val io: CoroutineDispatcher,
) : HostKeyStore {
    private val queries = database.hostKeyQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun get(
        host: String,
        port: Int,
    ): HostKey? =
        withContext(io) {
            queries.selectByHost(host.lowercase(), port.toLong()).executeAsOneOrNull()?.let { HostKey(it.key_type, it.fingerprint) }
        }

    override suspend fun put(
        host: String,
        port: Int,
        key: HostKey,
    ) {
        withContext(writeContext) { queries.put(host.lowercase(), port.toLong(), key.keyType, key.fingerprint).value }
    }

    override suspend fun forget(
        host: String,
        port: Int,
    ): Boolean = withContext(writeContext) { queries.remove(host.lowercase(), port.toLong()).value > 0 }
}
