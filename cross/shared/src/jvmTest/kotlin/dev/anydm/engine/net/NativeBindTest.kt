package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The native handle of a Java socket and the options that tie it to an interface (BND-02), against the real OS. */
class NativeBindTest {
    private val os = System.getProperty("os.name").lowercase()
    private val supported = "mac" in os || "linux" in os

    private fun loopback(): NetworkInfo {
        val nic =
            NetworkInterface
                .networkInterfaces()
                .filter { it.isLoopback && it.isUp }
                .findFirst()
                .get()
        return NetworkInfo(nic.name, nic.displayName, up = true, addresses = emptyList(), index = nic.index)
    }

    // Linux before 5.7 lets only a process with CAP_NET_RAW tie a socket to a device: such a machine skips, it doesn't fail.
    private fun bindOrSkip(
        socket: Socket,
        network: NetworkInfo,
    ) {
        try {
            NativeBind.bind(socket, network)
        } catch (e: BindFailure) {
            assumeTrue("not allowed to bind a device here: ${e.message}", "mac" in os)
            throw e
        }
    }

    @Test
    fun theHandleIsTheSocketsOwn() {
        Socket(java.net.Proxy.NO_PROXY).use { socket ->
            (SocketFd.of(socket) > 2) shouldBe true
        }
    }

    @Test
    fun aSocketBoundToTheLoopbackStillReachesALoopbackServer() {
        if (!supported) return
        EchoServer().use { echo ->
            Socket(java.net.Proxy.NO_PROXY).use { socket ->
                bindOrSkip(socket, loopback())
                socket.connect(echo.address, 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().write("hello".toByteArray())
                socket.getOutputStream().flush()
                String(socket.getInputStream().readNBytes(5)) shouldBe "hello"
            }
        }
    }

    @Test
    fun theOptionIsReadBackFromTheSocket() {
        if (!supported) return
        Socket(java.net.Proxy.NO_PROXY).use { socket ->
            val lo = loopback()
            bindOrSkip(socket, lo)
            NativeBind.boundTo(socket) shouldBe lo.key
        }
    }

    @Test
    fun aSocketBoundToAnotherInterfaceDoesNotReachTheLoopback() {
        if (!supported) return
        val other =
            NetworkInterface
                .networkInterfaces()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.inetAddresses().findAny().isPresent }
                .findFirst()
        if (other.isEmpty) return
        val nic = other.get()
        val info = NetworkInfo(nic.name, nic.displayName, up = true, addresses = emptyList(), index = nic.index)
        EchoServer().use { echo ->
            Socket(java.net.Proxy.NO_PROXY).use { socket ->
                bindOrSkip(socket, info)
                assertFailsWith<java.io.IOException> { socket.connect(InetSocketAddress(echo.address.address, echo.address.port), 3000) }
            }
        }
    }

    @Test
    fun anInterfaceThatDoesNotExistIsRefused() {
        if (!supported) return
        Socket(java.net.Proxy.NO_PROXY).use { socket ->
            assertFailsWith<BindFailure> {
                NativeBind.bind(socket, NetworkInfo("nope0", "nope0", up = true, addresses = emptyList(), index = 99999))
            }
        }
    }
}
