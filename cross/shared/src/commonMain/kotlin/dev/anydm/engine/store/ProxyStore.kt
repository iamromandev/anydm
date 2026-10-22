package dev.anydm.engine.store

import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.ProxyEntry

/** The saved proxies (NET-15), without their passwords: those are in the keychain. */
interface ProxyStore {
    /** By name. */
    suspend fun list(): List<ProxyEntry>

    suspend fun get(id: ProxyId): ProxyEntry?

    suspend fun add(new: NewProxy): ProxyResult

    /** Saves [proxy] under its id; [ProxyResult.NotFound] if it is gone, [ProxyResult.NameTaken] on a name clash. */
    suspend fun update(proxy: ProxyEntry): ProxyResult

    /** Removes the proxy unless a download, a profile or the global setting still names it. */
    suspend fun remove(id: ProxyId): ProxyRemoval
}

/** What saving a proxy came to. */
sealed interface ProxyResult {
    data class Saved(
        val proxy: ProxyEntry,
    ) : ProxyResult

    /** Another proxy has the name. */
    data object NameTaken : ProxyResult

    /** An update of a proxy that no longer exists. */
    data object NotFound : ProxyResult
}

/** What removing a proxy came to. It is never removed from under a job, which would send its traffic somewhere else. */
sealed interface ProxyRemoval {
    data object Removed : ProxyRemoval

    data object NotFound : ProxyRemoval

    /** The proxy is gone, but its password is still in the keychain, which couldn't be reached; the engine reports this, the store never does. */
    data object PasswordLeft : ProxyRemoval

    /** [downloads] and [profiles] name it; [global] is true when the global setting does. */
    data class InUse(
        val downloads: Int,
        val profiles: Int,
        val global: Boolean,
    ) : ProxyRemoval
}
