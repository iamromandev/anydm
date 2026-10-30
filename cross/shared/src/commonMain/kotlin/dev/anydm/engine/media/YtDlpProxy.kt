package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyScope
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.checkedFor
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.currentCoroutineContext

/**
 * The proxy as yt-dlp is told about it (NET-15): [args] for yt-dlp's command line and [environment] for its process. With a
 * login the address goes in the environment, because a command line is visible to every user of the machine. The
 * environment always sets every spelling of the proxy variables (empty for Direct) and empties `no_proxy`, so nothing the
 * user's own environment holds can take over. Direct is also `--proxy ""`.
 */
internal class YtDlpProxy(
    val args: List<String>,
    val environment: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The proxy for [route], or the failure that stops the job: a blocked route, or a type yt-dlp can't use. */
        fun of(route: ProxyRoute): StepResult<YtDlpProxy> =
            when (val checked = route.checkedFor(ProxyUse.MEDIA)) {
                is ProxyRoute.Blocked -> {
                    StepResult.Failed(ErrorClass.PROXY, checked.reason)
                }

                ProxyRoute.Direct -> {
                    StepResult.Ok(YtDlpProxy(listOf("--proxy", ""), environment(null)))
                }

                is ProxyRoute.Via -> {
                    val endpoint = checked.endpoint
                    val scheme = if (endpoint.type == ProxyType.SOCKS5) "socks5h" else "http"
                    val host = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
                    val login =
                        endpoint.username
                            ?.let { user ->
                                encode(user) + (endpoint.password?.let { ":" + encode(it) } ?: "") + "@"
                            }.orEmpty()
                    val url = "$scheme://$login$host:${endpoint.port}"
                    // The address is in the environment too, for ffmpeg, which yt-dlp may start for a stream and which reads
                    // `http_proxy`; the login is in nothing else.
                    StepResult.Ok(YtDlpProxy(if (login.isEmpty()) listOf("--proxy", url) else emptyList(), environment(url)))
                }
            }

        // Every spelling of the proxy variables is set, so none the user's own environment holds wins, and the no-proxy lists
        // are emptied so no host is let out of the proxy (or into it) behind the engine's back. Empty means off.
        private fun environment(url: String?): Map<String, String> =
            buildMap {
                for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
                    put(name, url.orEmpty())
                    put(name.uppercase(), url.orEmpty())
                }
                put("no_proxy", "")
                put("NO_PROXY", "")
            }

        /** The proxy that [url] takes in this coroutine (see ProxyScope); outside a download's scope yt-dlp is left as it is. */
        suspend fun forUrl(url: String): StepResult<YtDlpProxy> =
            if (currentCoroutineContext()[ProxyScope] ==
                null
            ) {
                StepResult.Ok(YtDlpProxy(emptyList()))
            } else {
                of(currentProxyRoute(url, ProxyUse.MEDIA))
            }

        // Percent-encodes everything but RFC 3986's unreserved characters, so ':', '@' and '/' in a login can't end it early.
        private fun encode(text: String): String =
            buildString {
                for (byte in text.encodeToByteArray()) {
                    val c = byte.toInt() and 0xFF
                    if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in "-._~") {
                        append(c.toChar())
                    } else {
                        append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                    }
                }
            }
    }
}
