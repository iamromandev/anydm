package dev.anydm.engine.ftp

import dev.anydm.engine.store.HostKey
import dev.anydm.engine.store.HostKeyStore

/** Host keys in a map, matched as the real store does: lowercase host and port. */
internal class FakeHostKeyStore : HostKeyStore {
    val keys = mutableMapOf<Pair<String, Int>, HostKey>()

    override suspend fun get(
        host: String,
        port: Int,
    ): HostKey? = keys[host.lowercase() to port]

    override suspend fun put(
        host: String,
        port: Int,
        key: HostKey,
    ) {
        keys[host.lowercase() to port] = key
    }

    override suspend fun forget(
        host: String,
        port: Int,
    ): Boolean = keys.remove(host.lowercase() to port) != null
}
