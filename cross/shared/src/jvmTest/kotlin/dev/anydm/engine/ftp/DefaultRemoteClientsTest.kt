package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/** The default factory picks Commons Net for the FTP schemes and sshj for SFTP. */
class DefaultRemoteClientsTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val data = ByteArray(5_000) { (it % 251).toByte() }
    private val login = RemoteLogin.Password(Credentials("ann", "pw"))
    private val clients = DefaultRemoteClients(FakeHostKeyStore())

    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

    @AfterTest
    fun close() = opened.forEach { runCatching { it.close() } }

    @Test
    fun ftpGoesToCommonsNet() =
        blocking {
            val ftp =
                FtpServerFixture().also {
                    opened += it
                    it.put("/a.bin", data)
                }
            val client = clients.create()
            try {
                client.connect(RemoteTarget(FtpScheme.FTP, "127.0.0.1", ftp.port, "/a.bin"), login)

                client.stat("/a.bin").size shouldBe data.size.toLong()
            } finally {
                client.close()
            }
        }

    @Test
    fun sftpGoesToSshj() =
        blocking {
            val sftp =
                SftpServerFixture().also {
                    opened += it
                    it.put("/a.bin", data)
                }
            val client = clients.create()
            try {
                client.connect(RemoteTarget(FtpScheme.SFTP, "127.0.0.1", sftp.port, "/a.bin"), login)

                client.stat("/a.bin").size shouldBe data.size.toLong()
            } finally {
                client.close()
            }
        }
}
