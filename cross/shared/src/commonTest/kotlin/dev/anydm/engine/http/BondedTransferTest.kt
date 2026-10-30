package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.net.FakeBond
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyScope
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.currentBoundNetwork
import dev.anydm.engine.net.withBond
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One download over several networks (BND-02, BND-03): workers take a network each, and the blocks are pulled as before. */
class BondedTransferTest {
    private val mib = 1L shl 20
    private val size = 40 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
    private val wifi = NetworkInfo("en0", "Wi-Fi", up = true, addresses = listOf("192.168.0.2"), index = 4)
    private val phone = NetworkInfo("en5", "Phone", up = true, addresses = listOf("172.20.10.2"), index = 9)

    private fun transferOf(segments: List<Segment> = this.segments): Transfer {
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, null, true, "https://h/f.bin", null)
        val mirror = Mirror(MirrorId(1), DownloadId(1), "https://h/f.bin", 0)
        return Transfer(aDownload(), mirror, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))))
    }

    private fun TestScope.protocol(transport: FakeTransport) =
        HttpProtocol(transport, connections = { 4 }, timeSource = testScheduler.timeSource)

    /** Serves ranges; every body is paced by the network the request was made on (null: the default route). */
    private class Network(
        val content: ByteArray,
        val every: (String?) -> kotlin.time.Duration,
        val failBindOn: Set<String> = emptySet(),
        val failConnectOn: Set<String> = emptySet(),
    ) {
        val requests = mutableListOf<String?>()
        val bodies = mutableListOf<Pair<String?, PacedBody>>()

        fun transport() =
            FakeTransport { request ->
                val net = currentBoundNetwork()?.key
                requests += net
                if (net in failBindOn) throw HttpIoException(HttpIoKind.BIND, "h", detail = "$net is gone")
                if (net in failConnectOn) throw HttpIoException(HttpIoKind.CONNECT, "h")
                pacedRanges(content) { PacedBody(it, every(net)).also { body -> bodies += net to body } }(request)
            }

        fun open(net: String?): Int = bodies.count { it.first == net && !it.second.closed }
    }

    @Test
    fun bothNetworksCarryBytesAndTheFileIsWhole() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val io = RecordingIo(size, segments)
            val bond = FakeBond(listOf(wifi, phone))

            val result = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), io) } }
            advanceTimeBy(120.seconds)

            result.await() shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            net.requests.toSet() shouldBe setOf("en0", "en5")
            io.meter.byNetwork.keys shouldBe setOf("en0", "en5")
            io.meter.byNetwork.values
                .sum() shouldBe size
        }

    @Test
    fun connectionsFollowTheMeasuredSpeed() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val bond = FakeBond(listOf(wifi, phone), speeds = mapOf("en0" to 3000.0, "en5" to 1000.0))
            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), RecordingIo(size, segments)) } }

            advanceTimeBy(500.milliseconds)
            val seen = mutableListOf<Pair<Int, Int>>()
            repeat(8) {
                runCurrent()
                seen += net.open("en0") to net.open("en5")
                advanceTimeBy(1.seconds)
            }

            seen.last() shouldBe (3 to 1)
            // Never more than its share, on the way up too.
            seen.all { (a, b) -> a <= 3 && b <= 1 } shouldBe true
            advanceTimeBy(120.seconds)
            run.await() shouldBe StepResult.Ok(Unit)
        }

    @Test
    fun aNetworkThatLeavesGetsNoMoreBlocksAndTheFileStillCompletes() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val io = RecordingIo(size, segments)
            val bond = FakeBond(listOf(wifi, phone))
            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), io) } }

            advanceTimeBy(3.seconds)
            bond.networks = listOf(wifi)
            runCurrent()
            val onPhoneAtTheDrop = net.requests.count { it == "en5" }
            (onPhoneAtTheDrop >= 1) shouldBe true
            advanceTimeBy(120.seconds)

            run.await() shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            net.requests.count { it == "en5" } shouldBe onPhoneAtTheDrop
            bond.faults shouldBe emptyList()
        }

    @Test
    fun aBindFailureFaultsTheNetworkAndEndsTheRunWithoutGoingByTheDefaultRoute() =
        runTest {
            val net = Network(content, { 100.milliseconds }, failBindOn = setOf("en5"))
            val bond = FakeBond(listOf(wifi, phone))
            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), RecordingIo(size, segments)) } }

            advanceTimeBy(60.seconds)

            val result = run.await()
            (result is StepResult.Failed) shouldBe true
            (result as StepResult.Failed).errorClass shouldBe ErrorClass.TRANSIENT
            bond.faults shouldBe listOf("en5")
            net.requests.none { it == null } shouldBe true
        }

    @Test
    fun aProxiedDownloadIsNotBonded() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val io = RecordingIo(size, segments)
            val bond = FakeBond(listOf(wifi, phone))
            val via = ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "proxy", 1080))

            val run =
                async {
                    withContext(ProxyScope { via }) { withBond(bond) { protocol(net.transport()).transfer(transferOf(), io) } }
                }
            advanceTimeBy(120.seconds)

            run.await() shouldBe StepResult.Ok(Unit)
            net.requests.toSet() shouldBe setOf<String?>(null)
            io.meter.byNetwork.keys shouldBe setOf<String?>(null)
        }

    @Test
    fun oneNetworkInUseIsNotBonding() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val bond = FakeBond(listOf(wifi))

            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), RecordingIo(size, segments)) } }
            advanceTimeBy(120.seconds)

            run.await() shouldBe StepResult.Ok(Unit)
            net.requests.toSet() shouldBe setOf<String?>(null)
        }

    @Test
    fun aWholeFileStreamIsBoundToo() =
        runTest {
            val net = Network(content, { 100.milliseconds })
            val transport =
                FakeTransport { request ->
                    net.requests += currentBoundNetwork()?.key
                    response(
                        200,
                        "Content-Length" to "${content.size}",
                        finalUrl = "https://h/f.bin",
                        body = PacedBody(content, 100.milliseconds),
                    )
                }
            val io = RecordingIo(size, emptyList())
            val bond = FakeBond(listOf(wifi, phone), speeds = mapOf("en0" to 1000.0, "en5" to 2000.0))

            val run = async { withBond(bond) { protocol(transport).transfer(transferOf(emptyList()), io) } }
            advanceTimeBy(120.seconds)

            run.await() shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            // The faster network takes it; the unused one carries nothing.
            net.requests.toSet() shouldBe setOf("en5")
            io.meter.byNetwork.keys shouldBe setOf("en5")
        }

    @Test
    fun theSharesMoveWhenTheSpeedsChange() =
        runTest {
            // Slow connections, so a block takes 16 s and a worker comes to its next decision about then.
            val net = Network(content, { 400.milliseconds })
            val bond = FakeBond(listOf(wifi, phone), speeds = mapOf("en0" to 3000.0, "en5" to 1000.0))
            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), RecordingIo(size, segments)) } }
            advanceTimeBy(8.seconds)
            runCurrent()
            (net.open("en0") to net.open("en5")) shouldBe (3 to 1)

            bond.speeds = mapOf("en0" to 1000.0, "en5" to 3000.0)
            advanceTimeBy(48.seconds)
            runCurrent()

            // Workers gave their place as they finished blocks, at most one in 5 s, and the ramp refilled on the other network.
            (net.open("en0") to net.open("en5")) shouldBe (1 to 3)
            advanceTimeBy(300.seconds)
            run.await() shouldBe StepResult.Ok(Unit)
        }

    @Test
    fun aNetworkThatCannotBeReachedIsLeftOutAfterItsTries() =
        runTest {
            val net = Network(content, { 100.milliseconds }, failConnectOn = setOf("en5"))
            val bond = FakeBond(listOf(wifi, phone))
            val run = async { withBond(bond) { protocol(net.transport()).transfer(transferOf(), RecordingIo(size, segments)) } }

            advanceTimeBy(60.seconds)

            // The healthy network is not blamed, and the run is not ended on the first exhausted block: the restart that
            // follows a fault (in the engine) takes the dead network out of the next run.
            bond.faults shouldBe listOf("en5")
            (run.await() is StepResult.Failed) shouldBe true
        }

    @Test
    fun aBindFailureOnAWholeFileStreamFaultsTheNetwork() =
        runTest {
            val net = Network(content, { 100.milliseconds }, failBindOn = setOf("en5"))
            val transport =
                FakeTransport { request ->
                    val key = currentBoundNetwork()?.key
                    net.requests += key
                    if (key == "en5") throw HttpIoException(HttpIoKind.BIND, "h", detail = "en5 is gone")
                    response(
                        200,
                        "Content-Length" to "${content.size}",
                        finalUrl = "https://h/f.bin",
                        body = PacedBody(content, 100.milliseconds),
                    )
                }
            val bond = FakeBond(listOf(wifi, phone), speeds = mapOf("en0" to 1000.0, "en5" to 2000.0))

            val run = async { withBond(bond) { protocol(transport).transfer(transferOf(emptyList()), RecordingIo(size, emptyList())) } }
            advanceTimeBy(60.seconds)

            bond.faults.toSet() shouldBe setOf("en5")
            (run.await() is StepResult.Failed) shouldBe true
            net.requests.none { it == null } shouldBe true
        }
}
