package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.net.FakeProxyServer
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.withProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/** HTTP downloads through a proxy (NET-15), against local fake proxies and servers. */
class OkHttpProxyTest {
    private val transport = OkHttpTransport()
    private val id = DownloadId(1)

    private fun routerOf(route: ProxyRoute): Pair<ProxyRouter, MutableList<String>> {
        val asked = CopyOnWriteArrayList<String>()
        return ProxyRouter { _, url -> route.also { asked += url } } to asked
    }

    private fun MockWebServer.respondWith(body: String) = enqueue(MockResponse.Builder().body(body).build())

    private suspend fun get(
        router: ProxyRouter,
        url: String,
    ) = withProxy(router, id) { transport.open(HttpRequest(url)) }

    @Test
    fun socks5CarriesTheRequestAndTheProxyResolvesTheName() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                origin.respondWith("hi")
                FakeProxyServer(FakeProxyServer.Kind.SOCKS5, "ann" to "pw", InetSocketAddress("127.0.0.1", origin.port)).use { proxy ->
                    val (router, _) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port, "ann", "pw")))

                    val response = get(router, "http://files.unresolvable.test:${origin.port}/x")

                    response.status shouldBe 200
                    response.body.readAll().decodeToString() shouldBe "hi"
                    proxy.requests.toList() shouldBe listOf("files.unresolvable.test:${origin.port}")
                    proxy.logins.toList() shouldBe listOf("ann:pw")
                }
            }
        }

    @Test
    fun anHttpProxyIsAnsweredWithBasicAfterA407() =
        runBlocking<Unit> {
            MockWebServer().use { proxy ->
                proxy.start()
                proxy.enqueue(
                    MockResponse
                        .Builder()
                        .code(407)
                        .addHeader("Proxy-Authenticate", "Basic realm=\"p\"")
                        .build(),
                )
                proxy.respondWith("via http proxy")
                val (router, _) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port, "ann", "pw")))

                val response = get(router, "http://files.unresolvable.test/x")

                response.status shouldBe 200
                response.body.readAll().decodeToString() shouldBe "via http proxy"
                proxy.takeRequest().headers["Proxy-Authorization"] shouldBe null
                val second = proxy.takeRequest()
                second.requestLine shouldBe "GET http://files.unresolvable.test/x HTTP/1.1"
                second.headers["Proxy-Authorization"] shouldBe "Basic YW5uOnB3"
            }
        }

    @Test
    fun aProxyThatKeepsRefusingTheLoginIsAProxyFailure() =
        runBlocking<Unit> {
            MockWebServer().use { proxy ->
                proxy.start()
                repeat(3) {
                    proxy.enqueue(
                        MockResponse
                            .Builder()
                            .code(407)
                            .addHeader("Proxy-Authenticate", "Basic realm=\"p\"")
                            .build(),
                    )
                }
                val (router, _) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port, "ann", "wrong")))

                val failure = shouldThrow<HttpIoException> { get(router, "http://files.unresolvable.test/x") }

                failure.kind shouldBe HttpIoKind.PROXY
                classOf(failure.kind) shouldBe dev.anydm.engine.core.ErrorClass.PROXY
            }
        }

    @Test
    fun aWrongSocksPasswordIsAProxyFailureNotATransientOne() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                FakeProxyServer(FakeProxyServer.Kind.SOCKS5, "ann" to "pw", InetSocketAddress("127.0.0.1", origin.port)).use { proxy ->
                    val (router, _) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port, "ann", "bad")))

                    val failure = shouldThrow<HttpIoException> { get(router, "http://files.unresolvable.test:${origin.port}/x") }

                    failure.kind shouldBe HttpIoKind.PROXY
                    failure.message!! shouldContain "login"
                    origin.requestCount shouldBe 0
                }
            }
        }

    @Test
    fun aBlockedRouteConnectsToNothing() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                origin.respondWith("must not be served")
                val (router, _) = routerOf(ProxyRoute.Blocked("no password saved"))

                val failure = shouldThrow<HttpIoException> { get(router, "http://127.0.0.1:${origin.port}/x") }

                failure.kind shouldBe HttpIoKind.PROXY
                failure.message!! shouldContain "no password saved"
                origin.requestCount shouldBe 0
            }
        }

    @Test
    fun anHttpsProxyIsBlockedForOkHttp() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                val (router, _) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTPS, "127.0.0.1", 9)))

                shouldThrow<HttpIoException> { get(router, "http://127.0.0.1:${origin.port}/x") }.kind shouldBe HttpIoKind.PROXY
                origin.requestCount shouldBe 0
            }
        }

    @Test
    fun everyRedirectHopAsksForItsRouteAgain() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                origin.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", "http://second.unresolvable.test:${origin.port}/y")
                        .build(),
                )
                origin.respondWith("done")
                FakeProxyServer(FakeProxyServer.Kind.SOCKS5, target = InetSocketAddress("127.0.0.1", origin.port)).use { proxy ->
                    val (router, asked) = routerOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port)))

                    get(router, "http://first.unresolvable.test:${origin.port}/x").body.readAll().decodeToString() shouldBe "done"

                    asked.toList() shouldBe
                        listOf("http://first.unresolvable.test:${origin.port}/x", "http://second.unresolvable.test:${origin.port}/y")
                    proxy.requests.toList() shouldBe
                        listOf("first.unresolvable.test:${origin.port}", "second.unresolvable.test:${origin.port}")
                }
            }
        }

    @Test
    fun withoutAProxyScopeTheRequestGoesDirect() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                origin.respondWith("direct")

                transport
                    .open(HttpRequest("http://127.0.0.1:${origin.port}/x"))
                    .body
                    .readAll()
                    .decodeToString() shouldBe "direct"
            }
        }

    @Test
    fun aDirectRouteNeverAsksTheJvmsOwnProxySelector() =
        runBlocking<Unit> {
            MockWebServer().use { origin ->
                origin.start()
                origin.respondWith("direct")
                val asked = CopyOnWriteArrayList<String>()
                val saved = java.net.ProxySelector.getDefault()
                // As if the system settings named a proxy: asking it would be a leak.
                java.net.ProxySelector.setDefault(
                    object : java.net.ProxySelector() {
                        override fun select(uri: java.net.URI): List<java.net.Proxy> {
                            // Only OkHttp's client counts: other threads of the test JVM (fake servers) connect too.
                            if (Throwable().stackTrace.any { it.className.startsWith("okhttp3.internal.connection.") }) {
                                asked +=
                                    uri.toString()
                            }
                            return listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 9)))
                        }

                        override fun connectFailed(
                            uri: java.net.URI?,
                            sa: java.net.SocketAddress?,
                            ioe: java.io.IOException?,
                        ) = Unit
                    },
                )
                try {
                    val (router, _) = routerOf(ProxyRoute.Direct)
                    get(router, "http://127.0.0.1:${origin.port}/x").body.readAll().decodeToString() shouldBe "direct"
                    asked shouldBe emptyList()
                } finally {
                    java.net.ProxySelector.setDefault(saved)
                }
            }
        }
}
