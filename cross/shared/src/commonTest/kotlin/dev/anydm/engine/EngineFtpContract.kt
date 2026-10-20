package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.ftp.FakeKeychain
import dev.anydm.engine.ftp.FakeRemoteServer
import dev.anydm.engine.ftp.FtpProtocol
import dev.anydm.engine.ftp.KeychainCredentials
import dev.anydm.engine.ftp.RemoteLogin
import dev.anydm.engine.http.Credentials
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** FTP jobs through the engine (FTP-01 to FTP-03, FTP-06) over an in-memory server, on a new database for each test. */
abstract class EngineFtpContract : StoreTestBase() {
    private val server = FakeRemoteServer()
    private val keychain = FakeKeychain()
    private val logins = KeychainCredentials(keychain)
    private val data = ByteArray(300 * 1024) { (it % 251).toByte() }

    private suspend fun TestScope.started(): EngineHarness {
        val ftp = FtpProtocol(server.factory, logins, logins, connections = { 3 }, minBlock = 64L * 1024)
        return EngineHarness(this, newStore(), protocols = mapOf(DownloadKind.FTP to ftp), credentials = logins).also { it.engine.start() }
    }

    private suspend fun EngineHarness.addFtp(url: String): DownloadId =
        engine
            .add(NewDownload(DownloadKind.FTP, mirrors = listOf(url), saveFolder = stepRoot.toString(), fileName = "f.bin"))
            .shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    @Test
    fun anFtpJobRunsToCompletionThroughTheEngine() =
        runTest {
            server.files["/f.bin"] = data
            val h = started()
            try {
                val id = h.addFtp("ftp://ann:pw@host/f.bin")
                settle()

                h.status(id) shouldBe COMPLETED
                h.fs.read(Path(stepRoot, "f.bin"))!!.contentEquals(data) shouldBe true
                // The login came out of the URL into the keychain, and the transfer found it there.
                server.connects.map { it.second }.toSet() shouldBe setOf(RemoteLogin.Password(Credentials("ann", "pw")))
                h.store.downloads
                    .mirrors(id)
                    .single()
                    .url shouldBe "ftp://host/f.bin"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aMissingFileFailsTheJob() =
        runTest {
            val h = started()
            try {
                val id = h.addFtp("ftp://host/f.bin")
                settle()

                h
                    .snapshot(id)!!
                    .download.status.name shouldBe "FAILED"
            } finally {
                h.engine.stop()
            }
        }
}
