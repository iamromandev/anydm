package dev.anydm.engine.platform

import dev.anydm.engine.net.SystemProxy

/** Answers with [proxy] for every address, or null for a direct one; [calls] has the urls asked. */
internal class FakeProxySource(
    var proxy: SystemProxy? = null,
) : ProxySource {
    val calls = mutableListOf<String>()

    /** Thrown by every call while set. */
    var failure: ProxySourceFailure? = null

    override suspend fun select(url: String): SystemProxy? {
        calls += url
        failure?.let { throw it }
        return proxy
    }
}
