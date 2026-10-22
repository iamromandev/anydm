package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The sockets FTP, SFTP and OkHttp's SOCKS path use (NET-15), against local fake proxies. */
class ProxySocketsTest {
    private fun roundTrip(
        factory: ProxySocketFactory,
        host: String,
        port: Int,
    ) {
        factory.createSocket().use { socket ->
            socket.connect(InetSocketAddress.createUnresolved(host, port), 5000)
            socket.soTimeout = 5000
            socket.getOutputStream().write("hello".toByteArray())
            socket.getOutputStream().flush()
            String(socket.getInputStream().readNBytes(5)) shouldBe "hello"
        }
    }

    @Test
    fun socks5SendsTheNameToTheProxy() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.SOCKS5, target = echo.address).use { proxy ->
                val endpoint = ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port)
                roundTrip(ProxySocketFactory(endpoint), "files.unresolvable.test", 2121)
                proxy.requests.toList() shouldBe listOf("files.unresolvable.test:2121")
            }
        }
    }

    @Test
    fun socks5LogsInWithUsernameAndPassword() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.SOCKS5, "ann" to "pw", echo.address).use { proxy ->
                roundTrip(ProxySocketFactory(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port, "ann", "pw")), "x.test", 22)
                proxy.logins.toList() shouldBe listOf("ann:pw")
            }
        }
    }

    @Test
    fun socks5RefusesAWrongPasswordAsAProxyFailure() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.SOCKS5, "ann" to "pw", echo.address).use { proxy ->
                val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port, "ann", "wrong"))
                val failure = assertFailsWith<ProxyFailure> { roundTrip(factory, "x.test", 22) }
                failure.message!! shouldContain "login"
            }
        }
    }

    @Test
    fun socks5WithoutALoginIsRefusedByAProxyThatWantsOne() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.SOCKS5, "ann" to "pw", echo.address).use { proxy ->
                val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port))
                assertFailsWith<ProxyFailure> { roundTrip(factory, "x.test", 22) }
            }
        }
    }

    @Test
    fun httpConnectTunnelsWithBasicAuth() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.HTTP, "ann" to "pw", echo.address).use { proxy ->
                roundTrip(
                    ProxySocketFactory(ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port, "ann", "pw")),
                    "files.unresolvable.test",
                    21,
                )
                proxy.requests.toList() shouldBe listOf("files.unresolvable.test:21")
                proxy.logins.toList() shouldBe listOf("ann:pw")
            }
        }
    }

    @Test
    fun httpConnectRefusalIsAProxyFailure() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.HTTP, "ann" to "pw", echo.address).use { proxy ->
                val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port))
                val failure = assertFailsWith<ProxyFailure> { roundTrip(factory, "x.test", 21) }
                failure.message!! shouldContain "407"
            }
        }
    }

    @Test
    fun aClosedProxyPortIsAPlainConnectionFailure() {
        val dead = java.net.ServerSocket(0).use { it.localPort }
        val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", dead))
        assertFailsWith<IOException> { roundTrip(factory, "x.test", 21) }
    }

    @Test
    fun theConvenienceOverloadsConnectThroughTheProxyToo() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.SOCKS5, target = echo.address).use { proxy ->
                val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port))
                factory.createSocket("named.test", 9).use { it.isConnected shouldBe true }
                factory.createSocket(InetAddress.getByAddress("byaddress.test", byteArrayOf(0, 0, 0, 0)), 10).use {
                    it.isConnected shouldBe
                        true
                }
                proxy.requests.toList() shouldBe listOf("named.test:9", "byaddress.test:10")
            }
        }
    }

    @Test
    fun anHttpsProxyIsReachedOverTlsAndThenTunnels() {
        val dir =
            java.nio.file.Files
                .createTempDirectory("anydm-tls")
                .toFile()
        try {
            val tls =
                dev.anydm.engine.ftp.TlsFixture
                    .create(dir, "localhost")
            val store =
                java.security.KeyStore.getInstance("JKS").also { k ->
                    tls.keystore.inputStream().use { k.load(it, tls.password.toCharArray()) }
                }
            val keys =
                javax.net.ssl.KeyManagerFactory
                    .getInstance(
                        javax.net.ssl.KeyManagerFactory
                            .getDefaultAlgorithm(),
                    ).also {
                        it.init(store, tls.password.toCharArray())
                    }
            val trust =
                javax.net.ssl.TrustManagerFactory
                    .getInstance(
                        javax.net.ssl.TrustManagerFactory
                            .getDefaultAlgorithm(),
                    ).also { it.init(store) }
            val serverContext =
                javax.net.ssl.SSLContext
                    .getInstance("TLS")
                    .also { it.init(keys.keyManagers, null, null) }
            val clientContext =
                javax.net.ssl.SSLContext
                    .getInstance("TLS")
                    .also { it.init(null, trust.trustManagers, null) }
            EchoServer().use { echo ->
                FakeProxyServer(FakeProxyServer.Kind.HTTP, "ann" to "pw", echo.address, serverContext).use { proxy ->
                    val endpoint = ProxyEndpoint(ProxyType.HTTPS, "localhost", proxy.port, "ann", "pw")

                    roundTrip(ProxySocketFactory(endpoint, clientContext.socketFactory), "files.unresolvable.test", 443)

                    proxy.requests.toList() shouldBe listOf("files.unresolvable.test:443")
                    proxy.logins.toList() shouldBe listOf("ann:pw")
                    // The default trust store doesn't know the proxy's certificate: refused, not sent in the clear.
                    assertFailsWith<IOException> { roundTrip(ProxySocketFactory(endpoint), "files.unresolvable.test", 443) }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLiteralAddressKeepsItsOwnTextAsItsName() {
        namedAddress("203.0.113.9").hostName shouldBe "203.0.113.9"
        namedAddress("[2001:db8::1]").hostName shouldBe "2001:db8::1"
        namedAddress("files.example.com").hostName shouldBe "files.example.com"
    }

    @Test
    fun aHostWithALineBreakIsRefusedBeforeItReachesTheRequest() {
        EchoServer().use { echo ->
            FakeProxyServer(FakeProxyServer.Kind.HTTP, target = echo.address).use { proxy ->
                val factory = ProxySocketFactory(ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port))
                assertFailsWith<ProxyFailure> { roundTrip(factory, "evil.test\r\nX: y", 80) }
                proxy.requests.toList() shouldBe emptyList()
            }
        }
    }
}
