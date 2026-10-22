package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ProfileId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Whose proxy settings the code running in this coroutine follows (NET-15): the engine puts one around each step it runs
 * for a download (probe, transfer, finalize, relink) and around the listing of a playlist, so every connection that
 * step opens, in any worker it starts, asks for its route, and a redirect to another host asks again. Code outside a
 * download, such as fetching a runtime tool, has none and connects directly.
 */
class ProxyScope(
    private val routeOf: suspend (String) -> ProxyRoute,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProxyScope>

    // A route depends on the scheme and host only, so a step with many requests to one host (blocks, fragments) asks once. A
    // change of any setting restarts the step, which gets a scope of its own.
    private val known = mutableMapOf<String, ProxyRoute>()
    private val lock = Mutex()

    suspend fun route(url: String): ProxyRoute {
        val key = routeKey(url)
        lock.withLock { known[key] }?.let { return it }
        return routeOf(url).also { found -> lock.withLock { known[key] = found } }
    }

    /** The route as the settings give it now, not the one this scope remembers: for code that outlives a step, like a seed. */
    suspend fun freshRoute(url: String): ProxyRoute = routeOf(url)

    private fun routeKey(url: String): String =
        proxyHostOf(url)?.let { url.substringBefore("://").lowercase() + "://" + it.lowercase() } ?: url
}

/** Runs [block] with [downloadId]'s proxy settings (a [ProxyScope]) around everything it connects. */
suspend fun <T> withProxy(
    router: ProxyRouter,
    downloadId: DownloadId,
    block: suspend () -> T,
): T = withContext(ProxyScope { router.route(downloadId, it) }) { block() }

/** Runs [block] with the settings a download that names [choice] and [profile] would have, for work that precedes the download. */
suspend fun <T> withProxyForNew(
    router: ProxyRouter,
    choice: ProxyChoice,
    profile: ProfileId?,
    block: suspend () -> T,
): T = withContext(ProxyScope { router.routeNew(choice, profile, it) }) { block() }

/** The route a connection to [url] for [use] takes in this coroutine: direct without a [ProxyScope]; [ProxyRoute.Blocked] if its proxy can't carry [use]. */
suspend fun currentProxyRoute(
    url: String,
    use: ProxyUse,
): ProxyRoute = currentCoroutineContext()[ProxyScope]?.route(url)?.checkedFor(use) ?: ProxyRoute.Direct
