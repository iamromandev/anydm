package dev.anydm.engine.ftp

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.http.Credentials
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.store.HostKey
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/** FTP, FTPS-free SFTP and HTTP logins through a real engine over a file database, with real servers on loopback. */
internal class FtpEndToEndTest : FtpEngineTestBase() {
    private val data = Random(1).nextBytes(4 * 1024 * 1024)

    private fun saved() = File(dl, "data.bin").readBytes()

    @Test
    fun anFtpDownloadWithALoginInTheUrlCompletes() =
        blocking {
            val ftp = ftpServer(bytes = data)
            val engine = newEngine()

            val id = engine.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
            store!!
                .downloads
                .mirrors(id)
                .single()
                .url shouldBe "ftp://127.0.0.1:${ftp.port}/data.bin"
            logins.credentialsFor("ftp://127.0.0.1:${ftp.port}") shouldBe Credentials("ann", "pw")
        }

    @Test
    fun anSftpDownloadWithAKeyLoginCompletes() =
        blocking {
            val key = ClientKey.create(keyDir)
            val sftp = sftpServer(authorized = listOf(key), bytes = data)
            val engine = newEngine()
            val url = "sftp://127.0.0.1:${sftp.port}/data.bin"

            engine.saveKeyLogin(url, SshKeyLogin("ann", key.privateKey.path, null)) shouldBe true
            val id = engine.addUrl(url)
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
            store!!
                .hostKeys
                .get("127.0.0.1", sftp.port)!!
                .fingerprint
                .startsWith("SHA256:") shouldBe true
        }

    @Test
    fun aParallelDownloadUsesSeveralConnections() =
        blocking {
            val ftp = ftpServer(bytes = data)
            val engine = newEngine()

            val id = engine.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")
            await(id, COMPLETED)

            ftp.peakConnections shouldBeGreaterThan 1
            saved().contentEquals(data) shouldBe true
        }

    @Test
    fun aPausedDownloadResumesAfterAnEngineRestart() =
        blocking {
            val ftp = ftpServer(bytes = data)
            val first = newEngine()
            first.setGlobalSpeedLimit(512L * 1024)
            val id = first.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")

            awaitSnapshot(id) { it.downloaded > 700_000 }
            first.pause(id)
            val paused = await(id, PAUSED).downloaded
            first.stop()

            val second = newEngine()
            second.downloads.value
                .first { it.download.id == id }
                .downloaded shouldBeGreaterThanOrEqualTo paused
            second.setGlobalSpeedLimit(null)
            second.resume(id)
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
        }

    @Test
    fun aServerFileThatChangedStartsOver() =
        blocking {
            val ftp = ftpServer(bytes = data)
            val first = newEngine()
            first.setGlobalSpeedLimit(512L * 1024)
            val id = first.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")
            awaitSnapshot(id) { it.downloaded > 700_000 }
            first.pause(id)
            await(id, PAUSED)
            first.stop()

            val changed = Random(2).nextBytes(data.size)
            ftp.put("/data.bin", changed)
            File(ftp.home, "data.bin").setLastModified(System.currentTimeMillis() + 120_000)
            val second = newEngine()
            second.setGlobalSpeedLimit(null)
            second.resume(id)
            await(id, COMPLETED)

            saved().contentEquals(changed) shouldBe true
            events.filterIsInstance<EngineEvent.StartedOver>().shouldNotBeEmpty()
        }

    @Test
    fun aChangedSftpHostKeyFailsAsAuthUntilForgotten() =
        blocking {
            val sftp = sftpServer(bytes = data)
            val engine = newEngine()
            val origin = "sftp://127.0.0.1:${sftp.port}"
            logins.save(origin, Credentials("ann", "pw"))
            store!!.hostKeys.put("127.0.0.1", sftp.port, HostKey("ecdsa-sha2-nistp256", "SHA256:someOtherServersKey"))

            val id = engine.addUrl("$origin/data.bin")
            val failed = await(id, FAILED)

            failed.lastFailure!!.errorClass shouldBe ErrorClass.AUTH
            failed.lastFailure!!.message shouldContain "host key"
            engine.forgetHostKey("127.0.0.1", sftp.port) shouldBe true
            engine.retry(id)
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
        }

    @Test
    fun aFailingKeychainStillDownloads() =
        blocking {
            val ftp = ftpServer(bytes = data)
            val engine = newEngine()
            keychain.failing = true

            val id = engine.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
            events.filterIsInstance<EngineEvent.CredentialsNotSaved>().single().origin shouldBe "ftp://127.0.0.1:${ftp.port}"
        }

    @Test
    fun anHttpDownloadFindsItsLoginInTheKeychain() =
        blocking {
            val http = own(FaultServer())
            http.serve("/data.bin", data)
            http.fault("/data.bin", Fault.Basic("ann", "pw"))
            val engine = newEngine()
            val url = http.url("/data.bin")

            val id = engine.addUrl(url.replace("://", "://ann:pw@"), DownloadKind.DIRECT)
            await(id, COMPLETED)

            saved().contentEquals(data) shouldBe true
            store!!
                .downloads
                .mirrors(id)
                .single()
                .url shouldBe url
        }

    @Test
    fun noSecretReachesTheLogOrTheEvents() =
        blocking {
            val key = ClientKey.create(keyDir, passphrase = "open sesame")
            val ftp = ftpServer(bytes = data)
            val sftp = sftpServer(authorized = listOf(key), path = "/sftp.bin", bytes = data)
            val engine = newEngine()
            val sftpUrl = "sftp://127.0.0.1:${sftp.port}/sftp.bin"
            engine.saveKeyLogin(sftpUrl, SshKeyLogin("ann", key.privateKey.path, "open sesame"))

            val viaFtp = engine.addUrl("ftp://ann:pw@127.0.0.1:${ftp.port}/data.bin")
            await(viaFtp, COMPLETED)
            val viaSftp = engine.addUrl(sftpUrl)
            await(viaSftp, COMPLETED)

            val lines = synchronized(logLines) { logLines.toList() }
            val seen = synchronized(events) { events.toList() }
            val everything = lines.joinToString("\n") + "\n" + seen.joinToString("\n") + "\n" + engine.downloads.value.joinToString("\n")
            (everything.contains("ann:pw")) shouldBe false
            (everything.contains("open sesame")) shouldBe false
            lines.shouldNotBeEmpty()
        }
}
