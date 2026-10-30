package dev.anydm.engine.net

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The machine's own networks and the sockets tied to them (BND-01, BND-02), against the real OS. */
class DesktopNetworksTest {
    private val os = System.getProperty("os.name").lowercase()
    private val supported = "mac" in os || "linux" in os

    private fun loopback(): NetworkInfo {
        val nic =
            NetworkInterface
                .networkInterfaces()
                .filter { it.isLoopback && it.isUp }
                .findFirst()
                .get()
        val addresses = nic.inetAddresses().map { it.hostAddress.substringBefore('%') }.toList()
        return NetworkInfo(nic.name, nic.displayName, up = true, addresses = addresses, index = nic.index)
    }

    @Test
    fun theListHasNoLoopbackNoTunnelAndNoNetworkWithoutAnAddress() =
        runTest {
            val listed = DesktopNetworks().list()

            listed.map { it.key } shouldNotContain loopback().key
            listed.map { it.key }.distinct().size shouldBe listed.size
            listed.forEach { network ->
                network.addresses.isNotEmpty() shouldBe true
                network.addresses.none { it.startsWith("127.") || it.startsWith("169.254.") || it.startsWith("fe80") } shouldBe true
                NetworkInterface.getByName(network.key)?.isPointToPoint shouldBe false
            }
        }

    @Test
    fun aNetworkThatCanBeBoundIsProbedAsSuch() =
        runTest {
            if (!supported) return@runTest
            val status = DesktopNetworks().probe(loopback())
            // macOS always ties by interface; Linux may be refused without the right to, and then falls back to the address.
            if ("mac" in os) status shouldBe BindStatus.ISOLATED else (status != BindStatus.UNSUPPORTED) shouldBe true
        }

    @Test
    fun aNetworkThatDoesNotExistCannotBeBound() =
        runTest {
            if (!supported) return@runTest
            DesktopNetworks().probe(NetworkInfo("nope0", "nope0", up = true, addresses = emptyList(), index = 99999)) shouldBe
                BindStatus.UNSUPPORTED
        }

    @Test
    fun socketsFromTheFactoryAreTiedToTheirNetwork() =
        runTest {
            if (!supported) return@runTest
            val networks = DesktopNetworks()
            val lo = loopback()
            networks.probe(lo)
            EchoServer().use { echo ->
                networks.factoryFor(lo).createSocket().use { socket ->
                    socket.connect(echo.address, 5000)
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("hello".toByteArray())
                    socket.getOutputStream().flush()
                    String(socket.getInputStream().readNBytes(5)) shouldBe "hello"
                }
                networks.factoryFor(lo).createSocket(echo.address.address, echo.address.port).use { socket ->
                    socket.isConnected shouldBe true
                }
            }
        }

    @Test
    fun aFactoryForANetworkThatCannotBeBoundFailsInsteadOfUsingTheDefaultRoute() =
        runTest {
            if (!supported) return@runTest
            val networks = DesktopNetworks()
            val gone = NetworkInfo("nope0", "nope0", up = true, addresses = emptyList(), index = 99999)
            networks.probe(gone)
            assertFailsWith<BindFailure> { networks.factoryFor(gone).createSocket() }
            assertFailsWith<BindFailure> { networks.factoryFor(gone).createSocket("127.0.0.1", 9) }
        }

    @Test
    fun aFactoryWorksBeforeTheNetworkWasProbed() =
        runTest {
            if (!supported) return@runTest
            EchoServer().use { echo ->
                DesktopNetworks().factoryFor(loopback()).createSocket().use { socket ->
                    socket.connect(echo.address, 5000)
                    socket.isConnected shouldNotBe false
                }
            }
        }
}
