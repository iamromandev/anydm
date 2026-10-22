package dev.anydm.engine.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The bonding a download's step runs under (BND-03): the workers of a download in blocks ask it which network each takes.
 * Put around a step by the engine only when the download may be bonded (no proxy, bonding on); code that runs without one
 * connects by the default route.
 */
internal class BondScope(
    val bond: Bond,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BondScope>
}

/** The network the connections made in this coroutine leave by (BND-02); the transports read it, and bind to it or fail. */
class BoundTo(
    val network: NetworkInfo,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BoundTo>
}

/** The network the connections made here must leave by; null when they take the default route. */
suspend fun currentBoundNetwork(): NetworkInfo? = currentCoroutineContext()[BoundTo]?.network

internal suspend fun currentBond(): Bond? = currentCoroutineContext()[BondScope]?.bond

/** Runs [block] with its connections tied to [network]. */
suspend fun <T> withNetwork(
    network: NetworkInfo,
    block: suspend () -> T,
): T = withContext(BoundTo(network)) { block() }

internal suspend fun <T> withBond(
    bond: Bond,
    block: suspend () -> T,
): T = withContext(BondScope(bond)) { block() }
