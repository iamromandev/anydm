package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import dev.anydm.engine.net.FakeBoundSockets
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.withNetwork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/** FTP, FTPS and SFTP connections made inside a bound network leave by it, data connections included (BND-02). */
class FtpBoundTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val data = ByteArray(5_000) { (it % 251).toByte() }
    private val login = RemoteLogin.Password(Credentials("ann", "pw"))
    private val sockets = FakeBoundSockets()
    private val clients = DefaultRemoteClients(FakeHostKeyStore(), bound = sockets)
    private val phone = NetworkInfo("en5", "Phone", up = true, addresses = listOf("172.20.10.2"), index = 9)

    @AfterTest
    fun close() = opened.forEach { runCatching { it.close() } }

    private fun ftpServer() =
        FtpServerFixture().also {
            opened += it
            it.put("/a.bin", data)
        }

    private fun sftpServer() =
        SftpServerFixture().also {
            opened += it
            it.put("/a.bin", data)
        }

    private suspend fun readAll(
        scheme: FtpScheme,
        port: Int,
    ): ByteArray {
        val client = clients.create()
        try {
            client.connect(RemoteTarget(scheme, "127.0.0.1", port, "/a.bin"), login)
            val out = java.io.ByteArrayOutputStream()
            client.read("/a.bin", 0, null) { bytes, n -> out.write(bytes, 0, n) }
            return out.toByteArray()
        } finally {
            client.close()
        }
    }

    @Test
    fun ftpControlAndDataConnectionsAreBound() =
        runBlocking<Unit> {
            val server = ftpServer()

            withNetwork(phone) { readAll(FtpScheme.FTP, server.port) shouldBe data }

            // One control and one data connection, both on the phone.
            (sockets.madeOn("en5") >= 2) shouldBe true
            sockets.made.keys shouldBe setOf("en5")
        }

    @Test
    fun sftpIsBound() =
        runBlocking<Unit> {
            val server = sftpServer()

            withNetwork(phone) { readAll(FtpScheme.SFTP, server.port) shouldBe data }

            (sockets.madeOn("en5") >= 1) shouldBe true
        }

    @Test
    fun withoutANetworkNothingIsBound() =
        runBlocking<Unit> {
            val server = ftpServer()

            readAll(FtpScheme.FTP, server.port) shouldBe data

            sockets.made.isEmpty() shouldBe true
        }

    @Test
    fun aNetworkThatCannotBeBoundFailsFtpWithoutUsingTheDefaultRoute() =
        runBlocking<Unit> {
            val server = ftpServer()
            sockets.refusing += "en5"

            val failure = shouldThrow<RemoteException> { withNetwork(phone) { readAll(FtpScheme.FTP, server.port) } }

            failure.failure shouldBe RemoteFailure.BIND
        }

    @Test
    fun aNetworkThatCannotBeBoundFailsSftpWithoutUsingTheDefaultRoute() =
        runBlocking<Unit> {
            val server = sftpServer()
            sockets.refusing += "en5"

            val failure = shouldThrow<RemoteException> { withNetwork(phone) { readAll(FtpScheme.SFTP, server.port) } }

            failure.failure shouldBe RemoteFailure.BIND
        }

    @Test
    fun withNoWayToBindABoundConnectionFails() =
        runBlocking<Unit> {
            val server = ftpServer()

            val failure =
                shouldThrow<RemoteException> {
                    withNetwork(phone) {
                        DefaultRemoteClients(
                            FakeHostKeyStore(),
                        ).create().connect(RemoteTarget(FtpScheme.FTP, "127.0.0.1", server.port, "/a.bin"), login)
                    }
                }

            failure.failure shouldBe RemoteFailure.BIND
        }
}
