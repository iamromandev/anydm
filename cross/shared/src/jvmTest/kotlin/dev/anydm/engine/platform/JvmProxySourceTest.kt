package dev.anydm.engine.platform

import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.SystemProxy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The OS's proxy as the JVM's ProxySelector reports it (NET-15). */
class JvmProxySourceTest {
    private class Scripted(
        private val answer: (URI) -> List<Proxy>,
    ) : ProxySelector() {
        val asked = mutableListOf<URI>()

        override fun select(uri: URI): List<Proxy> = answer(uri).also { asked += uri }

        override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: IOException?,
        ) = Unit
    }

    private fun select(
        url: String,
        selector: Scripted,
    ) = runBlocking { JvmProxySource { selector }.select(url) }

    @Test
    fun anHttpProxyForAWebAddress() {
        val selector = Scripted { listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("gw.example.com", 3128))) }

        select("https://files.example.com/a.zip", selector) shouldBe SystemProxy(ProxyType.HTTP, "gw.example.com", 3128)
        selector.asked.single().toString() shouldBe "https://files.example.com/a.zip"
    }

    @Test
    fun aSocksProxyIsSocks5() {
        val selector = Scripted { listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("socks.example.com", 1080))) }

        select("http://x.example.com/", selector) shouldBe SystemProxy(ProxyType.SOCKS5, "socks.example.com", 1080)
    }

    @Test
    fun directMeansNoProxy() {
        select("http://x.example.com/", Scripted { listOf(Proxy.NO_PROXY) }) shouldBe null
    }

    @Test
    fun theFirstUsableProxyWins() {
        val selector =
            Scripted {
                listOf(
                    Proxy.NO_PROXY,
                    Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("a.example.com", 1)),
                    Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("b.example.com", 2)),
                )
            }

        select("http://x.example.com/", selector) shouldBe SystemProxy(ProxyType.HTTP, "a.example.com", 1)
    }

    @Test
    fun sftpAndMagnetAddressesAskAsPlainSockets() {
        val selector = Scripted { listOf(Proxy.NO_PROXY) }

        select("sftp://box.example.com:2222/a", selector)
        select("magnet:?xt=urn:btih:abc", selector)

        selector.asked.map { it.toString() } shouldBe listOf("socket://box.example.com:2222/", "socket://peer.invalid/")
    }

    @Test
    fun anAddressThatCantBeAskedAboutOrAThrowingSelectorIsAFailureNotNoProxy() {
        assertFailsWith<ProxySourceFailure> { select("not a url", Scripted { error("must not be asked") }) }
        assertFailsWith<ProxySourceFailure> { select("http://x.example.com/", Scripted { throw IllegalArgumentException("boom") }) }
    }

    @Test
    fun aHostTheUriSyntaxRejectsIsAskedAboutWithAHyphen() {
        val selector = Scripted { listOf(Proxy.NO_PROXY) }

        select("http://my_host.example.com/a.zip", selector)

        selector.asked.single().host shouldBe "my-host.example.com"
    }
}
