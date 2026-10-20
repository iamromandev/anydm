package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.ftp.CredentialWriter
import dev.anydm.engine.ftp.FakeKeychain
import dev.anydm.engine.ftp.KeychainCredentials
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.http.Credentials
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Logins found in URLs go to the keychain when a download is added (FTP-06), on a new database for each test. */
abstract class EngineFtpLoginContract : StoreTestBase() {
    private val keychain = FakeKeychain()
    private val logins = KeychainCredentials(keychain)

    private suspend fun TestScope.started(writer: CredentialWriter? = logins): EngineHarness =
        EngineHarness(this, newStore(), credentials = writer).also { it.engine.start() }

    private suspend fun EngineHarness.addUrls(
        kind: DownloadKind,
        vararg urls: String,
        sourceUrl: String? = null,
    ): DownloadId =
        engine
            .add(
                NewDownload(
                    kind,
                    mirrors = urls.toList(),
                    saveFolder = stepRoot.toString(),
                    fileName = "job-${urls.first().hashCode()}.bin",
                    sourceUrl = sourceUrl,
                ),
            ).shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    private suspend fun EngineHarness.mirrorUrls(id: DownloadId) = store.downloads.mirrors(id).map { it.url }

    @Test
    fun aLoginInAMirrorUrlIsSavedAndStripped() =
        runTest {
            val h = started()
            try {
                val id = h.addUrls(DownloadKind.FTP, "ftp://ann:pw@host/dir/f.bin")

                h.mirrorUrls(id) shouldBe listOf("ftp://host/dir/f.bin")
                logins.credentialsFor("ftp://host:21") shouldBe Credentials("ann", "pw")
                keychain.entries.isEmpty() shouldBe false
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun sourceUrlIsStrippedToo() =
        runTest {
            val h = started()
            try {
                val id =
                    h.addUrls(DownloadKind.FTP, "ftp://ann:pw@host/s.bin", sourceUrl = "ftp://ann:pw@host/s.bin")

                h.store.downloads
                    .get(id)!!
                    .sourceUrl shouldBe "ftp://host/s.bin"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aUrlWithoutALoginIsUntouched() =
        runTest {
            val h = started()
            try {
                val id = h.addUrls(DownloadKind.FTP, "ftp://host/plain.bin")

                h.mirrorUrls(id) shouldBe listOf("ftp://host/plain.bin")
                keychain.entries.isEmpty() shouldBe true
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aFailingKeychainStillAddsTheDownloadAndEmitsCredentialsNotSaved() =
        runTest {
            val h = started()
            try {
                keychain.failing = true
                val events = mutableListOf<EngineEvent>()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { events += it } }
                runCurrent()

                val id = h.addUrls(DownloadKind.FTP, "ftp://ann:pw@host/fail.bin")
                runCurrent()

                h.mirrorUrls(id) shouldBe listOf("ftp://host/fail.bin")
                val event = events.filterIsInstance<EngineEvent.CredentialsNotSaved>().single()
                event.origin shouldBe "ftp://host:21"
                event.toString() shouldNotContain "pw"
                logins.credentialsFor("ftp://host:21") shouldBe Credentials("ann", "pw")
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aSecondMirrorOfAnotherHostGetsItsOwnLogin() =
        runTest {
            val h = started()
            try {
                val id = h.addUrls(DownloadKind.FTP, "ftp://a:1@one/f.bin", "sftp://b:2@two:2222/f.bin")

                h.mirrorUrls(id) shouldBe listOf("ftp://one/f.bin", "sftp://two:2222/f.bin")
                logins.credentialsFor("ftp://one:21") shouldBe Credentials("a", "1")
                logins.credentialsFor("sftp://two:2222") shouldBe Credentials("b", "2")
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aHttpUrlLoginIsMovedToo() =
        runTest {
            val h = started()
            try {
                val id = h.addUrls(DownloadKind.DIRECT, "https://ann:pw@example.com/f.zip")

                h.mirrorUrls(id) shouldBe listOf("https://example.com/f.zip")
                logins.credentialsFor("https://example.com:443") shouldBe Credentials("ann", "pw")
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun anUnusableLoginIsStrippedAndNotSaved() =
        runTest {
            val h = started()
            try {
                val id = h.addUrls(DownloadKind.FTP, "ftp://a%3Ab:pw@host/odd.bin")

                h.mirrorUrls(id) shouldBe listOf("ftp://host/odd.bin")
                keychain.entries.isEmpty() shouldBe true
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun noWriterLeavesTheUrlAsGiven() =
        runTest {
            val h = started(writer = null)
            try {
                val id = h.addUrls(DownloadKind.FTP, "ftp://ann:pw@host/raw.bin")

                h.mirrorUrls(id) shouldBe listOf("ftp://ann:pw@host/raw.bin")
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun saveKeyLoginStoresItUnderTheOrigin() =
        runTest {
            val h = started()
            try {
                val login = SshKeyLogin("ann", "/home/ann/.ssh/id", "pass")

                h.engine.saveKeyLogin("sftp://host/f.bin", login) shouldBe true

                logins.keyFor("sftp://host:22") shouldBe login
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun saveKeyLoginWithoutAWriterIsFalse() =
        runTest {
            val h = started(writer = null)
            try {
                h.engine.saveKeyLogin("sftp://host/f.bin", SshKeyLogin("ann", "/k", null)) shouldBe false
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun thePasswordIsNowhereInTheStoredRows() =
        runTest {
            val h = started()
            try {
                h.addUrls(DownloadKind.FTP, "ftp://ann:hunter2@host/rows.bin", sourceUrl = "ftp://ann:hunter2@host/rows.bin")

                h.store.downloads
                    .list()
                    .toString() shouldNotContain "hunter2"
                h.store.downloads
                    .allMirrors()
                    .toString() shouldNotContain "hunter2"
                h.engine.downloads.value
                    .toString() shouldNotContain "hunter2"
            } finally {
                h.engine.stop()
            }
        }
}
