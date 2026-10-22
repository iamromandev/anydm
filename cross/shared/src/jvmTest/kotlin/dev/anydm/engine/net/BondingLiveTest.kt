package dev.anydm.engine.net

import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.http.readAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import kotlin.test.Test

/**
 * Binding against real networks (BND-02), on a machine with two of them up: opt-in with `-Pbonding.live`, which is empty or a
 * URL to fetch (default: 1 MB from Cloudflare). Never part of CI. Run it with Wi-Fi and Ethernet, or a tethered phone, both
 * connected, and read the report it prints: a Linux host without routing rules per network reports BEST_EFFORT here.
 */
class BondingLiveTest {
    private val enabled = System.getProperty("bonding.live") != null
    private val url =
        System.getProperty("bonding.live")?.takeIf { it.startsWith("http") } ?: "https://speed.cloudflare.com/__down?bytes=1000000"

    private fun networks(): Pair<DesktopNetworks, List<NetworkInfo>> {
        assumeTrue("run with -Pbonding.live", enabled)
        val desktop = DesktopNetworks()
        val up = runBlocking { desktop.list() }.filter { it.up }
        println("bonding live: networks ${up.map { it.key }}")
        assumeTrue("needs two networks that are up", up.size >= 2)
        return desktop to up
    }

    @Test
    fun everyNetworkLeavesByItsOwnAddress() {
        val (desktop, up) = networks()
        for (network in up) {
            val status = runBlocking { desktop.probe(network) }
            println("bonding live: ${network.key} -> $status")
            (status == BindStatus.UNSUPPORTED) shouldBe false
            desktop.factoryFor(network).createSocket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 443), 10_000)
                val local = socket.localAddress.hostAddress.substringBefore('%')
                println("bonding live: ${network.key} local address $local")
                (local in network.addresses) shouldBe true
            }
        }
    }

    @Test
    fun aDownloadOnEachNetworkArrivesWhole() =
        runBlocking<Unit> {
            val (desktop, up) = networks()
            val transport = OkHttpTransport(bound = desktop)
            for (network in up) {
                val bytes: ByteArray = withNetwork(network) { transport.open(HttpRequest(url)).body.readAll() }
                println("bonding live: ${network.key} fetched ${bytes.size} bytes")
                bytes.isNotEmpty() shouldBe true
            }
        }
}
