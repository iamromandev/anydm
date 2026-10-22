package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.ftp.FakeKeychain
import dev.anydm.engine.platform.FakeProxySource
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.ProxyResult
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Which route a download's connections take (NET-15): the choice, the bypass list, the password and the system proxy. */
abstract class ProxyRouterContract : StoreTestBase() {
    private val url = "https://files.example.com/a.zip"
    private val keychain = FakeKeychain()
    private val system = FakeProxySource()
    private var profileCount = 0

    private fun TestScope.router(store: Store = newStore()) = StoreProxyRouter(store, keychain, system) to store

    private suspend fun Store.proxy(
        username: String? = "ann",
        bypass: List<String> = emptyList(),
        type: ProxyType = ProxyType.SOCKS5,
    ) = (proxies.add(NewProxy("Office", type, "proxy.example.com", 1080, username, bypass)) as ProxyResult.Saved).proxy

    private suspend fun Store.job(
        proxy: ProxyChoice = ProxyChoice.Inherit,
        profile: ProxyChoice? = null,
    ): DownloadId {
        val profileId = profile?.let { (profiles.add(NewProfile("P${++profileCount}", proxy = it)) as ProfileResult.Saved).profile.id }
        return downloads.add(NewDownload(DownloadKind.DIRECT, listOf(url), profileId = profileId, proxy = proxy)).id
    }

    private fun via(
        username: String? = "ann",
        password: String? = "pw",
        type: ProxyType = ProxyType.SOCKS5,
    ) = ProxyRoute.Via(ProxyEndpoint(type, "proxy.example.com", 1080, username, password))

    @Test
    fun theDefaultIsTheSystemProxyAndNoneMeansDirect() =
        runTest {
            val (router, store) = router()
            val id = store.job()

            router.route(id, url) shouldBe ProxyRoute.Direct
            system.calls shouldBe listOf(url)

            system.proxy = SystemProxy(ProxyType.HTTP, "gw.example.com", 3128)
            router.route(id, url) shouldBe ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "gw.example.com", 3128))
        }

    @Test
    fun theDownloadBeatsTheProfileBeatsTheGlobalSetting() =
        runTest {
            val (router, store) = router()
            val proxy = store.proxy()
            keychain.entries[proxy.account] = "pw"
            val named = ProxyChoice.Named(proxy.id)

            val inheriting = store.job(profile = ProxyChoice.Inherit)
            router.route(inheriting, url) shouldBe ProxyRoute.Direct // system, none set

            store.settings.update { it.copy(proxy = named) }
            router.route(inheriting, url) shouldBe via()

            val viaProfile = store.job(profile = ProxyChoice.Direct)
            router.route(viaProfile, url) shouldBe ProxyRoute.Direct

            val viaDownload = store.job(proxy = named, profile = ProxyChoice.Direct)
            router.route(viaDownload, url) shouldBe via()

            val direct = store.job(proxy = ProxyChoice.Direct)
            router.route(direct, url) shouldBe ProxyRoute.Direct
        }

    @Test
    fun aHostOnTheBypassListGoesDirect() =
        runTest {
            val (router, store) = router()
            val proxy = store.proxy(bypass = listOf("*.example.com"))
            keychain.entries[proxy.account] = "pw"
            val id = store.job(proxy = ProxyChoice.Named(proxy.id))

            router.route(id, "https://files.example.com:8443/x") shouldBe ProxyRoute.Direct
            router.route(id, "http://user:pw@127.0.0.1/x") shouldBe ProxyRoute.Direct
            router.route(id, "https://other.test/x") shouldBe via()
        }

    @Test
    fun aProxyWithoutAUsernameNeedsNoPassword() =
        runTest {
            val (router, store) = router()
            val proxy = store.proxy(username = null)
            val id = store.job(proxy = ProxyChoice.Named(proxy.id))

            router.route(id, url) shouldBe via(username = null, password = null)
            keychain.gets shouldBe 0
        }

    @Test
    fun aMissingPasswordOrKeychainBlocks() =
        runTest {
            val (router, store) = router()
            val proxy = store.proxy()
            val id = store.job(proxy = ProxyChoice.Named(proxy.id))

            router.route(id, url).shouldBeInstanceOf<ProxyRoute.Blocked>()

            keychain.entries[proxy.account] = "pw"
            router.route(id, url) shouldBe via()

            keychain.failing = true
            router.route(id, url).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }

    @Test
    fun aProxyThatIsGoneBlocks() =
        runTest {
            val (router, store) = router()
            val id = store.job(proxy = ProxyChoice.Named(ProxyId(404)))

            router.route(id, url).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }

    @Test
    fun aDownloadThatIsGoneBlocks() =
        runTest {
            val (router, _) = router()

            router.route(DownloadId(404), url).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }

    @Test
    fun anAddressWithNoHostHasNoBypass() =
        runTest {
            val (router, store) = router()
            val proxy = store.proxy(bypass = listOf("<local>"))
            keychain.entries[proxy.account] = "pw"
            val id = store.job(proxy = ProxyChoice.Named(proxy.id))

            router.route(id, "magnet:?xt=urn:btih:abc") shouldBe via()
        }

    @Test
    fun aSystemLookupThatFailsBlocksInsteadOfGoingDirect() =
        runTest {
            val (router, store) = router()
            val id = store.job()
            system.failure =
                dev.anydm.engine.platform
                    .ProxySourceFailure("selector failed")

            router.route(id, url).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }

    @Test
    fun aProfileThatIsNamedButCantBeReadBlocks() =
        runTest {
            val (router, store) = router()
            val id =
                store.downloads
                    .add(
                        NewDownload(
                            DownloadKind.DIRECT,
                            listOf(url),
                            profileId =
                                dev.anydm.engine.core
                                    .ProfileId(404),
                        ),
                    ).id

            router.route(id, url).shouldBeInstanceOf<ProxyRoute.Blocked>()
        }
}
