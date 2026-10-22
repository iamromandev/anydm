package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.platform.FakeNetworkBinder
import dev.anydm.engine.platform.FakeNetworkLister
import dev.anydm.engine.platform.network
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The engine lists the networks, keeps the user's choice and binds downloads to them (BND-01 to BND-03, FTP and SFTP). */
abstract class EngineBondingContract : StoreTestBase() {
    private class Fixture(
        val h: EngineHarness,
        val lister: FakeNetworkLister,
        val binder: FakeNetworkBinder,
        val ftp: FakeProtocol,
    )

    private suspend fun TestScope.started(
        names: Array<out String>,
        prepare: (FakeNetworkBinder) -> Unit,
        proxyRouter: ProxyRouter,
    ): Fixture {
        val lister = FakeNetworkLister(*names.map { network(it) }.toTypedArray())
        val binder = FakeNetworkBinder().also(prepare)
        val direct = FakeProtocol()
        val ftp = FakeProtocol()
        val protocols = DownloadKind.entries.associateWith { if (it == DownloadKind.FTP) ftp else direct }
        val h =
            EngineHarness(
                this,
                newStore(),
                direct,
                protocols = protocols,
                networkLister = lister,
                networkBinder = binder,
                proxyRouter = proxyRouter,
            )
        h.engine.start()
        runCurrent()
        return Fixture(h, lister, binder, ftp)
    }

    // The engine's timers never end: a test that fails before engine.stop() would leave runTest advancing time for good.
    private fun bondingTest(
        vararg names: String,
        prepare: (FakeNetworkBinder) -> Unit = {},
        proxyRouter: ProxyRouter = ProxyRouter.Direct,
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        val f = started(names, prepare, proxyRouter)
        try {
            body(f)
        } finally {
            f.h.engine.stop()
        }
    }

    private var ftpJobs = 0

    private suspend fun EngineHarness.addFtp(): DownloadId =
        engine
            .add(
                NewDownload(
                    DownloadKind.FTP,
                    mirrors = listOf("ftp://example.com/f${++ftpJobs}.bin"),
                    saveFolder =
                        dev.anydm.engine.core.stepRoot
                            .toString(),
                    fileName = "f$ftpJobs.bin",
                ),
            ).shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    @Test
    fun theMachinesNetworksAreListedAndNoneIsOnByDefault() =
        bondingTest("en0", "en5") { f ->

            val state = f.h.engine.networks.value
            state.networks.map { it.info.key } shouldBe listOf("en0", "en5")
            state.networks.none { it.enabled } shouldBe true
            state.bonded shouldBe false
        }

    @Test
    fun aChoiceIsSavedInTheSettings() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en5", true)

            f.h.store.settings
                .load()
                .bondedNetworks shouldBe setOf("en5")
            f.h.engine.networks.value.networks
                .first { it.info.key == "en5" }
                .enabled shouldBe true
        }

    @Test
    fun aDirectDownloadRunsInABondingScopeOnlyWithTwoNetworksOn() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.addJob()
            advanceTimeBy(5.seconds)
            f.h.protocol.bondings
                .map { it.first } shouldBe listOf(false)

            f.h.engine.setNetworkEnabled("en5", true)
            f.h.addJob()
            advanceTimeBy(5.seconds)
            f.h.protocol.bondings
                .last()
                .first shouldBe true
        }

    @Test
    fun wholeFtpDownloadsAreSpreadAcrossTheNetworks() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.engine.setNetworkEnabled("en5", true)
            f.h.addFtp()
            f.h.addFtp()
            runCurrent()
            advanceTimeBy(1.seconds)

            f.ftp.bondings
                .map { it.second }
                .toSet() shouldBe setOf("en0", "en5")
        }

    @Test
    fun anFtpDownloadIsNotBoundWithOneNetwork() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.addFtp()
            runCurrent()
            advanceTimeBy(1.seconds)

            f.ftp.bondings.map { it.second } shouldBe listOf<String?>(null)
        }

    @Test
    fun anFtpDownloadThroughAProxyIsNotBound() =
        bondingTest(
            "en0",
            "en5",
            proxyRouter =
                ProxyRouter {
                    _,
                    _,
                    ->
                    ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "proxy", 1080))
                },
        ) { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.engine.setNetworkEnabled("en5", true)
            f.h.addFtp()
            runCurrent()
            advanceTimeBy(1.seconds)

            f.h.engine.networks.value.bonded shouldBe true
            f.ftp.bondings.map { it.second } shouldBe listOf<String?>(null)
        }

    @Test
    fun aNetworkThatCannotBeBoundIsNotUsed() =
        bondingTest("en0", "en5", prepare = { it.statuses["en5"] = BindStatus.UNSUPPORTED }) { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.engine.setNetworkEnabled("en5", true)
            f.h.addFtp()
            runCurrent()
            advanceTimeBy(1.seconds)

            f.h.engine.networks.value.bonded shouldBe false
            f.ftp.bondings.map { it.second } shouldBe listOf<String?>(null)
        }

    @Test
    fun connectionsStartAgainWhenTheNetworksInUseChange() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.addJob()
            advanceTimeBy(1.seconds)
            f.h.protocol.transfers.size shouldBe 1

            f.h.engine.setNetworkEnabled("en5", true)
            advanceTimeBy(1.seconds)

            // Bonding began: the running download is stopped and started again inside it.
            (f.h.protocol.transfers.size >= 2) shouldBe true
            f.h.protocol.bondings
                .last()
                .first shouldBe true
        }

    @Test
    fun aNetworkThatLeavesEndsBondingForTheRunningDownloads() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.engine.setNetworkEnabled("en5", true)
            f.h.addJob()
            advanceTimeBy(1.seconds)
            val before = f.h.protocol.transfers.size

            f.lister.networks = listOf(network("en0"))
            advanceTimeBy(10.seconds)

            (f.h.protocol.transfers.size > before) shouldBe true
            f.h.protocol.bondings
                .last()
                .first shouldBe false
        }

    @Test
    fun bytesOnANetworkSetItsSpeed() =
        bondingTest("en0", "en5") { f ->
            f.h.engine.setNetworkEnabled("en0", true)
            f.h.engine.setNetworkEnabled("en5", true)
            f.h.protocol.network = "en5"
            f.h.addJob()
            advanceTimeBy(2.seconds)
            // The speed shows in the list at the next refresh.
            advanceTimeBy(10.seconds)

            val en5 =
                f.h.engine.networks.value.networks
                    .first { it.info.key == "en5" }
            (en5.speed != null && en5.speed!! > 0) shouldBe true
        }
}
