package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.ftp.FakeKeychain
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.PasswordChange
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyCommandResult
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.StoreProxyRouter
import dev.anydm.engine.net.SystemProxy
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.platform.FakeProxySource
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.ProxyRemoval
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Proxies (#543, NET-15): the engine's commands, the route each job's steps see, and the restart of running connections when a
 * choice changes, on a 1 MiB file at 16 KiB/s, on a new database from [newDriver] for each test.
 */
abstract class EngineProxyContract : StoreTestBase() {
    private val harnesses = mutableListOf<EngineHarness>()
    private val keychain = FakeKeychain()
    private val system = FakeProxySource()

    /** The route the transfer of each run saw, in order. */
    private val seen = mutableListOf<ProxyRoute>()

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun engineTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
                harnesses.clear()
            }
        }

    private fun TestScope.harness(withKeychain: Boolean = true): EngineHarness {
        val store = newStore()
        val protocol =
            FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))).also {
                it.onTransfer = { seen += currentProxyRoute("https://example.com/file-1.zip", ProxyUse.HTTP) }
            }
        return EngineHarness(
            this,
            store,
            protocol,
            proxyRouter = StoreProxyRouter(store, keychain, system),
            proxyKeychain = if (withKeychain) keychain else null,
        ).also { harnesses += it }
    }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private suspend fun EngineHarness.saveProxy(
        name: String = "Office",
        password: String? = "pw",
        username: String? = "ann",
    ) = engine
        .addProxy(
            NewProxy(name, ProxyType.SOCKS5, "proxy.example.com", 1080, username),
            password,
        ).shouldBeInstanceOf<ProxyCommandResult.Saved>()
        .proxy

    private fun via(
        password: String? = "pw",
        username: String? = "ann",
    ) = ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "proxy.example.com", 1080, username, password))

    @Test
    fun aJobsTransferSeesTheSystemProxyByDefault() =
        engineTest {
            system.proxy = SystemProxy(ProxyType.HTTP, "gw.example.com", 3128)
            val h = harness()
            h.engine.start()
            h.addJob()
            advance(2.seconds)

            seen shouldBe listOf(ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "gw.example.com", 3128)))
        }

    @Test
    fun theGlobalChoiceReachesTheJobsTransfer() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            h.engine.setProxyChoice(ProxyChoice.Named(proxy.id)).shouldBeInstanceOf<CommandResult.Done>()
            h.addJob()
            advance(2.seconds)

            seen shouldBe listOf(via())
            h.engine.proxyChoice() shouldBe ProxyChoice.Named(proxy.id)
        }

    @Test
    fun aJobsOwnChoiceBeatsTheGlobalOne() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            h.engine.setProxyChoice(ProxyChoice.Named(proxy.id))
            val id = h.addJob()
            h.engine.setDownloadProxy(id, ProxyChoice.Direct).shouldBeInstanceOf<CommandResult.Done>()
            advance(2.seconds)

            seen.last() shouldBe ProxyRoute.Direct
            h.snapshot(id)!!.download.proxy shouldBe ProxyChoice.Direct
        }

    @Test
    fun aProfilesChoiceReachesItsJobsAndRestartsRunningOnes() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            val profile = (h.engine.addProfile(NewProfile("Work")) as ProfileResult.Saved).profile
            h.engine
                .add(
                    NewDownload(
                        DownloadKind.DIRECT,
                        mirrors = listOf("https://example.com/file-1.zip"),
                        saveFolder = stepRoot.toString(),
                        fileName = "job-1.zip",
                        profileId = profile.id,
                    ),
                ).shouldBeInstanceOf<CommandResult.Done>()
            advance(2.seconds)
            seen.last() shouldBe ProxyRoute.Direct // system, none set

            h.engine.updateProfile(profile.copy(proxy = ProxyChoice.Named(proxy.id))).shouldBeInstanceOf<ProfileResult.Saved>()
            advance(6.seconds)

            seen.last() shouldBe via()
        }

    @Test
    fun deletingAProfileThatGaveAProxyRestartsItsRunningJobsWithoutIt() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            val profile = (h.engine.addProfile(NewProfile("Work", proxy = ProxyChoice.Named(proxy.id))) as ProfileResult.Saved).profile
            h.engine
                .add(
                    NewDownload(
                        DownloadKind.DIRECT,
                        mirrors = listOf("https://example.com/file-1.zip"),
                        saveFolder = stepRoot.toString(),
                        fileName = "job-1.zip",
                        profileId = profile.id,
                    ),
                ).shouldBeInstanceOf<CommandResult.Done>()
            advance(2.seconds)
            seen.last() shouldBe via()

            h.engine.deleteProfile(profile.id) shouldBe 1
            advance(6.seconds)

            seen.last() shouldBe ProxyRoute.Direct
        }

    @Test
    fun changingTheChoiceRestartsARunningTransferWithTheNewRoute() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            val id = h.addJob()
            advance(2.seconds)
            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            seen.size shouldBe 1

            h.engine.setProxyChoice(ProxyChoice.Named(proxy.id))
            advance(6.seconds)

            seen shouldBe listOf(ProxyRoute.Direct, via())
            h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun theGlobalChoiceCannotInheritOrNameAMissingProxy() =
        engineTest {
            val h = harness()
            h.engine.start()

            h.engine.setProxyChoice(ProxyChoice.Inherit).shouldBeInstanceOf<CommandResult.Invalid>()
            h.engine
                .setProxyChoice(
                    ProxyChoice.Named(
                        dev.anydm.engine.core
                            .ProxyId(404),
                    ),
                ).shouldBeInstanceOf<CommandResult.Invalid>()
            h.engine.proxyChoice() shouldBe ProxyChoice.System
        }

    @Test
    fun thePasswordGoesToTheKeychainAndNothingElse() =
        engineTest {
            val h = harness()
            h.engine.start()

            val proxy = h.saveProxy(password = "s3cret")

            keychain.entries[proxy.account] shouldBe "s3cret"
            h.engine.proxies() shouldBe listOf(proxy)
            proxy.toString().contains("s3cret") shouldBe false
        }

    @Test
    fun aPasswordNeedsAUsernameAndAKeychain() =
        engineTest {
            val h = harness()
            h.engine.start()
            h.engine
                .addProxy(NewProxy("A", ProxyType.HTTP, "p.example.com", 8080), "pw")
                .shouldBeInstanceOf<ProxyCommandResult.Invalid>()
            h.engine.proxies() shouldBe emptyList()

            val bare = harness(withKeychain = false)
            bare.engine.start()
            bare.engine
                .addProxy(NewProxy("A", ProxyType.HTTP, "p.example.com", 8080, "ann"), "pw")
                .shouldBeInstanceOf<ProxyCommandResult.KeychainFailed>()
            bare.engine.proxies() shouldBe emptyList()
        }

    @Test
    fun aKeychainThatFailsLeavesNoHalfMadeProxy() =
        engineTest {
            val h = harness()
            h.engine.start()
            keychain.failing = true

            h.engine
                .addProxy(NewProxy("A", ProxyType.HTTP, "p.example.com", 8080, "ann"), "pw")
                .shouldBeInstanceOf<ProxyCommandResult.KeychainFailed>()

            h.engine.proxies() shouldBe emptyList()
        }

    @Test
    fun updatingChangesOrClearsThePassword() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()

            h.engine.updateProxy(proxy.copy(host = "new.example.com")).shouldBeInstanceOf<ProxyCommandResult.Saved>()
            keychain.entries[proxy.account] shouldBe "pw"
            h.engine.updateProxy(proxy, PasswordChange.Set("other")).shouldBeInstanceOf<ProxyCommandResult.Saved>()
            keychain.entries[proxy.account] shouldBe "other"
            h.engine.updateProxy(proxy, PasswordChange.Clear).shouldBeInstanceOf<ProxyCommandResult.Saved>()
            keychain.entries[proxy.account] shouldBe null
            h.engine.updateProxy(proxy.copy(name = "x"), PasswordChange.Keep).shouldBeInstanceOf<ProxyCommandResult.Saved>()
        }

    @Test
    fun aProxyInUseStaysAndAnUnusedOneTakesItsPasswordWithIt() =
        engineTest {
            val h = harness()
            h.engine.start()
            val used = h.saveProxy("Used")
            val spare = h.saveProxy("Spare")
            h.engine.setProxyChoice(ProxyChoice.Named(used.id))

            h.engine.removeProxy(used.id) shouldBe ProxyRemoval.InUse(downloads = 0, profiles = 0, global = true)
            keychain.entries[used.account] shouldBe "pw"

            h.engine.removeProxy(spare.id) shouldBe ProxyRemoval.Removed
            keychain.entries[spare.account] shouldBe null
        }

    @Test
    fun aMissingPasswordBlocksTheRoute() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            keychain.entries.remove(proxy.account)
            h.engine.setProxyChoice(ProxyChoice.Named(proxy.id))
            h.addJob()
            advance(2.seconds)

            seen.single().shouldBeInstanceOf<ProxyRoute.Blocked>()
        }

    @Test
    fun anUpdateThatCantReachTheKeychainChangesNothing() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            keychain.failing = true

            h.engine
                .updateProxy(proxy.copy(host = "new.example.com"), PasswordChange.Set("other"))
                .shouldBeInstanceOf<ProxyCommandResult.KeychainFailed>()

            h.engine
                .proxies()
                .single()
                .host shouldBe "proxy.example.com"
        }

    @Test
    fun aProxyRemovedWhileTheKeychainIsOutLeavesItsPasswordAndSaysSo() =
        engineTest {
            val h = harness()
            h.engine.start()
            val proxy = h.saveProxy()
            keychain.failing = true

            h.engine.removeProxy(proxy.id) shouldBe ProxyRemoval.PasswordLeft

            h.engine.proxies() shouldBe emptyList()
            keychain.entries[proxy.account] shouldBe "pw"
        }
}
