package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/** Which proxy types each kind of connection can carry (NET-15): anything else is blocked, never sent direct. */
class ProxyUseTest {
    private fun via(type: ProxyType) = ProxyRoute.Via(ProxyEndpoint(type, "p.example.com", 8080))

    @Test
    fun directAndBlockedPassThrough() {
        for (use in ProxyUse.entries) {
            ProxyRoute.Direct.checkedFor(use) shouldBe ProxyRoute.Direct
            ProxyRoute.Blocked("no").checkedFor(use) shouldBe ProxyRoute.Blocked("no")
        }
    }

    @Test
    fun httpAndSocksCarryWebTrafficAndMedia() {
        for (use in listOf(ProxyUse.HTTP, ProxyUse.MEDIA)) {
            via(ProxyType.HTTP).checkedFor(use) shouldBe via(ProxyType.HTTP)
            via(ProxyType.SOCKS5).checkedFor(use) shouldBe via(ProxyType.SOCKS5)
            via(ProxyType.HTTPS).checkedFor(use).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }
    }

    @Test
    fun remoteFilesTakeEveryType() {
        for (use in listOf(ProxyUse.FTP, ProxyUse.SFTP)) {
            for (type in ProxyType.entries) via(type).checkedFor(use) shouldBe via(type)
        }
    }

    @Test
    fun torrentsTakeSocks5Only() {
        via(ProxyType.SOCKS5).checkedFor(ProxyUse.TORRENT) shouldBe via(ProxyType.SOCKS5)
        via(ProxyType.HTTP).checkedFor(ProxyUse.TORRENT).shouldBeInstanceOf<ProxyRoute.Blocked>()
        via(ProxyType.HTTPS).checkedFor(ProxyUse.TORRENT).shouldBeInstanceOf<ProxyRoute.Blocked>()
    }

    @Test
    fun theEndpointHidesItsPassword() {
        val endpoint = ProxyEndpoint(ProxyType.SOCKS5, "p.example.com", 1080, "ann", "s3cret")
        endpoint.toString().contains("s3cret") shouldBe false
        ProxyRoute.Via(endpoint).toString().contains("s3cret") shouldBe false
    }

    @Test
    fun choicesEncodeAndDecode() {
        for (choice in listOf(
            ProxyChoice.Inherit,
            ProxyChoice.Direct,
            ProxyChoice.System,
            ProxyChoice.Named(
                dev.anydm.engine.core
                    .ProxyId(7),
            ),
        )) {
            ProxyChoice.decode(choice.encode()) shouldBe choice
        }
        ProxyChoice.decode("PROXY:x") shouldBe null
        ProxyChoice.decode("nonsense") shouldBe null
    }

    @Test
    fun aScopeAsksOncePerSchemeAndHost() =
        kotlinx.coroutines.test.runTest {
            val asked = mutableListOf<String>()
            val scope = ProxyScope { url -> ProxyRoute.Direct.also { asked += url } }

            scope.route("https://a.example.com/x")
            scope.route("https://A.example.com:8443/y?z")
            scope.route("https://b.example.com/x")
            scope.route("http://a.example.com/x")
            scope.route("magnet:?xt=1")
            scope.route("magnet:?xt=1")

            asked shouldBe listOf("https://a.example.com/x", "https://b.example.com/x", "http://a.example.com/x", "magnet:?xt=1")
            scope.freshRoute("https://a.example.com/x")
            asked.size shouldBe 5
        }

    @Test
    fun aBackslashEndsTheHostLikeASlash() {
        proxyHostOf("http://evil.com\\@localhost/") shouldBe "evil.com"
    }
}
