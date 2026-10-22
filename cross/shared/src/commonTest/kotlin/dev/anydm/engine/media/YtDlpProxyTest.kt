package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/** What yt-dlp is told about the proxy (NET-15): a password goes in its environment, never on its command line. */
class YtDlpProxyTest {
    private companion object {
        val EVERY_NAME = setOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "no_proxy", "NO_PROXY")
    }

    private fun of(route: ProxyRoute) = YtDlpProxy.of(route).shouldBeInstanceOf<StepResult.Ok<YtDlpProxy>>().value

    @Test
    fun directSwitchesTheProxyOffEvenWhenTheEnvironmentHasOne() {
        val proxy = of(ProxyRoute.Direct)

        proxy.args shouldBe listOf("--proxy", "")
        proxy.environment.keys shouldBe EVERY_NAME
        proxy.environment.values.toSet() shouldBe setOf("")
    }

    @Test
    fun aProxyWithNoLoginIsAnArgument() {
        of(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "gw.example.com", 3128))).let {
            it.args shouldBe listOf("--proxy", "http://gw.example.com:3128")
            it.environment["http_proxy"] shouldBe "http://gw.example.com:3128"
        }
        of(ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "gw.example.com", 1080))).args shouldBe
            listOf("--proxy", "socks5h://gw.example.com:1080")
    }

    @Test
    fun aLoginGoesInTheEnvironmentAndNotOnTheCommandLine() {
        val proxy = of(ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "gw.example.com", 1080, "ann", "p@ss/w:rd")))

        proxy.args shouldBe emptyList()
        val url = "socks5h://ann:p%40ss%2Fw%3Ard@gw.example.com:1080"
        proxy.environment
            .filterKeys {
                it.endsWith(
                    "_proxy",
                    ignoreCase = true,
                ) && !it.startsWith("no", ignoreCase = true)
            }.values
            .toSet() shouldBe
            setOf(url)
        proxy.environment.keys shouldBe EVERY_NAME
        // The user's own no-proxy list is emptied, so no host is let out of the proxy.
        proxy.environment["no_proxy"] shouldBe ""
        proxy.environment["NO_PROXY"] shouldBe ""
        proxy.args.joinToString(" ").contains("p@ss") shouldBe false
    }

    @Test
    fun aUsernameWithNoPasswordIsStillInTheEnvironment() {
        of(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "gw.example.com", 3128, "ann"))).environment["all_proxy"] shouldBe
            "http://ann@gw.example.com:3128"
    }

    @Test
    fun aBlockedRouteOrAnHttpsProxyFailsAsAProxyFailure() {
        val blocked = YtDlpProxy.of(ProxyRoute.Blocked("no password saved")).shouldBeInstanceOf<StepResult.Failed>()
        blocked.errorClass shouldBe ErrorClass.PROXY
        blocked.message shouldBe "no password saved"

        YtDlpProxy
            .of(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTPS, "gw.example.com", 443)))
            .shouldBeInstanceOf<StepResult.Failed>()
            .errorClass shouldBe ErrorClass.PROXY
    }

    @Test
    fun anIpv6HostIsBracketed() {
        of(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "::1", 3128))).args shouldBe listOf("--proxy", "http://[::1]:3128")
    }
}
