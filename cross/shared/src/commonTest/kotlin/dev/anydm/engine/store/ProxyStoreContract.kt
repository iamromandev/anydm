package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What every [ProxyStore] must do (NET-15), each test on a new database from [newDriver]. */
abstract class ProxyStoreContract : StoreTestBase() {
    private fun new(name: String = "Office") =
        NewProxy(name, ProxyType.SOCKS5, "proxy.example.com", 1080, "ann", listOf("*.corp.test", "10.0.0.0/8"))

    private fun ProxyResult.saved() = shouldBeInstanceOf<ProxyResult.Saved>().proxy

    @Test
    fun aProxyRoundTripsWithItsBypassList() =
        runTest {
            val proxies = newStore().proxies

            val saved = proxies.add(new()).saved()

            saved shouldBe
                ProxyEntry(saved.id, "Office", ProxyType.SOCKS5, "proxy.example.com", 1080, "ann", listOf("*.corp.test", "10.0.0.0/8"))
            proxies.get(saved.id) shouldBe saved
            proxies.list() shouldBe listOf(saved)
            saved.account shouldBe "anydm-proxy:${saved.id.value}"
        }

    @Test
    fun aNameIsUnique() =
        runTest {
            val proxies = newStore().proxies
            val first = proxies.add(new("A")).saved()
            val second = proxies.add(new("B")).saved()

            proxies.add(new("A")) shouldBe ProxyResult.NameTaken
            proxies.update(second.copy(name = "A")) shouldBe ProxyResult.NameTaken
            proxies.update(first.copy(host = "other.example.com")).saved().host shouldBe "other.example.com"
            proxies.update(first.copy(id = ProxyId(99))) shouldBe ProxyResult.NotFound
        }

    @Test
    fun anUnusedProxyIsRemoved() =
        runTest {
            val proxies = newStore().proxies
            val saved = proxies.add(new()).saved()

            proxies.remove(saved.id) shouldBe ProxyRemoval.Removed
            proxies.get(saved.id) shouldBe null
            proxies.remove(saved.id) shouldBe ProxyRemoval.NotFound
        }

    @Test
    fun aProxyANamedByADownloadAProfileOrTheGlobalSettingStays() =
        runTest {
            val store = newStore()
            val saved = store.proxies.add(new()).saved()
            val choice = ProxyChoice.Named(saved.id)
            store.downloads.add(NewDownload(DownloadKind.DIRECT, listOf("http://example.com/a"), proxy = choice))
            store.profiles.add(NewProfile("Work", proxy = choice))
            store.settings.update { it.copy(proxy = choice) }

            store.proxies.remove(saved.id) shouldBe ProxyRemoval.InUse(downloads = 1, profiles = 1, global = true)

            store.settings.update { it.copy(proxy = ProxyChoice.Direct) }
            store.proxies.remove(saved.id) shouldBe ProxyRemoval.InUse(downloads = 1, profiles = 1, global = false)
        }

    @Test
    fun choicesRoundTripOnDownloadsProfilesAndSettings() =
        runTest {
            val store = newStore()
            val named =
                ProxyChoice.Named(
                    store.proxies
                        .add(new())
                        .saved()
                        .id,
                )

            for (choice in listOf(ProxyChoice.Inherit, ProxyChoice.Direct, ProxyChoice.System, named)) {
                val download = store.downloads.add(NewDownload(DownloadKind.DIRECT, listOf("http://example.com/a"), proxy = choice))
                store.downloads.get(download.id)!!.proxy shouldBe choice
                store.downloads.update(download.copy(proxy = ProxyChoice.Direct)) shouldBe true
                store.downloads.get(download.id)!!.proxy shouldBe ProxyChoice.Direct
            }
            val profile = (store.profiles.add(NewProfile("P", proxy = named)) as dev.anydm.engine.files.ProfileResult.Saved).profile
            store.profiles.get(profile.id)!!.proxy shouldBe named
        }

    @Test
    fun theGlobalChoiceDefaultsToTheSystemAndNeverInherits() =
        runTest {
            val store = newStore()
            store.settings.load().proxy shouldBe ProxyChoice.System

            store.settings.update { it.copy(proxy = ProxyChoice.Direct) }.proxy shouldBe ProxyChoice.Direct
            store.settings.load().proxy shouldBe ProxyChoice.Direct
            store.settings.update { it.copy(proxy = ProxyChoice.System) }
            store.settings.load().proxy shouldBe ProxyChoice.System
        }
}
