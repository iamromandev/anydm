package dev.anydm.engine.net

import java.io.IOException
import javax.net.SocketFactory

/** A socket could not be tied to its network (BND-02). The connection is not made at all: it never falls back to the default route. */
class BindFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Sockets that leave by one network (BND-02). The desktop ties them with native calls; Android would hand out
 * `Network.socketFactory`. A factory binds every socket it makes, including the ones a library makes by itself (redirect
 * hops, FTP data connections), and throws [BindFailure] when it can't.
 */
fun interface BoundSocketFactories {
    fun factoryFor(network: NetworkInfo): SocketFactory
}

/** The [BindFailure] behind [e], if that is why it failed. */
fun bindFailureOf(e: Throwable): BindFailure? =
    generateSequence(e) { it.cause.takeUnless { c -> c === it } }.take(8).filterIsInstance<BindFailure>().firstOrNull()
