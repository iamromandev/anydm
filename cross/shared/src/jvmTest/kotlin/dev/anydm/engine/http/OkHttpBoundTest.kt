package dev.anydm.engine.http

import dev.anydm.engine.net.FakeBoundSockets
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyScope
import dev.anydm.engine.net.withNetwork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test

/** Connections made inside a bound network leave by it, and by nothing else (BND-02, BND-05). */
class OkHttpBoundTest {
    private val server = FaultServer()
    private val sockets = FakeBoundSockets()
    private val transport = OkHttpTransport(bound = sockets)
    private val file = ByteArray(5000) { (it % 251).toByte() }
    private val wifi = NetworkInfo("en0", "Wi-Fi", up = true, addresses = listOf("192.168.0.2"), index = 4)
    private val phone = NetworkInfo("en5", "Phone", up = true, addresses = listOf("172.20.10.2"), index = 9)

    @AfterTest
    fun close() = server.close()

    private suspend fun get(path: String = "/a.bin") = transport.open(HttpRequest(server.url(path)))

    @Test
    fun eachNetworkMakesItsOwnConnectionsAndKeepsThem() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)

            withNetwork(wifi) { get().body.readAll() shouldBe file }
            // A connection attempt may try more than one address, so count what each network made rather than expecting one.
            val onWifi = sockets.madeOn("en0")
            (onWifi >= 1) shouldBe true
            sockets.madeOn("en5") shouldBe 0

            withNetwork(phone) { get().body.readAll() shouldBe file }
            val onPhone = sockets.madeOn("en5")
            (onPhone >= 1) shouldBe true
            // The wifi connection was not used for the phone's request.
            sockets.madeOn("en0") shouldBe onWifi

            // Each network reuses its own pooled connection.
            withNetwork(phone) { get().body.readAll() shouldBe file }
            withNetwork(wifi) { get().body.readAll() shouldBe file }
            sockets.madeOn("en5") shouldBe onPhone
            sockets.madeOn("en0") shouldBe onWifi
        }

    @Test
    fun aNetworkThatCameBackChangedGetsItsOwnClientAndLeavesTheOldOneAlone() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            val renewed = wifi.copy(index = 5)

            withNetwork(wifi) { get().body.readAll() }
            val before = sockets.madeOn("en0")
            withNetwork(renewed) { get().body.readAll() }
            val both = sockets.madeOn("en0")
            (both > before) shouldBe true

            // Workers that started before the change still use the first client and its pooled connection.
            withNetwork(wifi) { get().body.readAll() }
            withNetwork(renewed) { get().body.readAll() }
            sockets.madeOn("en0") shouldBe both
        }

    @Test
    fun withoutANetworkTheDefaultRouteIsUsed() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)

            get().body.readAll() shouldBe file

            sockets.made.isEmpty() shouldBe true
        }

    @Test
    fun aNetworkThatCannotBeBoundFailsTheRequestWithoutGoingByTheDefaultRoute() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            sockets.refusing += "en5"

            val failure = shouldThrow<HttpIoException> { withNetwork(phone) { get() } }

            failure.kind shouldBe HttpIoKind.BIND
            server.requests.size shouldBe 0
        }

    @Test
    fun withNoWayToBindABoundRequestFails() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)

            val failure = shouldThrow<HttpIoException> { withNetwork(wifi) { OkHttpTransport().open(HttpRequest(server.url("/a.bin"))) } }

            failure.kind shouldBe HttpIoKind.BIND
            server.requests.size shouldBe 0
        }

    @Test
    fun aBlockedProxyRouteStillBlocksABoundRequest() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)

            val failure =
                shouldThrow<HttpIoException> {
                    withContext(ProxyScope { ProxyRoute.Blocked("the proxy is gone") }) { withNetwork(wifi) { get() } }
                }

            failure.kind shouldBe HttpIoKind.PROXY
            sockets.made.isEmpty() shouldBe true
        }
}
