package dev.anydm.engine.store

import dev.anydm.engine.ftp.Keychain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Where the cookies of a download are kept (OSI-03). A browser's cookies are a login, so they go to the OS [keychain] and
 * the database holds only a marker (`sealed:<id>`) in their place. [open] gives the cookies back from a marker. With no
 * keychain, or one that fails, the cookies are held in memory for this run only: [onNotSaved] says so (once per run), and
 * after a restart the marker opens to [Opened.Lost], so the download fails as needing a login instead of running without
 * its cookies. A keychain that is only unreachable now (locked, not up yet) opens to [Opened.Unavailable], which is not
 * the same as gone.
 */
class CookieVault(
    private val keychain: Keychain?,
) {
    /** The marker to keep in the row, and whether the keychain really has the cookies now. */
    data class Sealed(
        val marker: String,
        val inKeychain: Boolean,
    )

    /** What a stored cookies value opens to. */
    sealed interface Opened {
        /** Nothing was stored. */
        data object None : Opened

        data class Text(
            val cookies: String,
        ) : Opened {
            override fun toString(): String = "Text(***)"
        }

        /** A marker that nothing holds: the keychain never had them, or lost them. */
        data object Lost : Opened

        /** The keychain can't be asked now. The cookies may well be there. */
        data object Unavailable : Opened
    }

    /** Called, once per run, when cookies could only be kept in memory. Set by the engine. */
    @Volatile var onNotSaved: (() -> Unit)? = null

    private val lock = Mutex()
    private val held = mutableMapOf<String, String>()
    private var told = false

    /** Keeps [cookies] and returns the marker for the row. A call that can't reach the keychain keeps them in memory. */
    suspend fun seal(cookies: String): Sealed {
        val id = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val stored = putInKeychain(account(id), cookies)
        lock.withLock { held[id] = cookies }
        if (!stored) tellOnce()
        return Sealed(PREFIX + id, stored)
    }

    /** What [stored], a value of the cookies column, opens to. Cookies not sealed yet (a row from before) are themselves. */
    suspend fun open(stored: String?): Opened {
        if (stored == null) return Opened.None
        if (stored == LOST) return Opened.Lost
        if (!isSealed(stored)) return Opened.Text(stored)
        val id = stored.removePrefix(PREFIX)
        // Under the lock, so a forget that comes between the read and the keep can't leave a removed id's cookies behind.
        return lock.withLock {
            held[id]?.let { return@withLock Opened.Text(it) }
            val found =
                try {
                    keychain?.get(account(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withLock Opened.Unavailable
                } ?: return@withLock Opened.Lost
            held[id] = found
            Opened.Text(found)
        }
    }

    /** Drops the cookies [stored] points to, from memory and the keychain. Anything else is left alone. */
    suspend fun forget(stored: String?) {
        if (stored == null || stored == LOST || !isSealed(stored)) return
        val id = stored.removePrefix(PREFIX)
        lock.withLock { held.remove(id) }
        removeFromKeychain(account(id))
    }

    private suspend fun tellOnce() {
        val first = lock.withLock { !told.also { told = true } }
        if (first) onNotSaved?.invoke()
    }

    private suspend fun putInKeychain(
        account: String,
        cookies: String,
    ): Boolean =
        try {
            (keychain ?: return false).put(account, cookies)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun removeFromKeychain(account: String) {
        try {
            keychain?.remove(account)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing to remove from a keychain that can't be reached.
        }
    }

    private fun account(id: String) = "cookies:$id"

    companion object {
        private const val PREFIX = "sealed:"

        /** The marker of cookies that were lost before this copy of the context was made: it opens to [Opened.Lost] for good. */
        const val LOST = "sealed:lost"

        fun isSealed(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)
    }
}
